package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ActivityLineTest {

    @Test
    public void showsActionDataAndTarget() {
        assertEquals("action=VIEW data=https://www.google.com/maps/timeline target=x.Maps",
                ActivityLine.describe("android.intent.action.VIEW",
                        "https://www.google.com/maps/timeline", "x.Maps"));
    }

    @Test
    public void dropsQueryAndBoundsLength() {
        String row = ActivityLine.describe(null, "https://g.co/m?token=secret", null);
        assertEquals("action=- data=https://g.co/m?…", row);
        String huge = ActivityLine.describe("MAIN", "x".repeat(400), null);
        assertTrue(huge.length() < 160);
        assertFalse(ActivityLine.describe("a", "l1\nl2", null).contains("\n"));
    }
}
