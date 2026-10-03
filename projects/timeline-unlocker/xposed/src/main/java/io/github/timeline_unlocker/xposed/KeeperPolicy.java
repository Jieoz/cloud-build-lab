package io.github.timeline_unlocker.xposed;

import java.util.Properties;

/**
 * Pure: decisions for the entry keeper. Unit-tested.
 *
 * <p>Auto-open: the server keeps revoking the Timeline entry under the cn identity, but the
 * in-process deep link demonstrably opens the Timeline page anyway (2026-10-03). So when a real
 * home screen shows no entry, Maps itself opens the link — at most once per day, never right
 * after a manual request, never while the screen could not be trusted.</p>
 */
final class KeeperPolicy {

    /** One automatic open per day. */
    static final long DAY_MS = 24L * 3600_000;
    /** A manual open request younger than this suppresses the automatic one. */
    static final long MANUAL_QUIET_MS = 10 * 60_000;

    private KeeperPolicy() {}

    /** Reasons the automatic open can be refused; {@code ok} means fire it. */
    static final class Decision {
        final boolean fire;
        final String reason;

        private Decision(boolean fire, String reason) {
            this.fire = fire;
            this.reason = reason;
        }

        static final Decision OK = new Decision(true, "ok");

        static Decision no(String reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * @param entryButton the home screen's entry button group was seen on this scan
     * @param realScreen  the scan saw a real screen ({@code views >= ResumeVerdict.MIN_SCREEN_VIEWS})
     * @param identity    the identity Maps was given (cn/us)
     * @param now         wall clock, millis
     * @param lastOpenDay day stamp (yyyyMMdd) of the previous automatic open, or empty
     * @param manualAt    timestamp of the newest manual open request, 0 when none
     */
    static Decision autoOpen(boolean entryButton, boolean realScreen, String identity, long now,
                             String lastOpenDay, long manualAt) {
        if (entryButton) return Decision.no("entry present");
        if (!realScreen) return Decision.no("background screen");
        if (!"cn".equals(identity)) return Decision.no("identity " + identity);
        if (!lastOpenDay.isEmpty() && lastOpenDay.equals(dayStamp(now))) {
            return Decision.no("already opened today");
        }
        if (manualAt > 0 && now - manualAt < MANUAL_QUIET_MS) {
            return Decision.no("manual request " + ((now - manualAt) / 1000) + "s ago");
        }
        return Decision.OK;
    }

    /**
     * A stored snapshot may be restored: only files this process itself wrote (owner check is
     * implicit), and never shared_prefs — wholesale-restoring preferences would clobber newer
     * user settings; the hypothesis under test is about flag/phenotype files.
     */
    static boolean mayRestore(String relativePath) {
        return relativePath != null && !relativePath.startsWith("shared_prefs/");
    }

    /** Snapshot at most once a day: the files change daily but a week-old copy already answers. */
    static boolean shouldSnapshot(String lastSnapshotDay, long now) {
        return !dayStamp(now).equals(lastSnapshotDay == null ? "" : lastSnapshotDay);
    }

    static String dayStamp(long now) {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date(now));
    }

    /** Reads the stored {@code keeper_open_day} / {@code keeper_snapshot_day} values. */
    static String prop(Properties state, String key) {
        return state == null ? "" : String.valueOf(state.getProperty(key, ""));
    }
}
