package io.github.timeline_unlocker.xposed;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * Diagnostic lines for the three hooked Google processes.
 *
 * Always mirrored to the LSPosed log. When a process Context is bound, the same
 * lines are also appended under that app's own external files directory, which
 * the module UI can read back because the three packages share this signature.
 * The queue is flushed after the feature hooks are installed, so a logging
 * failure cannot remove the telephony or coordinate hooks.
 */
public final class DiagLog {

    static final String DIR_NAME = "TimelineUnlocker";
    private static final String TAG = "TimelineUnlocker-X";
    private static final int MAX_QUEUED = 400;

    private static final Object LOCK = new Object();
    private static final List<String> pending = new ArrayList<>();
    private static volatile Context appContext;
    private static volatile Handler writer;
    private static volatile String activeFile;

    private DiagLog() {}

    public static void line(String message) {
        String text = stamp() + " " + (message == null ? "" : message.replace('\n', ' ').replace('\r', ' '));
        try {
            XposedBridge.log("[" + TAG + "] " + text);
        } catch (Throwable ignored) {
            // Unit tests and a missing framework must not drop the file copy.
        }
        synchronized (LOCK) {
            if (pending.size() >= MAX_QUEUED) pending.remove(0);
            pending.add(text);
        }
        flushAsync();
    }

    /** Bind the host process context. Call only after the feature hooks are in place. */
    public static void bind(Context context) {
        if (context == null) return;
        appContext = context;
        ensureWriter();
        line("log file: " + fileFor(context).getAbsolutePath());
        flushAsync();
    }

    public static File exportDir() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(downloads, DIR_NAME);
    }

    /**
     * Copies every session log this package can see into Download/TimelineUnlocker/.
     * Runs in the module app, which shares the user id of Maps, GMS, and GSF.
     */
    public static File exportToDownloads(Context context) throws Exception {
        File outDir = exportDir();
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("cannot create " + outDir.getAbsolutePath());
        }
        String name = "timeline-unlocker-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".txt";
        File out = new File(outDir, name);
        StringBuilder body = new StringBuilder();
        body.append("Timeline Unlocker export\n");
        body.append("module ").append(context.getPackageName()).append('\n');
        int files = 0;
        int missing = 0;
        for (String pkg : new String[]{
                "com.google.android.apps.maps",
                "com.google.android.gms",
                "com.google.android.gsf"
        }) {
            File dir = sessionDir(context, pkg);
            body.append("\n## ").append(pkg).append('\n');
            body.append("dir ").append(dir.getAbsolutePath()).append('\n');
            File[] logs = dir.listFiles((d, n) -> n.startsWith("session-") && n.endsWith(".txt"));
            if (logs == null || logs.length == 0) {
                body.append(dir.isDirectory() ? "(no session file)\n" : "(directory not readable)\n");
                missing++;
                continue;
            }
            java.util.Arrays.sort(logs, (a, b) -> a.getName().compareTo(b.getName()));
            for (File log : logs) {
                body.append("\n--- ").append(log.getName()).append(" (").append(log.length()).append(" bytes) ---\n");
                body.append(readTail(log, 256 * 1024));
                if (body.charAt(body.length() - 1) != '\n') body.append('\n');
                files++;
            }
        }
        if (files == 0) {
            body.append("\nNo host session was readable. Open Maps once after enabling the module, then export again.\n");
            body.append("Unreadable package dirs: ").append(missing).append('\n');
        }
        try (FileOutputStream fos = new FileOutputStream(out, false)) {
            fos.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void flushAsync() {
        Handler handler = writer;
        if (handler == null || appContext == null) return;
        handler.post(DiagLog::flushNow);
    }

    private static void flushNow() {
        Context context = appContext;
        if (context == null) return;
        List<String> batch;
        synchronized (LOCK) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        File file = fileFor(context);
        StringBuilder payload = new StringBuilder();
        for (String row : batch) payload.append(row).append('\n');
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            activeFile = file.getAbsolutePath();
        } catch (Throwable t) {
            synchronized (LOCK) {
                pending.add(0, stamp() + " file write failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            try {
                XposedBridge.log("[" + TAG + "] file write failed: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void ensureWriter() {
        if (writer != null) return;
        synchronized (LOCK) {
            if (writer != null) return;
            HandlerThread thread = new HandlerThread("timeline-unlocker-log");
            thread.start();
            writer = new Handler(thread.getLooper());
        }
    }

    private static File fileFor(Context context) {
        String known = activeFile;
        if (known != null) return new File(known);
        File dir = new File(context.getExternalFilesDir(null), DIR_NAME);
        String name = "session-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".txt";
        return new File(dir, name);
    }

    private static File sessionDir(Context context, String packageName) {
        File mine = context.getExternalFilesDir(null);
        if (mine == null) return new File("/unreadable/" + packageName);
        String path = mine.getAbsolutePath().replace(context.getPackageName(), packageName);
        return new File(path, DIR_NAME);
    }

    private static String readTail(File file, int maxBytes) throws Exception {
        long length = file.length();
        int take = (int) Math.min(length, maxBytes);
        byte[] buf = new byte[take];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            if (length > take) in.skip(length - take);
            int read = 0;
            while (read < take) {
                int n = in.read(buf, read, take - read);
                if (n < 0) break;
                read += n;
            }
        }
        String text = new String(buf, StandardCharsets.UTF_8);
        if (length > take) return "[truncated to last " + take + " bytes]\n" + text;
        return text;
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
