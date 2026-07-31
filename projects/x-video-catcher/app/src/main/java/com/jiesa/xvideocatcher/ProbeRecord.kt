package com.jiesa.xvideocatcher

/**
 * Formats one probe observation as a JSONL record.
 *
 * Kept free of Android and Xposed types on purpose: this is the part where a bug
 * silently corrupts the export Jay sends back, so it has to be unit-testable on a
 * plain JVM.
 */
object ProbeRecord {

    /**
     * @param stack already-filtered frames, outermost first.
     */
    fun candidate(
        timestamp: String,
        source: String,
        url: String,
        stack: List<String>,
    ): String {
        val json = StringBuilder(url.length + 128)
        json.append('{')
        json.append("\"ts\":\"").append(escape(timestamp)).append("\",")
        json.append("\"source\":\"").append(escape(source)).append("\",")
        json.append("\"url\":\"").append(escape(url)).append("\",")
        json.append("\"stack\":[")
        stack.forEachIndexed { index, frame ->
            if (index > 0) json.append(',')
            json.append('"').append(escape(frame)).append('"')
        }
        json.append("]}")
        return json.toString()
    }

    fun note(timestamp: String, message: String): String =
        "{\"ts\":\"${escape(timestamp)}\",\"note\":\"${escape(message)}\"}"

    /**
     * Trims the frames worth keeping: our own and the framework's frames say nothing
     * about who fetched the media, and an unbounded trace floods the log on every
     * media segment.
     */
    fun relevantFrames(stack: Array<StackTraceElement>, limit: Int = 12): List<String> =
        stack.asSequence()
            .filterNot { it.className.startsWith("com.jiesa.xvideocatcher") }
            .filterNot { it.className.startsWith("de.robv.android.xposed") }
            .map { "${it.className}.${it.methodName}" }
            .take(limit)
            .toList()

    /**
     * A record spans exactly one line, so any raw newline would split it and break the
     * JSONL contract. Control characters get replaced rather than dropped so the
     * surrounding text is never destroyed.
     */
    fun escape(raw: String): String {
        val out = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ' || c == '\u007f') out.append(' ') else out.append(c)
            }
        }
        return out.toString()
    }
}
