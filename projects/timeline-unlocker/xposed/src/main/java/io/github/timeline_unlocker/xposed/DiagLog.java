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

import de.robv.android.xposed.XposedBridge;

/**
 * Off until the module switch is on. While off, a line is one flag read and a return.
 * While on, the hooked process inserts a text/plain row into the system Downloads collection.
 * The module UI does not copy that file. It opens the system Downloads list.
 */
public final class DiagLog {

    static final String DIR_NAME = "TimelineUnlocker";
    static final String FLAG_NAME = "log-on.txt";
    private static final String TAG = "TimelineUnlocker-X";
    private static final String MIME = "text/plain";
    private static final int MAX_QUEUED = 400;

    private static final Object LOCK = new Object();
    private static final List<String> pending = new ArrayList<>();
    private static final String sessionSuffix = UUID.randomUUID().toString().substring(0, 6);
    private static volatile boolean enabled;
    private static volatile Context appContext;
    private static volatile Handler writer;
    private static volatile Uri rowUri;

    private DiagLog() {}

    public static boolean isEnabled(Context context) {
        return enabled;
    }

    public static void applySwitch(boolean value) {
        enabled = value;
        Context context = appContext;
        if (context == null) return;
        line(value ? "switch on" : "switch off");
        if (!value) {
            synchronized (LOCK) {
                pending.clear();
            }
        }
        flushAsync();
    }

    public static void setEnabled(Context context, boolean value) {
        if (Build.VERSION.SDK_INT >= 29) {
            setEnabledMediaStore(context, value);
            return;
        }
        File dir = flagDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir.getAbsolutePath());
        }
        File flag = new File(dir, FLAG_NAME);
        if (value) {
            try (FileOutputStream out = new FileOutputStream(flag)) {
                out.write(new byte[]{'1'});
            } catch (Throwable t) {
                throw new IllegalStateException(t.getMessage());
            }
        } else if (flag.exists() && !flag.delete()) {
            throw new IllegalStateException("cannot remove " + flag.getAbsolutePath());
        }
    }

    private static void setEnabledMediaStore(Context context, boolean value) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String relative = Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/";
        Uri existing = findRow(resolver, collection, relative, FLAG_NAME);
        if (!value) {
            if (existing != null) resolver.delete(existing, null, null);
            return;
        }
        Uri uri = existing;
        if (uri == null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, FLAG_NAME);
            values.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = resolver.insert(collection, values);
        }
        if (uri == null) throw new IllegalStateException("cannot insert log-on.txt");
        try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("cannot open log-on.txt");
            out.write(new byte[]{'1'});
        } catch (Throwable t) {
            throw new IllegalStateException(t.getMessage());
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
    }

    public static void bind(Context context) {
        if (context == null) return;
        appContext = context;
        ensureWriter();
        if (!enabled) return;
        line("log file: " + displayPath());
        line("switch " + describeSwitch(context));
        flushAsync();
    }

    static String describeSwitch(Context context) {
        if (context == null) return "no context";
        if (Build.VERSION.SDK_INT < 29) {
            return new File(flagDir(), FLAG_NAME).exists() ? "file present" : "file absent";
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String relative = Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/";
            android.database.Cursor cursor = resolver.query(
                    collection,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH},
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "%"},
                    null);
            int rows = 0;
            StringBuilder names = new StringBuilder();
            if (cursor != null) {
                try {
                    while (cursor.moveToNext() && rows < 8) {
                        rows++;
                        names.append(' ').append(cursor.getString(0));
                    }
                } finally {
                    cursor.close();
                }
            }
            boolean hit = findRow(resolver, collection, relative, FLAG_NAME) != null;
            return "sdk " + Build.VERSION.SDK_INT
                    + " query " + relative + FLAG_NAME
                    + " hit=" + hit
                    + " rows=" + rows
                    + names;
        } catch (Throwable t) {
            return "query failed " + t.getClass().getName() + " " + t.getMessage();
        }
    }

    static boolean readHost(Context context) {
        if (context != null && Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver resolver = context.getContentResolver();
                Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                String relative = Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/";
                return findRow(resolver, collection, relative, FLAG_NAME) != null;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return new File(flagDir(), FLAG_NAME).exists();
    }

    private static File flagDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
    }

    private static void recordClosed(Context context, String reason) {
        if (context == null) return;
        try {
            String text = stamp() + " " + context.getPackageName() + " " + reason;
            appendMediaStore(context, (text + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    public static String displayPath() {
        String pkg = appContext == null ? "module" : appContext.getPackageName();
        return "Download/" + DIR_NAME + "/" + fileName(pkg);
    }

    public static void line(String message) {
        String text = stamp() + " " + (message == null ? "" : message.replace('\n', ' ').replace('\r', ' '));
        try {
            XposedBridge.log("[" + TAG + "] " + text);
        } catch (Throwable ignored) {
        }
        if (!enabled) return;
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
            try {
                XposedBridge.log("[" + TAG + "] file write failed -> " + displayPath());
            } catch (Throwable ignored) {
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
            try (FileOutputStream fos = new FileOutputStream(new File(dir, fileName(context.getPackageName())), true)) {
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
