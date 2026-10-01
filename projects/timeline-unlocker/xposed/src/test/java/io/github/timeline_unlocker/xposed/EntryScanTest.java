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
}
