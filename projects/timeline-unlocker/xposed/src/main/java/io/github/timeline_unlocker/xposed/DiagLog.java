package io.github.timeline_unlocker.xposed;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Diagnostic log, off by default.
 *
 * <p>The switch value is read once — outside this class, from libxposed remote preferences
 * ({@link ModuleRuntime#switchOn}) — when the Maps process starts, and handed to {@link #bind}.
 * There is no polling, no listener, no broadcast receiver, and no host-file read here.</p>
 *
 * <p><b>While OFF</b> (the default): {@link #line} does a single volatile read and returns. The
 * writer thread is never started, MediaStore is never queried, nothing is queued, nothing touches
 * disk. The log adds no wake-ups and no battery cost.</p>
 *
 * <p><b>While ON</b>: the hooked process appends UTF-8 lines to a single {@code text/plain} row in
 * the system Downloads collection
 * ({@code Download/TimelineUnlocker/timeline-<pkg>-yyyyMMdd-<6 hex>.txt}) on one dedicated
 * background thread; the row Uri is cached so each flush is a single append.</p>
 */
public final class DiagLog {

    static final String DIR_NAME = "TimelineUnlocker";

    /** Module SharedPreferences file that carries the switch (see {@link LogExportActivity}). */
    public static final String PREFS_NAME = "switch";
    /** Boolean key inside {@link #PREFS_NAME}; absent/false means the log is off. */
    public static final String KEY_ON = "on";

    private static final String MIME = "text/plain";
    private static final int MAX_QUEUED = 400;

    private static final Object LOCK = new Object();
    private static final List<String> pending = new ArrayList<>();
    private static final String sessionSuffix = UUID.randomUUID().toString().substring(0, 6);

    private static volatile boolean enabled;   // default false: off until bind(context, true)
    private static volatile Context appContext;
    private static volatile Handler writer;    // created only when ON
    private static volatile Uri rowUri;

    private DiagLog() {}

    /** For tests / UI display only. Reflects the last {@link #bind} value. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Bind the hooked process with the switch value the caller already read once. When {@code on}
     * is false this returns after two field writes: no thread, no MediaStore query, no disk I/O.
     */
    public static void bind(Context context, boolean on) {
        appContext = context;
        enabled = on;
        if (!on) return;
        ensureWriter();
        line("log file: " + displayPath());
        flushAsync();
    }

    public static String displayPath() {
        String pkg = appContext == null ? "module" : appContext.getPackageName();
        return "Download/" + DIR_NAME + "/" + fileName(pkg);
    }

    public static void line(String message) {
        if (!enabled) return;
        String text = stamp() + " "
                + (message == null ? "" : message.replace('\n', ' ').replace('\r', ' '));
        synchronized (LOCK) {
            if (pending.size() >= MAX_QUEUED) pending.remove(0);
            pending.add(text);
        }
        flushAsync();
    }

    static String fileName(String packageName) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return "timeline-" + safe(packageName) + "-" + day + "-" + sessionSuffix + ".txt";
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
        StringBuilder payload = new StringBuilder();
        for (String row : batch) payload.append(row).append('\n');
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        boolean ok = Build.VERSION.SDK_INT >= 29
                ? appendMediaStore(context, bytes)
                : appendFile(context, bytes);
        if (!ok) {
            synchronized (LOCK) {
                pending.add(0, stamp() + " file write failed");
            }
        }
    }

    private static Uri findRow(ContentResolver resolver, Uri collection, String relative, String name) {
        android.database.Cursor cursor = resolver.query(
                collection,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{relative, name},
                null);
        if (cursor == null) return null;
        try {
            if (!cursor.moveToFirst()) return null;
            return ContentUris.withAppendedId(collection, cursor.getLong(0));
        } finally {
            cursor.close();
        }
    }

    private static boolean appendMediaStore(Context context, byte[] bytes) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String name = fileName(context.getPackageName());
            String relative = Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/";
            Uri uri = rowUri != null ? rowUri : findRow(resolver, collection, relative, name);
            if (uri == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
                uri = resolver.insert(collection, values);
            }
            if (uri == null) return false;
            rowUri = uri;
            OutputStream out = resolver.openOutputStream(uri, "wa");
            if (out == null) return false;
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean appendFile(Context context, byte[] bytes) {
        try {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) return false;
            try (FileOutputStream fos = new FileOutputStream(
                    new File(dir, fileName(context.getPackageName())), true)) {
                fos.write(bytes);
            }
            return true;
        } catch (Throwable t) {
            return false;
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

    private static String safe(String packageName) {
        return packageName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
