package io.github.timeline_unlocker.xposed;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure: decides whether on-screen texts show Timeline. Unit-tested off device. */
final class EntryScan {

    private static final String[] KEYS = {"时间轴", "時間軸", "timeline", "时间线", "你去过的地方"};
    private static final int MAX_HITS = 6;
    private static final int MAX_LEN = 40;

    private EntryScan() {}

    static final class Result {
        final List<String> hits;

        Result(List<String> hits) {
            this.hits = hits;
        }

        boolean found() {
            return !hits.isEmpty();
        }

        String line() {
            return found() ? "timeline=YES hits=" + hits : "timeline=no";
        }
    }

    static Result evaluate(List<String> texts) {
        Set<String> hits = new LinkedHashSet<>();
        for (String raw : texts) {
            if (raw == null) continue;
            String text = raw.replace('\n', ' ').trim();
            String lower = text.toLowerCase(Locale.ROOT);
            for (String key : KEYS) {
                if (lower.contains(key)) {
                    hits.add(text.length() > MAX_LEN ? text.substring(0, MAX_LEN) + "…" : text);
                    break;
                }
            }
            if (hits.size() >= MAX_HITS) break;
        }
        return new Result(new ArrayList<>(hits));
    }
}
