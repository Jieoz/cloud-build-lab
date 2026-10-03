package io.github.timeline_unlocker.xposed;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure: drives the ladder of attempts to open the Timeline page inside Maps without the in-app
 * entry. Unit-tested off device; MainHook executes what this decides.
 *
 * <p>Attempts, most direct first: the confirmed veneer method with the live captured argument,
 * candidate veneer methods with {@code null} (container analysis: the entry button ends up
 * there), then Maps deep links. Each attempt is one {@link Step}; the driver caps the ladder so a
 * failing build cannot loop the main thread or spam the log.</p>
 */
final class OpenerDriver {

    /** One thing to try. */
    static final class Step {
        final int kind; // KIND_VENEER_ARG / KIND_VENEER_NULL / KIND_LINK
        final String className; // veneer class (or "" for links)
        final String methodName; // veneer method (or "" for links)
        final String argDump; // captured field dump, when replaying the capture
        final String link; // deep link (or "" for veneer)

        Step(int kind, String className, String methodName, String argDump, String link) {
            this.kind = kind;
            this.className = className == null ? "" : className;
            this.methodName = methodName == null ? "" : methodName;
            this.argDump = argDump == null ? "" : argDump;
            this.link = link == null ? "" : link;
        }

        String describe() {
            switch (kind) {
                case KIND_VENEER_ARG:
                    return "veneer " + className + "." + methodName + "(captured arg,"
                            + OpenerCapture.dumpSize(argDump) + " fields)";
                case KIND_VENEER_NULL:
                    return "veneer " + className + "." + methodName + "(null)";
                default:
                    return "link " + link;
            }
        }
    }

    static final int KIND_VENEER_ARG = 1;
    static final int KIND_VENEER_NULL = 2;
    static final int KIND_LINK = 3;

    private static final int MAX_STEPS = 12;

    private OpenerDriver() {}

    /**
     * The ladder for one open request. {@code confirmed} is the veneer whose firing was followed
     * by a wrapper within the window; it is tried first with the live captured argument, then all
     * candidates (confirmed included) with {@code null}, then the deep links.
     */
    static List<Step> ladder(boolean confirmed, String confirmedClass, String confirmedMethod,
                             String argDump, String[] candidates, String[] links) {
        List<Step> out = new ArrayList<>();
        if (confirmed && confirmedClass != null && !confirmedClass.isEmpty()
                && confirmedMethod != null && !confirmedMethod.isEmpty()) {
            out.add(new Step(KIND_VENEER_ARG, confirmedClass, confirmedMethod, argDump, ""));
        }
        if (candidates != null) {
            for (String candidate : candidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                int dot = candidate.lastIndexOf('.');
                if (dot <= 0) continue;
                out.add(new Step(KIND_VENEER_NULL, candidate.substring(0, dot),
                        candidate.substring(dot + 1), "", ""));
            }
        }
        if (links != null) {
            for (String link : links) {
                if (link != null && !link.isEmpty()) {
                    out.add(new Step(KIND_LINK, "", "", "", link));
                }
            }
        }
        return out.size() > MAX_STEPS ? out.subList(0, MAX_STEPS) : out;
    }

    /** Whether a {@code TimelineWrapper} sighting within the window after step N proves it. */
    static boolean confirms(int attemptsSoFar, long wrapperSeenAtMs, long stepStartedAtMs,
                            long windowMs) {
        if (attemptsSoFar <= 0) return false;
        if (wrapperSeenAtMs < stepStartedAtMs) return false;
        return wrapperSeenAtMs - stepStartedAtMs <= windowMs;
    }

    /** One-line verdict for the log, from the step and whether the wrapper appeared. */
    static String verdict(String describe, boolean wrapperSeen) {
        return "opener " + describe + " -> " + (wrapperSeen ? "OPENED" : "no");
    }
}
