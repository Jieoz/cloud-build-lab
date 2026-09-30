package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TimelineProbeTest {

    @Test
    public void countryAndOperatorReadsAreTimelineEvidence() {
        assertTrue(TimelineProbe.relevant(
                "android.telephony.TelephonyManager", "getSimCountryIso"));
        assertTrue(TimelineProbe.relevant(
                "android.telephony.TelephonyManager", "getNetworkOperator"));
        assertTrue(TimelineProbe.relevant(
                "android.telephony.SubscriptionInfo", "getCountryIso"));
        assertTrue(TimelineProbe.relevant(
                "android.telephony.SubscriptionInfo", "getMccString"));
        assertTrue(TimelineProbe.relevant(
                "android.telephony.SubscriptionManager", "getSimOperator"));
        assertFalse(TimelineProbe.relevant(
                "android.telephony.SubscriptionInfo", "getIccId"));
    }

    @Test
    public void hotPathAndUnrelatedReadsStaySilent() {
        assertFalse(TimelineProbe.relevant(
                "android.location.Location", "getLatitude"));
        assertFalse(TimelineProbe.relevant(
                "android.telephony.TelephonyManager", "getLine1Number"));
        assertFalse(TimelineProbe.relevant(
                "android.os.SystemProperties", "getInt"));
        assertFalse(TimelineProbe.relevant(null, null));
        assertFalse(TimelineProbe.relevant("android.telephony.TelephonyManager", ""));
    }

    @Test
    public void systemPropertyGetIsRelevantBecauseTheHookAlreadyFiltersKeys() {
        assertTrue(TimelineProbe.relevant("android.os.SystemProperties", "get"));
    }

    @Test
    public void lineIsBoundedAndNamesTheValueTheHostSaw() {
        String row = TimelineProbe.line(
                "android.telephony.TelephonyManager", "getSimCountryIso", "cn");
        assertEquals("timeline probe TelephonyManager.getSimCountryIso -> cn", row);

        String huge = TimelineProbe.line("Owner", "get", "x".repeat(80) + "\nsecret");
        assertTrue(huge.startsWith("timeline probe Owner.get -> "));
        assertFalse(huge.contains("\n"));
        assertTrue(huge.length() < 90);
    }

    @Test
    public void callKeepsReturnAndPrimitiveArgsOnly() {
        String row = TimelineProbe.call(
                "com.google.android.apps.maps.Gate", "a", "Z",
                new Object[]{Boolean.TRUE, "account-token", Integer.valueOf(3)}, Boolean.FALSE);
        assertEquals("timeline call Gate.a ret=Z args=true,3 -> false", row);

        String noisy = TimelineProbe.call("Owner", "b", "Z", null, "x".repeat(80) + "\n");
        assertTrue(noisy.startsWith("timeline call Owner.b ret=Z -> "));
        assertFalse(noisy.contains("\n"));
        assertFalse(noisy.contains("args="));
    }
}
