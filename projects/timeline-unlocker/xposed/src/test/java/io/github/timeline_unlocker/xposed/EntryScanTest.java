package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class EntryScanTest {

    @Test
    public void findsChineseAndEnglishEntry() {
        EntryScan.Result r = EntryScan.evaluate(Arrays.asList("搜索", "你的时间轴", "Timeline"));
        assertTrue(r.found());
        assertEquals(Arrays.asList("你的时间轴", "Timeline"), r.hits);
    }

    @Test
    public void plainMapScreenIsNo() {
        EntryScan.Result r = EntryScan.evaluate(Arrays.asList("搜索", "探索", "导航", null));
        assertFalse(r.found());
        assertEquals("timeline=no", r.line());
    }

    @Test
    public void dedupesAndClipsLongText() {
        String longText = "时间轴" + "很长".repeat(40);
        EntryScan.Result r = EntryScan.evaluate(Arrays.asList(longText, longText));
        assertEquals(1, r.hits.size());
        assertTrue(r.hits.get(0).endsWith("…"));
    }

    @Test
    public void loneBareTitleIsPageNotEntry() {
        // Observed 10-03 09:25: the deep link lands on a page whose only Timeline text is the
        // bare title 「时间轴」. That is a page, not the home entry.
        EntryScan.Result r = EntryScan.evaluate(Arrays.asList("搜索", "时间轴", "导航"));
        assertTrue(r.found());
        assertFalse(r.entryButton);
    }

    @Test
    public void entryButtonNeedsTwoDistinctAffordances() {
        EntryScan.Result yes = EntryScan.evaluate(
                Arrays.asList("基于您的时间轴", "浏览时间轴"));
        assertTrue(yes.found());
        assertTrue(yes.entryButton);
    }

    @Test
    public void summaryShowsFirstShortTextsAndCaps() {
        String s = EntryScan.summary(Arrays.asList("搜索", "在这里输入或搜索地点", null, "  ",
                "这条特别长超出二十四字符上限的文本不会被采纳进入摘要里", "你的时间轴"));
        assertTrue(s.contains("搜索"));
        assertTrue(s.contains("你的时间轴"));
        assertFalse(s.contains("这条特别长"));
        assertTrue(s.startsWith("["));
        assertEquals("-", EntryScan.summary(new java.util.ArrayList<>()));
        assertEquals("-", EntryScan.summary(Arrays.asList("  ", null)));
    }
}
