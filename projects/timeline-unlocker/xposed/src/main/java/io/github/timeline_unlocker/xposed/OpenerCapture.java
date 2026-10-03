package io.github.timeline_unlocker.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Pure: pieces of the timeline opener that run off device in unit tests.
 *
 * <p>The in-app entry the server hides is only a button; Maps opens the page itself through an
 * obfuscated veneer method (container-APK analysis: {@code akjz.l(Lakkx;)}, log tag
 * {@code MapsActivityVeneerImpl.startTimeline}). Names change between Maps builds, so the module
 * discovers them at runtime from the {@code TimelineWrapper} construction stack instead of
 * hardcoding them.</p>
 */
final class OpenerCapture {

    private static final String WRAPPER = "com.google.android.apps.gmm.mapsactivity.instant.TimelineWrapper";
    /** How many caller candidates to keep: lowest obfuscated frames above the wrapper ctor. */
    static final int MAX_CANDIDATES = 3;
    private static final int MAX_DUMP_FIELDS = 12;
    private static final int MAX_VALUE = 60;

    /** A candidate "open the timeline" caller: obfuscated class, method, no package. */
    static final class Frame {
        final String className;
        final String methodName;

        Frame(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        String key() {
            return className + "." + methodName;
        }
    }

    private OpenerCapture() {}

    /**
     * The {@code TimelineWrapper} ctor stack runs: ctor, then generated factory / graph plumbing,
     * then the veneer method, then the rest of the app. Plumbing and veneer are both obfuscated
     * default-package classes, so the lowest obfuscated frames are candidates and the timing check
     * (candidate fires, wrapper follows within seconds) picks the real one at the next open.
     */
    static Frame[] pickVeneerFrames(StackTraceElement[] stack) {
        if (stack == null) return new Frame[0];
        java.util.List<Frame> out = new java.util.ArrayList<>();
        for (StackTraceElement e : stack) {
            if (out.size() >= MAX_CANDIDATES) break;
            String cls = e.getClassName();
            if (cls == null || cls.isEmpty()) continue;
            if (cls.equals(WRAPPER)) continue;
            if (cls.indexOf('.') >= 0) continue; // obfuscated Maps roots live in the default package
            if (!isObfuscatedName(cls)) continue;
            String method = e.getMethodName();
            if (method == null || method.isEmpty()) continue;
            if (method.startsWith("access$")) continue;
            Frame candidate = new Frame(cls, method);
            boolean duplicate = false;
            for (Frame kept : out) if (kept.key().equals(candidate.key())) duplicate = true;
            if (!duplicate) out.add(candidate);
        }
        return out.toArray(new Frame[0]);
    }

    /** Single-candidate convenience for tests. */
    static Frame pickVeneerFrame(StackTraceElement[] stack) {
        Frame[] all = pickVeneerFrames(stack);
        return all.length == 0 ? null : all[0];
    }

    /** ProGuard short names: 2-5 chars, lowercase letters/digits, first char a letter. */
    static boolean isObfuscatedName(String name) {
        if (name == null || name.length() < 2 || name.length() > 5) return false;
        if (!Character.isLowerCase(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLowerCase(c) && (c < '0' || c > '9')) return false;
        }
        return true;
    }

    // ---- candidate list persistence (CSV of cls.method, most likely first) ---------------------

    /** Encode candidates for the state file; empty input gives "". */
    static String encodeCandidates(Frame[] frames) {
        if (frames == null || frames.length == 0) return "";
        StringBuilder out = new StringBuilder();
        for (Frame f : frames) {
            if (out.length() > 0) out.append(',');
            out.append(f.key());
        }
        return out.toString();
    }

    /** Inverse of {@link #encodeCandidates}; tolerant of blanks. */
    static String[] decodeCandidates(String csv) {
        if (csv == null || csv.isEmpty()) return new String[0];
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : csv.split(",")) {
            String key = part == null ? "" : part.trim();
            int dot = key.lastIndexOf('.');
            if (dot <= 0 || dot == key.length() - 1) continue;
            out.add(key);
        }
        return out.toArray(new String[0]);
    }

    /**
     * One-line snapshot of an object's instance fields: {@code field=Type:value;...}. Values are
     * summarized (strings truncated, objects by simple class name); the dump feeds the log, not a
     * rebuild — the replay reuses the captured live argument instead.
     */
    static String dumpFields(Object obj) {
        return dumpFields(obj, MAX_DUMP_FIELDS);
    }

    static String dumpFields(Object obj, int maxFields) {
        if (obj == null || maxFields <= 0) return "";
        StringBuilder out = new StringBuilder();
        Class<?> type = obj.getClass();
        int n = 0;
        while (type != null && type != Object.class && n < maxFields) {
            for (Field f : type.getDeclaredFields()) {
                if (n >= maxFields) break;
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.isSynthetic()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (out.length() > 0) out.append(';');
                    out.append(f.getName()).append('=')
                            .append(f.getType().getSimpleName()).append(':')
                            .append(summarize(v));
                    n++;
                } catch (Throwable ignored) {
                    // A field the replay cannot read it also cannot rebuild; skip silently.
                }
            }
            type = type.getSuperclass();
        }
        return out.toString();
    }

    private static String summarize(Object v) {
        if (v == null) return "null";
        if (v instanceof String) {
            String s = (String) v;
            return "\"" + (s.length() > MAX_VALUE ? s.substring(0, MAX_VALUE) + "…" : s) + "\"";
        }
        String name = v.getClass().getSimpleName();
        if (v instanceof Number || v instanceof Character || v instanceof Boolean) {
            return name + "(" + v + ")";
        }
        if (v.getClass().isArray()) return name + "[len=" + java.lang.reflect.Array.getLength(v) + "]";
        return name;
    }

    /** Count how many entries a dump carries (0 for empty/null). */
    static int dumpSize(String dump) {
        if (dump == null || dump.isEmpty()) return 0;
        return dump.split(";", -1).length;
    }
}
