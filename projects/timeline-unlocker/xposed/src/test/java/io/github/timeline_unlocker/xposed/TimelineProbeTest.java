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
}
