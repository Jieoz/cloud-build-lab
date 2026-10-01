package io.github.timeline_unlocker.xposed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure helpers for {@link EntryWatch}: caller signatures and config-file diffs. Unit-tested. */
final class WatchState {

    /** Frames that say nothing about who in Maps asked: runtime, framework, hook bridges, us. */
    private static final String[] SKIP = {
            "java.", "javax.", "kotlin.", "android.", "androidx.", "com.android.", "dalvik.",
            "libcore.", "sun.", "io.github.timeline_unlocker.", "io.github.libxposed.",
            "org.matrix.", "org.lsposed.", "de.robv.", "LSPHooker"};

    private static final int CALLER_FRAMES = 4;

    private WatchState() {}

    /** First few app frames, "cls.method" joined by " < ". Stable across launches of one build. */
    static String callerSignature(StackTraceElement[] stack) {
        if (stack == null) return "?";
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (StackTraceElement f : stack) {
            String cls = f.getClassName();
            if (skipped(cls)) continue;
            if (kept++ > 0) out.append(" < ");
            out.append(cls).append('.').append(f.getMethodName());
            if (kept >= CALLER_FRAMES) break;
        }
        return kept == 0 ? "?" : out.toString();
    }

    private static boolean skipped(String cls) {
        if (cls == null) return true;
        for (String p : SKIP) if (cls.startsWith(p)) return true;
        return false;
    }

    /** Only reads that carry the region: country, operator, MCC/MNC, carrier id. */
    static boolean regionRead(String member) {
        if (member == null) return false;
        String m = member.toLowerCase(Locale.US);
        if (m.contains("simstate")) return false;
        return m.contains("country") || m.contains("operator") || m.contains("mcc")
                || m.contains("mnc") || m.contains("carrier");
    }

    /** Config-like files in Maps' data dir worth tracking (flags, experiments, prefs). */
    static boolean trackedFile(String relativePath) {
        if (relativePath == null) return false;
        String p = relativePath.toLowerCase(Locale.US);
        if (p.startsWith("shared_prefs/")) return true;
        return p.contains("phenotype") || p.contains("flag") || p.contains("experiment")
                || p.contains("config") || p.contains("gservices") || p.contains("timeline");
    }

    /** Lines "new|changed|gone path value", capped; empty when nothing moved. */
    static List<String> diff(Map<String, String> before, Map<String, String> now, int cap) {
        List<String> out = new ArrayList<>();
        int more = 0;
        for (Map.Entry<String, String> e : now.entrySet()) {
            String old = before.get(e.getKey());
            String line = old == null ? "new " + e.getKey() + " " + e.getValue()
                    : old.equals(e.getValue()) ? null
                    : "changed " + e.getKey() + " " + old + " -> " + e.getValue();
            if (line == null) continue;
            if (out.size() < cap) out.add(line); else more++;
        }
        for (String key : before.keySet()) {
            if (now.containsKey(key)) continue;
            if (out.size() < cap) out.add("gone " + key); else more++;
        }
        if (more > 0) out.add("... " + more + " more");
        return out;
    }
}
