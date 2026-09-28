package io.github.timeline_unlocker.xposed;

/**
 * Decides which host reads are evidence for the Timeline entry, so the diagnostic log
 * stays quiet on the hot path. The entry is gated on a US country / operator identity
 * inside GMS and GSF; Maps itself only consumes that decision.
 *
 * <p>Nothing here hooks or spoofs. Callers log a line only when {@link #relevant} is true.</p>
 */
final class TimelineProbe {

    private TimelineProbe() {}

    /**
     * True when a telephony / subscription / system-property read is one of the identity
     * checks Google uses to hide Timeline. Unrelated reads (signal, phone number, alpha
     * operator name, arbitrary properties) are false so they never reach the log.
     */
    static boolean relevant(String owner, String member) {
        if (member == null || member.isEmpty()) return false;
        String who = owner == null ? "" : owner;
        if (isTelephony(who)) return isCountryOrOperator(member);
        if (isSubscription(who)) return isCountryOrOperator(member);
        if (isSystemProperties(who) && isGet(member)) return true;
        return false;
    }

    /** One bounded line: who asked, which method, and the value the host actually received. */
    static String line(String owner, String member, String value) {
        String shown = value == null ? "null" : value.replace('\n', ' ').replace('\r', ' ');
        if (shown.length() > 48) shown = shown.substring(0, 48);
        return "timeline probe " + simple(owner) + "." + member + " -> " + shown;
    }

    private static boolean isTelephony(String owner) {
        return owner.endsWith("TelephonyManager");
    }

    private static boolean isSubscription(String owner) {
        return owner.endsWith("SubscriptionInfo") || owner.endsWith("SubscriptionManager");
    }

    private static boolean isSystemProperties(String owner) {
        return owner.endsWith("SystemProperties");
    }

    private static boolean isGet(String member) {
        return "get".equals(member);
    }

    private static boolean isCountryOrOperator(String member) {
        String name = member.toLowerCase(java.util.Locale.US);
        return name.contains("country")
                || name.contains("operator")
                || name.contains("mcc")
                || name.contains("mnc")
                || name.contains("simstate")
                || name.contains("carrier");
    }

    private static String simple(String owner) {
        if (owner == null || owner.isEmpty()) return "?";
        int dot = owner.lastIndexOf('.');
        return dot < 0 ? owner : owner.substring(dot + 1);
    }
}
