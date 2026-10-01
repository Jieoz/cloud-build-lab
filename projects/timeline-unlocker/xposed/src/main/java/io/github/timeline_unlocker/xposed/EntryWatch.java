package io.github.timeline_unlocker.xposed;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Long-running evidence for "the entry is gone a day later", Maps main process only, always on
 * (independent of the debug-log switch, so nothing needs to be left on for days).
 *
 * <p>Writes one small file, {@code Download/TimelineUnlocker/timeline-watch-maps.txt}, with only
 * state changes:</p>
 * <ul>
 *   <li>{@code start}: every Maps launch with the identity Maps was given (cn/us).</li>
 *   <li>{@code entry}: entry seen / not seen, only when it differs from the last recorded state,
 *       so the line where it flips to {@code no} dates the loss.</li>
 *   <li>{@code config}: Maps' own flag/experiment/prefs files that changed since the previous
 *       launch (size and mtime), so the flip can be matched to the refresh that caused it.</li>
 *   <li>{@code read}: each new (method, value, caller) country/operator read, with the first
 *       app frames, so the code path that fetched the config is named by runtime evidence.</li>
 * </ul>
 * State between launches lives in Maps' own no-backup dir. All work runs off the main thread.
 */
final class EntryWatch {

    static final String FILE = "timeline-watch-maps.txt";
    private static final String STATE = "timeline-unlocker-watch.properties";
    private static final int MAX_READS_PER_LAUNCH = 60;
    private static final int MAX_CALLERS_PER_DAY = 150;
    private static final int MAX_FILES = 400;
    /** Bound on directory entries looked at, so a large tile cache cannot slow a launch. */
    private static final int MAX_VISITED = 4000;
    private static final int MAX_CONFIG_LINES = 25;

