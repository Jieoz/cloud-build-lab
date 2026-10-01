package io.github.timeline_unlocker.xposed;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagLogPathTest {

    @Test
    public void oneFilePerProcess() {
        String maps = DiagLog.fileName("com.google.android.apps.maps");
        assertTrue(maps.matches("timeline-\\d{8}-maps\\.txt"));
        assertTrue(DiagLog.fileName("com.google.android.gms").endsWith("-gms.txt"));
        assertTrue(DiagLog.fileName("com.google.android.gms.persistent").endsWith("-gms.persistent.txt"));
        assertTrue(DiagLog.fileName("com.google.android.gms:car").endsWith("-gms.car.txt"));
        assertTrue(DiagLog.fileName("com.google.process.gapps").endsWith("-gapps.txt"));
        assertTrue(DiagLog.fileName(null).endsWith("-module.txt"));
    }

    @Test
    public void fileNameIsSafe() {
        String name = DiagLog.fileName("weird/pro cess");
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
