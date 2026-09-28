package io.github.timeline_unlocker.xposed;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagLogPathTest {

    @Test
    public void fileNameIsTheSystemDownloadShape() {
        String name = DiagLog.fileName("com.google.android.apps.maps");
        assertTrue(name.startsWith("timeline-com.google.android.apps.maps-"));
        assertTrue(name.endsWith(".txt"));
        assertFalse(name.contains("session-"));
    }

    @Test
    public void fileNameSanitizesPackage() {
        String name = DiagLog.fileName("weird/../pkg name");
        assertFalse(name.contains("/"));
        assertFalse(name.contains(" "));
        assertTrue(name.startsWith("timeline-"));
    }

    @Test
    public void offByDefaultAndLineDoesNotThrowWhenOff() {
        // Default state is off; line() must be a no-op without a bound context and must not throw.
        assertFalse(DiagLog.isEnabled());
        DiagLog.line("must not be stored while off");
        assertFalse(DiagLog.isEnabled());
    }
}
