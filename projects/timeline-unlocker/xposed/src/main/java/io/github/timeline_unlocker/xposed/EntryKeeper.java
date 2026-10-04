package io.github.timeline_unlocker.xposed;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The keeper. Two mechanisms, one goal — the Timeline without the clear-data dance:
 *
 * <p><b>Snapshot/restore (the experiment):</b> the only proven way back is clear-data + us, so
 * the authorization plausibly lives in Maps' local flag/phenotype files — written on that
 * first run, revoked later by the daily sync. When the entry is visible, the tracked files are
 * snapshotted to {@code Download/TimelineUnlocker/flags/} (survives clear-data, proven by the
 * log files themselves); every Maps process start restores them. If the entry then survives the
 * daily revoke the hypothesis is proven and the problem is dead; if not, the local route is
 * excluded with per-file evidence. shared_prefs is never restored (would clobber user
 * settings).</p>
 *
 * <p><b>Gone-reminder:</b> when a real home screen (views &ge; 100) shows no entry group under
 * cn, one notification per day offers the verified in-process deep link — no module UI, no
 * watching. A fresh manual open request suppresses it.</p>
 *
 * <p>Maps main process only; every step logs to the normal Maps log. The whole class is inert
 * until armed with the log bound, and the switch (remote prefs {@code keeper}) gates everything.</p>
 */
final class EntryKeeper {

    private static final String STATE = "timeline-unlocker-keeper.properties";
    private static final String KEY_OPEN_DAY = "open_day";
    private static final String KEY_SNAP_DAY = "snapshot_day";
    private static final String FLAG_DIR = "flags";
    private static final int MAX_FILES = 400;
    private static final int MAX_BYTES_PER_FILE = 4_000_000;

