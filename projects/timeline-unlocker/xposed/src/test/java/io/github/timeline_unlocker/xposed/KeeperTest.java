package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Properties;

/** KeeperPolicy + KeeperCodec, off device. */
public class KeeperTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final String TODAY = KeeperPolicy.dayStamp(NOW);

    @Test
    public void autoOpenFiresOnlyForGoneEntryOnRealScreenUnderCn() {
        assertTrue(KeeperPolicy.autoOpen(false, true, "cn", NOW, "", 0).fire);
    }

    @Test
    public void autoOpenRefusals() {
        assertFalse(KeeperPolicy.autoOpen(true, true, "cn", NOW, "", 0).fire);
        assertFalse(KeeperPolicy.autoOpen(false, false, "cn", NOW, "", 0).fire);
        assertFalse(KeeperPolicy.autoOpen(false, true, "us", NOW, "", 0).fire);
        assertFalse(KeeperPolicy.autoOpen(false, true, "cn", NOW, TODAY, 0).fire);
        KeeperPolicy.Decision recent = KeeperPolicy.autoOpen(false, true, "cn", NOW, "", NOW - 1000);
        assertFalse(recent.fire);
    }

    @Test
    public void manualQuietWindowExpires() {
        long old = NOW - KeeperPolicy.MANUAL_QUIET_MS - 1;
        assertTrue(KeeperPolicy.autoOpen(false, true, "cn", NOW, "", old).fire);
    }

    @Test
    public void settledScansAreEightSecondsAndLater() {
        // 4.20 shipped final-scan-only by mistake: with 8-10s visits the 20s scan never fired
        // and the keeper never judged (10-03/10-04 logs). 8s and 20s settle, 2s is transitional.
        assertFalse(KeeperPolicy.isSettled(2));
        assertTrue(KeeperPolicy.isSettled(8));
        assertTrue(KeeperPolicy.isSettled(20));
    }

    @Test
    public void snapshotOncePerDay() {
        assertTrue(KeeperPolicy.shouldSnapshot("", NOW));
        assertTrue(KeeperPolicy.shouldSnapshot(null, NOW));
        assertFalse(KeeperPolicy.shouldSnapshot(TODAY, NOW));
        assertTrue(KeeperPolicy.shouldSnapshot(TODAY, NOW + KeeperPolicy.DAY_MS));
    }

    @Test
    public void prefsAreNeverRestored() {
        assertFalse(KeeperPolicy.mayRestore("shared_prefs/primes.xml"));
        assertFalse(KeeperPolicy.mayRestore(null));
        assertTrue(KeeperPolicy.mayRestore("files/phenotype/shared/all_accounts.pb"));
        assertTrue(KeeperPolicy.mayRestore("no_backup/flags/b533107089"));
    }

    @Test
    public void propHandlesMissingAndNull() {
        Properties p = new Properties();
        assertEquals("", KeeperPolicy.prop(p, "missing"));
        assertEquals("", KeeperPolicy.prop(null, "any"));
        p.setProperty("k", "v");
        assertEquals("v", KeeperPolicy.prop(p, "k"));
    }

    @Test
    public void codecRoundTripAndManifest() {
        String rel = "files/phenotype/shared/all_accounts.pb";
        String encoded = KeeperCodec.encodeName(rel);
        assertFalse(encoded.contains("/"));
        assertEquals("files_phenotype_shared_all_accounts.pb", encoded);
        String line = KeeperCodec.manifestLine(rel, 185);
        StringBuilder out = new StringBuilder();
        long[] size = KeeperCodec.parseManifest(line, out);
        assertTrue(size != null && size[0] == 185);
        assertEquals(rel, out.toString());
    }

    @Test
    public void manifestRejectsMalformedLines() {
        StringBuilder out = new StringBuilder();
        assertNull(KeeperCodec.parseManifest("no-pipes-here", out));
        assertNull(KeeperCodec.parseManifest("a|b|notanumber", out));
        // encoded column must match the relative column's encoding
        assertNull(KeeperCodec.parseManifest("wrong|x.pb|3", out));
    }
}
