package io.github.timeline_unlocker.xposed;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagLogPathTest {

    @Test
    public void fileNameIsTheSystemDownloadShape() {
        String name = DiagLog.fileName("com.google.android.apps.maps");
        assertTrue(name.startsWith("timeline-com.google.android.apps.maps-"));
        assertTrue(name.endsWith(".txt"));
        assertFalse(name.contains("session-"));
        assertTrue("timeline_unlocker".equals(DiagLog.PREFS));
    }
}