    private static volatile boolean armed;
    private static volatile Context context;
    private static final Properties state = new Properties();
    private static volatile boolean loaded;
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "timeline-unlocker-keeper");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean restored = new AtomicBoolean();
    /** Lines produced before the log was bound; drained at arm. */
    private static final List<String> early = new ArrayList<>();
    private static final Object EARLY = new Object();

    private EntryKeeper() {}

    /** Maps main process, called once the Application context exists. Idempotent. */
    static synchronized void arm(Context app) {
        if (armed || app == null) return;
        armed = true;
        context = app;
        io.execute(() -> {
            load();
            drainEarly();
            // onPackageReady ran before the switch was readable/on: retry the restore here.
            if (!restored.getAndSet(true)) restore(context);
        });
    }

    private static void drainEarly() {
        List<String> batch;
        synchronized (EARLY) {
            if (early.isEmpty()) return;
            batch = new ArrayList<>(early);
            early.clear();
        }
        for (String line : batch) DiagLog.line(line);
    }

    private static void say(String line) {
        if (DiagLog.isEnabled()) {
            DiagLog.line(line);
            return;
        }
        synchronized (EARLY) {
            early.add(line);
            if (early.size() > 50) early.remove(0);
        }
    }

    private static boolean switchOn() {
        return ModuleRuntime.switchOn(DiagLog.PREFS_NAME, DiagLog.KEY_KEEPER);
    }

    /**
     * Restore before Maps reads its flags. Called from {@code onPackageReady} when an Application
     * already exists, and once more from {@link #arm} otherwise — whichever comes first wins.
     * Synchronous on purpose (the caller is already off the main thread there); the file set is
     * small flag/phenotype blobs.
     */
    static void restoreEarly(Context app) {
        if (app == null || !restored.compareAndSet(false, true)) return;
        if (!switchOn()) {
            say("keeper off: no restore");
            restored.set(false); // a later arm() with the switch on can still do it
            return;
        }
        restore(app);
    }

    private static void restore(Context app) {
        try {
            String[] manifest = readRow(app, KeeperCodec.MANIFEST_NAME);
            if (manifest == null) {
                say("keeper restore: no manifest yet");
                return;
            }
            StringBuilder rel = new StringBuilder();
            int ok = 0;
            int skipped = 0;
            int failed = 0;
            for (String line : manifest) {
                long[] size = KeeperCodec.parseManifest(line, rel);
                if (size == null) continue;
                String path = rel.toString();
                if (!KeeperPolicy.mayRestore(path)) {
                    skipped++;
                    continue;
                }
                String[] bytes = readRow(app, KeeperCodec.encodeName(path));
                if (bytes == null) {
                    failed++;
                    continue;
                }
                if (writePrivate(app, path, bytes[0].getBytes(StandardCharsets.ISO_8859_1))) ok++;
                else failed++;
            }
            say("keeper restored files=" + ok + " skipped_prefs=" + skipped + " failed=" + failed);
        } catch (Throwable t) {
            say("keeper restore failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * One entry-scan result. The settled t=20s scan of a real screen decides, and only while
     * its resume is still current — a scan scheduled by a resume that was since replaced must
     * not judge (10-04 06:42: the 20s scan of the home resume fired after the deep link had
     * already taken over the activity). Entry seen → maybe snapshot; entry gone under cn →
     * maybe notify. A deep-link landing on the real Timeline page is filtered upstream
     * (EntryWatch); a link landing back on the home counts as a home (10-04 06:42:05).
     */
    static void onScan(Context act, boolean entryButton, boolean realScreen, int scanSeconds,
                       int resume, String identity) {
        if (!armed || !DiagLog.isEnabled() || !switchOn() || !realScreen
                || !KeeperPolicy.isSettled(scanSeconds)
                || !EntryWatch.resumeIsCurrent(resume)) return;
        Context app = context != null ? context : act.getApplicationContext();
        if (app == null) return;
        final boolean seen = entryButton;
        io.execute(() -> {
            if (!EntryWatch.resumeIsCurrent(resume)) return; // replaced while queued
            load();
            long now = System.currentTimeMillis();
            String today = KeeperPolicy.dayStamp(now);
            if (seen) {
                if (!KeeperPolicy.shouldSnapshot(prop(KEY_SNAP_DAY), now)) return; // once/day
                int files = snapshot(app);
                if (files > 0) {
                    state.setProperty(KEY_SNAP_DAY, today);
                    save();
                }
                return;
            }
            if (!"cn".equals(identity)) return;
            long manual = ModuleRuntime.readLong(DiagLog.PREFS_NAME, DiagLog.KEY_OPEN_REQUEST);
            KeeperPolicy.Decision d = KeeperPolicy.autoOpen(false, true, identity, now,
                    prop(KEY_OPEN_DAY), manual);
            if (!d.fire) {
                say("keeper notify suppressed: " + d.reason);
                return;
            }
            if (notify(app)) {
                state.setProperty(KEY_OPEN_DAY, today);
                save();
            }
        });
    }

    // ---- snapshot: private files -> Download/TimelineUnlocker/flags/ ------------------------

    private static int snapshot(Context app) {
        java.util.TreeMap<String, String> files = new java.util.TreeMap<>();
        EntryWatch.collectTrackedFiles(app.getDataDir(), files);
        if (files.isEmpty()) {
            say("keeper snapshot: nothing tracked");
            return 0;
        }
        int written = 0;
        StringBuilder manifest = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : files.entrySet()) {
            String rel = e.getKey();
            long size;
            try {
                size = Long.parseLong(e.getValue().substring(0, e.getValue().indexOf('@')));
            } catch (Throwable t) {
                continue;
            }
            if (size > MAX_BYTES_PER_FILE) continue;
            byte[] data = readPrivate(app, rel);
            if (data == null) continue;
            if (writeRow(app, KeeperCodec.encodeName(rel), data)) {
                manifest.append(KeeperCodec.manifestLine(rel, data.length)).append('\n');
                written++;
                if (written >= MAX_FILES) break;
            }
        }
        boolean manifestOk = writeRow(app, KeeperCodec.MANIFEST_NAME,
                manifest.toString().getBytes(StandardCharsets.UTF_8));
        say("keeper snapshot files=" + written + " manifest=" + manifestOk);
        return manifestOk ? written : 0;
    }

    private static byte[] readPrivate(Context app, String rel) {
        File f = new File(app.getDataDir(), rel);
        if (!f.isFile() || f.length() > MAX_BYTES_PER_FILE) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] out = new byte[(int) f.length()];
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) break;
                off += n;
            }
            return off == out.length ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean writePrivate(Context app, String rel, byte[] data) {
        File f = new File(app.getDataDir(), rel);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(data);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- MediaStore rows (own app's rows need no permission; proven by DiagLog) -------------

    private static String flagsRelativePath() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + DiagLog.DIR_NAME + "/" + FLAG_DIR + "/";
    }

    private static Uri findRow(Context app, String name) {
        try {
            android.content.ContentResolver resolver = app.getContentResolver();
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String relative = flagsRelativePath();
            android.database.Cursor cursor = resolver.query(collection,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{relative, name}, null);
            if (cursor == null) return null;
            try {
                if (!cursor.moveToFirst()) return null;
                return android.content.ContentUris.withAppendedId(collection, cursor.getLong(0));
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean writeRow(Context app, String name, byte[] data) {
        OutputStream out = null;
        try {
            android.content.ContentResolver resolver = app.getContentResolver();
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = findRow(app, name);
            if (uri == null) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, flagsRelativePath());
                uri = resolver.insert(collection, values);
            }
            if (uri == null) return false;
            out = resolver.openOutputStream(uri, "w");
            if (out == null) return false;
            out.write(data);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Whole-row read; null when the row is missing. One-row files only (snapshot sized). */
    private static String[] readRow(Context app, String name) {
        InputStream in = null;
        try {
            Uri uri = findRow(app, name);
            if (uri == null) return null;
            in = app.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String l : text.split("\n")) {
                if (!l.trim().isEmpty()) lines.add(l);
            }
            return lines.toArray(new String[0]);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---- gone-reminder ----------------------------------------------------------------------

    private static boolean notify(Context app) {
        try {
            NotificationManager nm =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            String channel = "timeline_keeper";
            nm.createNotificationChannel(new NotificationChannel(channel, "时间轴守护",
                    NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/timeline"))
                    .setPackage(app.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(app, 1907, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification n = new android.app.Notification.Builder(app, channel)
                    .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                    .setContentTitle("时间轴入口已消失")
                    .setContentText("点按直接打开时间轴页面")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(1907, n);
            say("keeper notify posted");
            return true;
        } catch (Throwable t) {
            say("keeper notify failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

    // ---- state ------------------------------------------------------------------------------

    private static String prop(String key) {
        return String.valueOf(state.getProperty(key, ""));
    }

    private static java.io.File stateFile() {
        Context app = context;
        return app == null ? null : new java.io.File(app.getNoBackupFilesDir(), STATE);
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        java.io.File f = stateFile();
        if (f == null || !f.exists()) return;
        Properties loadedState = OpenerState.loadQuietly(f);
        state.clear();
        state.putAll(loadedState);
    }

    private static void save() {
        try {
            java.io.File f = stateFile();
            if (f == null) return;
            Properties copy = new Properties();
            copy.putAll(state);
            OpenerState.saveQuietly(copy, f);
        } catch (Throwable ignored) {
        }
    }
}
