package io.github.timeline_unlocker.xposed;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagLogPathTest {

    @Test
    public void switchDefaultsOffAndGatesTheFile() throws Exception {
        File root = Files.createTempDirectory("timeline-log").toFile();
        FileLogPrefs prefs = new FileLogPrefs(new File(root, "prefs.txt"));

        assertFalse(prefs.isEnabled());
        prefs.setEnabled(true);
        assertTrue(prefs.isEnabled());
        prefs.setEnabled(false);
        assertFalse(prefs.isEnabled());

        File dir = new File(root, DiagLog.DIR_NAME);
        assertTrue(dir.mkdirs());
        File session = new File(dir, "session-com.google.android.apps.maps-test.txt");
        Files.write(session.toPath(),
                "12:00:00.000 loading package: com.google.android.apps.maps\n".getBytes(StandardCharsets.UTF_8));
        String merged = new String(Files.readAllBytes(session.toPath()), StandardCharsets.UTF_8);
        assertTrue(merged.contains("com.google.android.apps.maps"));
        assertEquals("TimelineUnlocker", DiagLog.DIR_NAME);
    }

    /** Plain stand-in for the world-readable preference. Default is off. */
    static final class FileLogPrefs {
        private final File file;

        FileLogPrefs(File file) {
            this.file = file;
        }

        boolean isEnabled() throws Exception {
            if (!file.exists()) return false;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return "1".equals(text);
        }

        void setEnabled(boolean value) throws Exception {
            Files.write(file.toPath(), (value ? "1" : "0").getBytes(StandardCharsets.UTF_8));
        }
    }
}
