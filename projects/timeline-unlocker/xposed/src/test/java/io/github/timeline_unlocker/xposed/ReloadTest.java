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
    public void mapsProcessesFollowTheMapsButton() {
        assertEquals(DiagLog.KEY_RELOAD_MAPS, DiagLog.reloadKeyFor("com.google.android.apps.maps"));
        assertEquals(DiagLog.KEY_RELOAD_MAPS,
                DiagLog.reloadKeyFor("com.google.android.apps.maps:LocationFriendService"));
    }

    @Test
    public void playServicesProcessesFollowTheGmsButton() {
        assertEquals(DiagLog.KEY_RELOAD_GMS, DiagLog.reloadKeyFor("com.google.android.gms"));
        assertEquals(DiagLog.KEY_RELOAD_GMS, DiagLog.reloadKeyFor("com.google.android.gms.persistent"));
        assertEquals(DiagLog.KEY_RELOAD_GMS, DiagLog.reloadKeyFor("com.google.process.gservices"));
    }

    @Test
    public void reloadKeysAreDistinctFromEachOtherAndTheLogSwitch() {
        assertNotEquals(DiagLog.KEY_RELOAD_MAPS, DiagLog.KEY_RELOAD_GMS);
        assertNotEquals(DiagLog.KEY_ON, DiagLog.KEY_RELOAD_MAPS);
        assertNotEquals(DiagLog.KEY_ON, DiagLog.KEY_RELOAD_GMS);
    }
}
