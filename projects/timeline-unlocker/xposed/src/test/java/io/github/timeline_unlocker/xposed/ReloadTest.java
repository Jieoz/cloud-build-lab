package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class ReloadTest {

    @Test
    public void staleLineNamesShortProcessAndVersion() {
        assertEquals("· gms.persistent（版本 116）",
                LogExportActivity.staleLine("com.google.android.gms.persistent", 116));
    }

    @Test
    public void reloadKeyIsSeparateFromLogSwitch() {
        assertNotEquals(DiagLog.KEY_ON, DiagLog.KEY_RELOAD);
    }
}