    private static volatile Context context;
    private static volatile String identity = "?";
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "timeline-unlocker-watch");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger reads = new AtomicInteger();
    private static final Properties state = new Properties();
    private static volatile boolean loaded;
    private static volatile boolean armed;
    /** Reads seen between onPackageReady and Application create (the startup reads). */
    private static final List<String[]> early = new java.util.ArrayList<>();

    private EntryWatch() {}

    /** Maps main process only, before any read happens. */
    static void arm() {
        armed = true;
    }

    static boolean armed() {
        return armed && context != null;
    }

    static void start(Context app, String mapsIdentity, String module) {
        if (!armed || context != null || app == null) return;
        synchronized (early) {
            context = app;
        }
        identity = mapsIdentity;
        io.execute(() -> {
            load();
            write("start identity=" + mapsIdentity + " module=" + module
                    + " last-entry=" + state.getProperty("entry", "?"));
            configDiff();
            save();
        });
        java.util.List<String[]> queued;
        synchronized (early) {
            queued = new java.util.ArrayList<>(early);
            early.clear();
        }
        for (String[] r : queued) record(r[0], r[1], r[2]);
    }

    private static volatile boolean seenThisLaunch;

    /** With the debug log off, scans stop once the entry was seen this launch. */
    static boolean settled() {
        return seenThisLaunch;
    }

    /**
     * One entry scan. {@code yes} is recorded the first time it is seen in a launch; {@code no}
     * only after the last scan of a resume found nothing and nothing was seen this launch, so
     * moving between screens does not flip the record. Either is written only on a change.
     */
    static void entry(boolean found, String hits, boolean lastScan) {
        if (context == null) return;
        if (found) {
            if (seenThisLaunch) return;
            seenThisLaunch = true;
        } else if (!lastScan || seenThisLaunch) {
            return;
        }
        io.execute(() -> {
            load();
            String now = found ? "yes" : "no";
            if (now.equals(state.getProperty("entry"))) return;
            state.setProperty("entry", now);
            write("entry " + now + " identity=" + identity + (found ? " hits=" + hits : ""));
            save();
        });
    }

    /** A country/operator read inside Maps, with its caller. Capped per launch, deduped forever. */
    static void read(String member, String value) {
        if (!armed || !WatchState.regionRead(member)) return;
        if (reads.incrementAndGet() > MAX_READS_PER_LAUNCH) return;
        String caller = WatchState.callerSignature(new Throwable().getStackTrace());
        if (context == null) {
            synchronized (early) {
                if (context == null) {
                    early.add(new String[]{member, value, caller});
                    return;
                }
            }
        }
        record(member, value, caller);
    }

    private static void record(String member, String value, String caller) {
        io.execute(() -> {
            load();
            // Deduped per day: the refresh that drops the entry shows up on the day it happens.
            String key = "c:" + day() + ":" + member + "=" + value + "@" + caller;
            if (state.containsKey(key)) return;
            if (callerCount() >= MAX_CALLERS_PER_DAY) return;
            state.setProperty(key, stamp());
            write("read " + member + " -> " + value + " by " + caller);
            save();
        });
    }

    private static int callerCount() {
        int n = 0;
        for (Object k : state.keySet()) if (String.valueOf(k).startsWith("c:")) n++;
        return n;
    }

    /** Diff Maps' flag/experiment/prefs files against the previous launch. */
    private static void configDiff() {
        File root = context.getDataDir();
        Map<String, String> now = new TreeMap<>();
        scan(root, root, now, 0, new int[]{0});
        Map<String, String> before = new TreeMap<>();
        for (String k : state.stringPropertyNames()) {
            if (k.startsWith("f:")) before.put(k.substring(2), state.getProperty(k));
        }
        if (before.isEmpty()) {
            write("config baseline files=" + now.size());
        } else {
            List<String> lines = WatchState.diff(before, now, MAX_CONFIG_LINES);
            for (String line : lines) write("config " + line);
        }
        for (String k : new HashSet<>(state.stringPropertyNames())) {
            if (k.startsWith("f:")) state.remove(k);
        }
        for (Map.Entry<String, String> e : now.entrySet()) state.setProperty("f:" + e.getKey(), e.getValue());
    }

    private static void scan(File root, File dir, Map<String, String> out, int depth, int[] visited) {
        if (depth > 4 || out.size() >= MAX_FILES || visited[0] > MAX_VISITED) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        String base = root.getAbsolutePath() + "/";
        for (File f : kids) {
            if (out.size() >= MAX_FILES || ++visited[0] > MAX_VISITED) return;
            String rel = f.getAbsolutePath().startsWith(base)
                    ? f.getAbsolutePath().substring(base.length()) : f.getName();
            if (rel.startsWith("cache") || rel.startsWith("code_cache") || rel.startsWith("lib")) continue;
            if (f.isDirectory()) {
                scan(root, f, out, depth + 1, visited);
            } else if (WatchState.trackedFile(rel) && !rel.endsWith(STATE)) {
                out.put(rel, f.length() + "@" + new SimpleDateFormat("MMdd HH:mm", Locale.US)
                        .format(new Date(f.lastModified())));
            }
        }
    }

    private static File stateFile() {
        return new File(context.getNoBackupFilesDir(), STATE);
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        File f = stateFile();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            state.load(in);
        } catch (Throwable ignored) {
        }
        String today = "c:" + day() + ":";
        for (String k : new HashSet<>(state.stringPropertyNames())) {
            if (k.startsWith("c:") && !k.startsWith(today)) state.remove(k);
        }
    }

    private static void save() {
        try (FileOutputStream out = new FileOutputStream(stateFile())) {
            state.store(out, null);
        } catch (Throwable ignored) {
        }
    }

    private static void write(String line) {
        String text = stamp() + " " + line.replace('\n', ' ') + "\n";
        if (!DiagLog.appendDownload(context, FILE, text.getBytes(StandardCharsets.UTF_8))) {
            ModuleRuntime.frameworkLog("[watch] write failed: " + line);
        }
    }

    private static String day() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
