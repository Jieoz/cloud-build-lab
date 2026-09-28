package io.github.timeline_unlocker.xposed;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class DiagLogPathTest {

    @Test
    public void sessionFilesRoundTripWithoutTheXposedClass() throws Exception {
        Path root = Files.createTempDirectory("timeline-log");
        Path maps = root.resolve("Android/data/com.google.android.apps.maps/files/TimelineUnlocker");
        Files.createDirectories(maps);
        Files.write(maps.resolve("session-test.txt"),
                "12:00:00.000 loading package: com.google.android.apps.maps\n".getBytes(StandardCharsets.UTF_8));

        byte[] raw = Files.readAllBytes(maps.resolve("session-test.txt"));
        String text = new String(raw, StandardCharsets.UTF_8);
        assertTrue(text.contains("com.google.android.apps.maps"));
        assertTrue(maps.toString().contains(DiagLog.DIR_NAME));
    }
}
