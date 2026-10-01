package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RestartCommandTest {

    @Test
    public void forceStopsExactlyTheThreeScopedPackages() {
        assertEquals("am force-stop com.google.android.apps.maps; "
                        + "am force-stop com.google.android.gms; "
                        + "am force-stop com.google.android.gsf",
                LogExportActivity.restartCommand());
    }
}
