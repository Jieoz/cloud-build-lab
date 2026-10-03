package io.github.timeline_unlocker.xposed;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure: decides whether on-screen texts show Timeline, and whether what they show is the home
 * screen's entry (two or more distinct Timeline affordances, e.g. 「基于您的时间轴」+「浏览时间轴」)
 * or a Timeline page's own title (a single short bare keyword like 「时间轴」). The daily entry
 * ledger must only follow the button group: the opener's deep link lands on a page whose title
 * matches the same keywords and would otherwise pollute the ledger. Unit-tested off device.
 */
final class EntryScan {

    private static final String[] KEYS = {"时间轴", "時間軸", "timeline", "时间线", "你去过的地方"};
    private static final String[] BARE_TITLES = {"时间轴", "時間軸", "timeline", "时间线"};
    private static final int MAX_HITS = 6;
    private static final int MAX_LEN = 40;

    private EntryScan() {}

    static final class Result {
        final List<String> hits;
        final boolean entryButton;

        Result(List<String> hits, boolean entryButton) {
            this.hits = hits;
            this.entryButton = entryButton;
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
        return new Result(new ArrayList<>(hits), isEntryButton(new ArrayList<>(hits)));
    }

    /**
     * The home entry always shows several distinct affordances (title + action labels); a lone
     * short bare keyword is a page title (deep-link landing header), not the entry.
     */
    static boolean isEntryButton(List<String> hits) {
        if (hits == null || hits.size() < 2) return false;
        int distinct = 0;
        for (String h : hits) {
            String t = h.replace("…", "").trim().toLowerCase(Locale.ROOT);
            boolean bare = false;
            for (String k : BARE_TITLES) {
                if (t.equals(k)) {
                    bare = true;
                    break;
                }
            }
            if (!bare) distinct++;
        }
        return distinct >= 2;
    }
}
