package io.github.timeline_unlocker.xposed;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Pure: open-request dedupe for the opener, backed by a small Properties file in Maps' own
 * no-backup dir. The UI writes a fresh timestamp into the remote preference; the Maps process
 * consumes the newest request once. Unit-tested off device.
 */
final class OpenerState {

    private final long[] handled;
    private int count;

    OpenerState(int keep) {
        this.handled = new long[Math.max(1, keep)];
    }

    /** True when this request timestamp was never handled before (and records it as handled). */
    synchronized boolean shouldHandle(long request) {
        if (request <= 0) return false;
        for (long seen : handled) {
            if (seen == request) return false;
        }
        handled[count % handled.length] = request;
        count++;
        return true;
    }

    /** How many requests this state has accepted. */
    synchronized int accepted() {
        return count;
    }

    /** True when the request is fresh enough for a just-starting process to still consume it. */
    static boolean isFresh(long now, long request, long maxAgeMs) {
        return request > 0 && now >= request && now - request <= maxAgeMs;
    }

    /** Load helper shared with tests: read a long property, 0 when absent or malformed. */
    static long readLong(Properties state, String key) {
        String raw = state == null ? null : state.getProperty(key);
        if (raw == null || raw.isEmpty()) return 0;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Load helper shared with tests. */
    static void writeLong(Properties state, String key, long value) {
        if (state != null) state.setProperty(key, String.valueOf(value));
    }

    /** Best-effort load of the watch state file; empty properties when anything fails. */
    static Properties loadQuietly(File file) {
        Properties out = new Properties();
        if (file != null && file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                out.load(in);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** Best-effort save; false only when the write itself failed. */
    static boolean saveQuietly(Properties state, File file) {
        if (state == null || file == null) return false;
        try (FileOutputStream out = new FileOutputStream(file)) {
            state.store(out, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
