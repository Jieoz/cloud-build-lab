package io.github.timeline_unlocker.xposed;

/** One bounded log row for an Activity intent. Pure, so it is unit-tested off device. */
final class ActivityLine {

    private static final int MAX_DATA = 120;

    private ActivityLine() {}

    static String describe(String action, String data, String component) {
        StringBuilder out = new StringBuilder();
        out.append("action=").append(action == null ? "-" : shorten(action));
        out.append(" data=").append(data == null ? "-" : clip(data));
        if (component != null) out.append(" target=").append(component);
        return out.toString();
    }

    private static String shorten(String action) {
        return action.startsWith("android.intent.action.")
                ? action.substring("android.intent.action.".length()) : action;
    }

    /** Keeps scheme/host/path, drops the query (it can carry account or place tokens). */
    private static String clip(String data) {
        String text = data.replace('\n', ' ').replace('\r', ' ');
        int query = text.indexOf('?');
        if (query >= 0) text = text.substring(0, query) + "?…";
        return text.length() > MAX_DATA ? text.substring(0, MAX_DATA) + "…" : text;
    }
}
