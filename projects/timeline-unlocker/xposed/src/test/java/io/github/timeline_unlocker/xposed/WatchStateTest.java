package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public class WatchStateTest {

    private static StackTraceElement f(String cls, String m) {
        return new StackTraceElement(cls, m, null, -1);
    }

    @Test
    public void callerSkipsFrameworkHookAndModuleFrames() {
        StackTraceElement[] stack = {
                f("java.lang.Thread", "getStackTrace"),
                f("io.github.timeline_unlocker.xposed.MainHook", "lambda$x"),
                f("org.matrix.vector.impl.hooks.VectorChain", "proceed"),
                f("LSPHooker_", "callback"),
                f("android.telephony.TelephonyManager", "getSimCountryIso"),
                f("bxyz", "a"), f("cabc", "b"), f("dqq", "run"), f("efg", "c"), f("zzz", "d")};
        assertEquals("bxyz.a < cabc.b < dqq.run < efg.c", WatchState.callerSignature(stack));
    }

    @Test
    public void callerUnknownWhenOnlyFramework() {
        assertEquals("?", WatchState.callerSignature(new StackTraceElement[]{
                f("android.os.Handler", "dispatch")}));
        assertEquals("?", WatchState.callerSignature(null));
    }

    @Test
    public void regionReadsOnly() {
        assertTrue(WatchState.regionRead("TelephonyManager.getSimCountryIso"));
        assertTrue(WatchState.regionRead("TelephonyManager.getSimOperatorNumericForPhone(1)"));
        assertTrue(WatchState.regionRead("SystemProperties.get(ro.carrier)"));
        assertFalse(WatchState.regionRead("TelephonyManager.getSimState"));
        assertFalse(WatchState.regionRead("SystemProperties.get(persist.sys.overlayfonts)"));
        assertFalse(WatchState.regionRead(null));
    }

    @Test
    public void trackedFiles() {
        assertTrue(WatchState.trackedFile("shared_prefs/settings.xml"));
        assertTrue(WatchState.trackedFile("files/phenotype/shared/x.pb"));
        assertTrue(WatchState.trackedFile("databases/experiments.db"));
        assertFalse(WatchState.trackedFile("databases/tiles.db"));
        assertFalse(WatchState.trackedFile(null));
    }

    @Test
    public void diffReportsNewChangedGoneAndCaps() {
        Map<String, String> before = new TreeMap<>();
        before.put("a", "1@x");
        before.put("b", "2@x");
        before.put("c", "3@x");
        Map<String, String> now = new TreeMap<>();
        now.put("a", "1@x");
        now.put("b", "5@y");
        now.put("d", "4@y");
        List<String> d = WatchState.diff(before, now, 10);
        assertEquals(Arrays.asList("changed b 2@x -> 5@y", "new d 4@y", "gone c"), d);
        assertTrue(WatchState.diff(before, before, 10).isEmpty());
        List<String> capped = WatchState.diff(before, now, 1);
        assertEquals(Arrays.asList("changed b 2@x -> 5@y", "... 2 more"), capped);
    }
}
