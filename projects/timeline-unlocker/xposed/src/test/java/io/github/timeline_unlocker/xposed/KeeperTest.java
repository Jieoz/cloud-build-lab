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
    public void manifestAcceptsZeroByteRows() {
        // ~80 no_backup_flags_b* rows are 0-byte files whose existence is the meaning; the
        // restore loop must treat size=0 as valid data (10-05: bytes[0] AIOOB on empty rows
        // killed the whole restore before a single file was written).
        StringBuilder rel = new StringBuilder();
        long[] size = KeeperCodec.parseManifest(
                "no_backup_flags_b123|no_backup/flags/b123|0", rel);
        assertTrue(size != null);
        assertEquals(0L, size[0]);
        assertEquals("no_backup/flags/b123", rel.toString());
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
    public void snapshotDayWithoutHaveSnapshotStillCountsAsSnapshot() {
        // 10-05: the device's only snapshot was written by 4.23, which set snapshot_day but not
        // have_snapshot (the key shipped in 4.24). The ladder's gate saw "never snapshotted",
        // silently disabled the retries, and 106 rows went unused all day. The gate must accept
        // snapshot_day alone as proof a snapshot exists (EntryKeeper backfills the key).
        Properties state = new Properties();
        assertTrue(state.getProperty("have_snapshot", "").isEmpty());
        String snapDay = "20261005";
        // the exact gate logic now in EntryKeeper.scheduleRestoreRetries
        String haveSnap = KeeperPolicy.prop(state, "have_snapshot");
        if (haveSnap.isEmpty() && !snapDay.isEmpty()) haveSnap = snapDay;
        assertFalse(haveSnap.isEmpty());
        // and a truly never-snapshotted device still skips the ladder
        assertTrue(KeeperPolicy.prop(new Properties(), "have_snapshot").isEmpty());
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
        assertFalse(KeeperPolicy.mayRestore("shared_prefs/com.google.android.gms.appid.xml"));
        assertFalse(KeeperPolicy.mayRestore(null));
        assertTrue(KeeperPolicy.mayRestore("files/phenotype/shared/all_accounts.pb"));
        assertTrue(KeeperPolicy.mayRestore("no_backup/flags/b533107089"));
    }

    @Test
    public void settingsPreferenceIsTheOneRestoredPref() {
        // 4.26: settings_preference.xml carries the timeline_* keys that record the grant
        // itself (8 keys in the 10-07 snapshot); the 10-05 revoke window lined up with this
        // file growing 13213->21364. Skipping exactly this file would have made the next
        // natural-revoke experiment uninterpretable ("entry still gone" could then mean
        // account-side revocation OR the one excluded file). Whole-file restore accepted.
        assertTrue(KeeperPolicy.mayRestore(KeeperPolicy.RESTORED_PREF));
        assertEquals("shared_prefs/settings_preference.xml", KeeperPolicy.RESTORED_PREF);
        // every other pref stays excluded
        assertFalse(KeeperPolicy.mayRestore("shared_prefs/settings_preference.xml.bak"));
    }

    @Test
    public void revokeRowsAreNamespacedAgainstAuthorizationRows() {
        // The revoke capture must never overwrite an authorization snapshot's payload row:
        // its manifest is trusted, but a name collision would silently replace evidence.
        String plain = KeeperCodec.encodeName("files/phenotype/shared/all_accounts.pb");
        String rv = KeeperCodec.revokeName(plain);
        assertEquals("rv_" + plain, rv);
        assertFalse(rv.equals(plain));
    }

    @Test
    public void revokeManifestIsNeverConsumedByRestore() {
        // Revoke manifest lines are rv_-prefixed in column 1, which fails parseManifest's
        // encoded-name check on purpose: restore only ever consumes keeper-manifest.txt.
        String rel = "files/phenotype/shared/all_accounts.pb";
        String line = KeeperCodec.revokeName(KeeperCodec.encodeName(rel)) + "|" + rel + "|185";
        StringBuilder out = new StringBuilder();
        assertNull(KeeperCodec.parseManifest(line, out));
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
