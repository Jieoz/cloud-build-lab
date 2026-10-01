package io.github.timeline_unlocker.xposed;

/**
 * Pure: the entry verdict of one Maps resume. Unit-tested.
 *
 * <p>{@code yes} as soon as one scan of the resume shows the entry. {@code no} when the resume
 * ends (pause or next resume) after at least one scan of a real screen found nothing. Scans of a
 * near-empty tree (Maps already in the background, {@code views=1}) say nothing either way.</p>
 */
final class ResumeVerdict {

    /** log101: a backgrounded MapsActivity scans as views=1, the home screen as 180+. */
    static final int MIN_SCREEN_VIEWS = 100;

    private boolean found;
    private boolean looked;

    void reset() {
        found = false;
        looked = false;
    }

    /** Returns the hits to record as {@code yes} the first time this resume shows the entry. */
    String scan(boolean hit, String hits, int views) {
        if (views < MIN_SCREEN_VIEWS) return null;
        looked = true;
        if (!hit || found) return null;
        found = true;
        return hits;
    }

    /** True when this resume should record {@code no}. Closes the resume either way. */
    boolean end() {
        boolean no = looked && !found;
        reset();
        return no;
    }
}
