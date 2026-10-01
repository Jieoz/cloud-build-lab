package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TimelineLinkTest {

    @Test
    public void linksAreHttpsGoogleTimelinePages() {
        assertTrue(LogExportActivity.TIMELINE_LINKS.length >= 1);
        for (String link : LogExportActivity.TIMELINE_LINKS) {
            assertTrue(link, link.startsWith("https://"));
            assertTrue(link, link.contains("google.com/maps/timeline"));
        }
    }
}
