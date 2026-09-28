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
 * The file sink is off until the module UI turns it on. While off, a line is one
 * preference read and a return — it does not touch disk and does not keep a queue.
 * While on, each host process writes its own file under Download/TimelineUnlocker.
 * The module UI only merges those files; it never reads another app's private dir.
 */
public final class DiagLog {

    static final String DIR_NAME = "TimelineUnlocker";
    static final String KEY_ENABLED = "log_enabled";
    private static final String TAG = "TimelineUnlocker-X";
    private static final int MAX_QUEUED = 400;

    private static final Object LOCK = new Object();
    private static final List<String> pending = new ArrayList<>();
    private static volatile boolean enabled;
    private static volatile Context appContext;
    private static volatile Handler writer;
    private static volatile String activeFile;

    private DiagLog() {}

    public static boolean isEnabled(Context context) {
        return readFlag(flagFile(context));
    }

    public static void setEnabled(Context context, boolean value) {
        File file = flagFile(context);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(value ? new byte[]{'1'} : new byte[]{'0'});
        } catch (Exception e) {
            throw new IllegalStateException("cannot write " + file.getAbsolutePath(), e);
        }
    }

    public static void line(String message) {
        if (!enabled) return;
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

    /**
     * Bind the host process context after the feature hooks are in place.
     * The switch is read once here. Changing it needs a force-stop of Maps and
     * Play services, or a reboot, before this process sees the new value.
     */
    public static void bind(Context context) {
        if (context == null) return;
        appContext = context;
        enabled = isEnabled(context);
        if (!enabled) return;
        ensureWriter();
        line("log file: " + fileFor(context).getAbsolutePath());
        flushAsync();
    }

    public static File exportDir() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(downloads, DIR_NAME);
    }

    /**
     * Merges session files already written into Download/TimelineUnlocker.
     * Those files are created by Maps, GMS, and GSF. This method only reads the
     * public download folder; it does not open another app's private directory.
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
        File[] logs = outDir.listFiles((d, n) -> n.startsWith("session-") && n.endsWith(".txt"));
        StringBuilder body = new StringBuilder();
        body.append("Timeline Unlocker export\n");
        body.append("module ").append(context.getPackageName()).append('\n');
        body.append("switch ").append(isEnabled(context) ? "on" : "off").append('\n');
        body.append("dir ").append(outDir.getAbsolutePath()).append('\n');
        if (logs == null || logs.length == 0) {
            body.append("\nNo session file in Download/").append(DIR_NAME)
                    .append(". Turn the switch on, force-stop Maps and Play services, open Maps, then export again.\n");
        } else {
            java.util.Arrays.sort(logs, (a, b) -> a.getName().compareTo(b.getName()));
            for (File log : logs) {
                body.append("\n--- ").append(log.getName()).append(" (").append(log.length()).append(" bytes) ---\n");
                body.append(readTail(log, 256 * 1024));
                if (body.charAt(body.length() - 1) != '\n') body.append('\n');
            }
        }
        try (FileOutputStream fos = new FileOutputStream(out, false)) {
            fos.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private static boolean readFlag(File file) {
        if (file == null || !file.isFile()) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int value = in.read();
            return value == '1';
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Public flag. A private file of the module app is invisible to Maps, GMS,
     * and GSF because the signatures differ. A missing file means off.
     */
    private static File flagFile(Context context) {
        return new File(exportDir(), KEY_ENABLED);
    }

    private static void flushAsync() {
        Handler handler = writer;
        if (handler == null || appContext == null) return;
        handler.post(DiagLog::flushNow);
    }

    private static void flushNow() {
        Context context = appContext;
        if (context == null || !enabled) return;
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
        String name = "session-"
                + safe(context.getPackageName()) + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".txt";
        return new File(exportDir(), name);
    }

    private static String safe(String packageName) {
        return packageName.replaceAll("[^A-Za-z0-9._-]", "_");
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
