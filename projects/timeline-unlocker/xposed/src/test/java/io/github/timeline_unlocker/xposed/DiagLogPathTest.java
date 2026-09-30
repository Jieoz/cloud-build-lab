package io.github.timeline_unlocker.xposed;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagLogPathTest {

    @Test
    public void fileNameIsOneSharedDocument() {
        String name = DiagLog.fileName();
        assertTrue(name.startsWith("timeline-"));
        assertTrue(name.endsWith(".txt"));
        assertFalse(name.contains("maps"));
        assertFalse(name.contains("session"));
        assertTrue(name.matches("timeline-\\d{8}\\.txt"));
    }

    @Test
    public void fileNameHasNoPackage() {
        String name = DiagLog.fileName();
        assertFalse(name.contains("/"));
        assertFalse(name.contains(" "));
    }

    @Test
    public void offByDefaultAndLineDoesNotThrowWhenOff() {
        // Default state is off; line() must be a no-op without a bound context and must not throw.
        assertFalse(DiagLog.isEnabled());
        DiagLog.line("must not be stored while off");
        assertFalse(DiagLog.isEnabled());
    }
}
