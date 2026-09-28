package io.github.timeline_unlocker.xposed;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagLogPathTest {

    @Test
    public void missingFlagMeansOffAndFileNameIsStableShape() throws Exception {
        File root = Files.createTempDirectory("timeline-log").toFile();
        File flag = new File(new File(root, DiagLog.DIR_NAME), DiagLog.KEY_ENABLED);
        assertFalse(flag.exists());
        assertTrue(flag.getParentFile().mkdirs());
        Files.write(flag.toPath(), new byte[]{'0'});
        assertEquals('0', Files.readAllBytes(flag.toPath())[0]);
        Files.write(flag.toPath(), new byte[]{'1'});
        assertEquals('1', Files.readAllBytes(flag.toPath())[0]);
        String name = DiagLog.fileName("com.google.android.apps.maps");
        assertTrue(name.startsWith("timeline-com.google.android.apps.maps-"));
        assertTrue(name.endsWith(".txt"));
    }
}
