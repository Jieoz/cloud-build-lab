package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ResumeVerdictTest {

    @Test
    public void lostEntryRecordsNoWithoutTheTwentySecondScan() {
        // log101 19:03: two home-screen scans without the entry, then Maps left (no t=20s scan).
        ResumeVerdict v = new ResumeVerdict();
        assertNull(v.scan(false, "[]", 251));
        assertNull(v.scan(false, "[]", 232));
        assertTrue(v.end());
    }

    @Test
    public void entrySeenOnceRecordsYesOnce() {
        ResumeVerdict v = new ResumeVerdict();
        assertEquals("[时间轴]", v.scan(true, "[时间轴]", 311));
        assertNull(v.scan(true, "[时间轴]", 226));
        assertNull(v.scan(false, "[]", 226));
        assertFalse(v.end());
    }

    @Test
    public void backgroundScansSayNothing() {
        ResumeVerdict v = new ResumeVerdict();
        assertNull(v.scan(false, "[]", 1));
        assertFalse(v.end());
        assertNull(v.scan(true, "[时间轴]", 1));
        assertFalse(v.end());
    }

    @Test
    public void endClosesTheResume() {
        ResumeVerdict v = new ResumeVerdict();
        v.scan(false, "[]", 250);
        assertTrue(v.end());
        assertFalse(v.end());
    }
}
