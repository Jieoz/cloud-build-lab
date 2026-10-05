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
    /** Set once a snapshot lands; a data clear deletes it with everything else. */
    private static final String KEY_HAVE_SNAP = "have_snapshot";
    /** name -> MediaStore row, so restarts reuse the same row instead of spawning "(1)" copies. */
    private static final java.util.concurrent.ConcurrentHashMap<String, android.net.Uri> rowCache =
            new java.util.concurrent.ConcurrentHashMap<>();
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
            if (!restored.getAndSet(true)) {
                if (!restore(context)) scheduleRestoreRetries(context);
            }
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
     *
     * <p>The immediate attempt lands inside the process-start blind window (10-05 clear-data:
     * MediaStore exact queries return no rows for the first seconds — snapshot wrote 106 rows at
     * +19s fine, restores at +30ms/+55s after restart saw none, and the log row itself spawned a
     * "(1)" copy). So a failed attempt schedules quiet retries on the retry executor.</p>
     */
    static void restoreEarly(Context app) {
        if (app == null || !restored.compareAndSet(false, true)) return;
        if (!switchOn()) {
            say("keeper off: no restore");
            restored.set(false); // a later arm() with the switch on can still do it
            return;
        }
        if (!restore(app)) scheduleRestoreRetries(app);
    }

    private static final long[] RETRY_DELAYS_MS = {5_000, 20_000, 60_000};
    private static final ExecutorService retry = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "timeline-unlocker-keeper-retry");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean restoreDone = new AtomicBoolean();

    private static void scheduleRestoreRetries(Context app) {
        load();
        if (restoreDone.get()) return;
        String haveSnap = prop(KEY_HAVE_SNAP);
        String snapDay = prop(KEY_SNAP_DAY);
        // 4.23 wrote snapshot_day without have_snapshot; that snapshot is just as real. Without
        // this migration the 10-05 device concluded "never snapshotted", silently disabled the
        // whole retry ladder, and 106 snapshot rows sat unused all day (10-05 08:12/15:25/15:59).
        if (haveSnap.isEmpty() && !snapDay.isEmpty()) {
            state.setProperty(KEY_HAVE_SNAP, snapDay);
            save();
            haveSnap = snapDay;
            say("keeper have_snapshot backfilled from snapshot_day=" + snapDay);
        }
        if (haveSnap.isEmpty()) {
            restoreDone.set(true); // nothing upstream was ever snapshotted: no rows will appear
            say("keeper restore: no snapshot on record, ladder not armed");
            return;
        }
        final Context a = app;
        retry.execute(() -> {
            for (long delay : RETRY_DELAYS_MS) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (restoreDone.get()) return;
                say("keeper restore retry t+" + (delay / 1000) + "s");
                if (restore(a)) return;
            }
        });
    }

    /** @return true when this attempt is final (restored, or nothing retryable left). */
    private static boolean restore(Context app) {
        try {
            String[] manifest = readRow(app, KeeperCodec.MANIFEST_NAME);
            if (manifest == null) {
                say("keeper restore: no manifest yet");
                return false; // blind window may still hide the rows: retry
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
                // Raw bytes, not lines: ~80 no_backup_flags_b* rows are empty files whose
                // existence is the meaning, and phenotype pb rows are binary — both were
                // destroyed by the line-splitting read (bytes[0] AIOOB killed the whole
                // restore on 10-05 06:16/07:04 before a single file was written).
                byte[] data = readRowBytes(app, KeeperCodec.encodeName(path));
                if (data == null) {
                    failed++;
                    continue;
                }
                if (writePrivate(app, path, data)) ok++;
                else failed++;
            }
            say("keeper restored files=" + ok + " skipped_prefs=" + skipped + " failed=" + failed);
            // A pass where every payload read failed is a blind-window signature, not a done
            // deal — leave room for the retry ladder.
            if (ok == 0 && failed > 0 && skipped == 0) return false;
            restoreDone.set(true);
            return true;
        } catch (Throwable t) {
            say("keeper restore failed: " + t.getClass().getSimpleName());
            return true; // a crashing path must not spin on the ladder
        }
    }

    /**
     * One entry-scan result. The settled t=20s scan of a real screen decides, and only while
     * its resume is still current — a scan scheduled by a resume that was since replaced must
     * not judge (10-04 06:42: the 20s scan of the home resume fired after the deep link had
     * already taken over the activity). Entry seen → maybe snapshot; entry gone under cn →
     * maybe notify. Gone means no Timeline keyword at all on a settled scan: a keyword without
     * the button group is a partial render (10-05 07:04:22 hits=[基于您的时间轴] alone, full
     * card set 40s later) and decides nothing. A deep-link landing on the real Timeline page
     * is filtered upstream (EntryWatch); a link landing back on the home counts as a home.
     */
    static void onScan(Context act, boolean entryButton, boolean realScreen, int scanSeconds,
                       boolean timelineDialog, boolean anyHit, int resume, String identity) {
        if (!armed || !DiagLog.isEnabled() || !switchOn() || !realScreen
                || !EntryWatch.resumeIsCurrent(resume)) return;
        // The dialog itself is the authorization proof: snapshot on sight, any scan age.
        if (!timelineDialog && !KeeperPolicy.isSettled(scanSeconds)) return;
        Context app = context != null ? context : act.getApplicationContext();
        if (app == null) return;
        final boolean authorized = entryButton || timelineDialog;
        io.execute(() -> {
            if (!EntryWatch.resumeIsCurrent(resume)) return; // replaced while queued
            load();
            long now = System.currentTimeMillis();
            String today = KeeperPolicy.dayStamp(now);
            if (authorized) {
                if (!KeeperPolicy.shouldSnapshot(prop(KEY_SNAP_DAY), now)) {
                    say("keeper snapshot skipped: already snapshotted today");
                    return; // once/day
                }
                int files = snapshot(app);
                if (files > 0) {
                    state.setProperty(KEY_SNAP_DAY, today);
                    // Marks upstream data as snapshot-covered: after a Maps data clear this
                    // key dies with everything else, so a cleared device knows no rows exist
                    // and the restore ladder doesn't wait on rows that will never appear.
                    state.setProperty(KEY_HAVE_SNAP, today);
                    save();
                }
                return;
            }
            if (!"cn".equals(identity)) return;
            if (anyHit) {
                say("keeper notify suppressed: keyword still on screen");
                return;
            }
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
        Uri cached = rowCache.get(name);
        if (cached != null) return cached;
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
                Uri uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0));
                rowCache.put(name, uri);
                return uri;
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
            // Re-query before every insert: a no-row answer can be the process-start blind
            // window (10-05 clear-data: MediaStore rows are invisible to exact queries for the
            // first seconds), and trusting it spawns "(1)" duplicate rows the export then
            // delivers twice.
            Uri uri = findRow(app, name);
            if (uri == null) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, flagsRelativePath());
                uri = resolver.insert(collection, values);
                if (uri != null) rowCache.put(name, uri);
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
        byte[] raw = readRowBytes(app, name);
        if (raw == null) return null;
        List<String> lines = new ArrayList<>();
        for (String l : new String(raw, StandardCharsets.UTF_8).split("\n")) {
            if (!l.trim().isEmpty()) lines.add(l);
        }
        return lines.toArray(new String[0]);
    }

    /** Full raw payload of a Download row; null when missing/unreadable. */
    private static byte[] readRowBytes(Context app, String name) {
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
            return buf.toByteArray();
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
