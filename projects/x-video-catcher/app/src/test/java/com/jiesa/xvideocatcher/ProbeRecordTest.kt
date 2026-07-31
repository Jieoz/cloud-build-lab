package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export is only useful if every record is one parseable line. These tests guard
 * the two ways that silently breaks: an unescaped newline splitting a record, and
 * escaping that destroys the URL text itself.
 */
class ProbeRecordTest {

    @Test
    fun `record is a single line`() {
        val record = ProbeRecord.candidate(
            timestamp = "2026-07-29 10:00:00.000",
            source = "Cronet.newUrlRequestBuilder/manifest",
            url = "https://video.twimg.com/a/pl/720/x.m3u8",
            stack = listOf("a.b.c.fetch", "d.e.f.load"),
        )
        assertFalse(record.contains('\n'))
        assertTrue(record.startsWith("{"))
        assertTrue(record.endsWith("}"))
    }

    @Test
    fun `newlines and quotes in input do not break the line contract`() {
        val record = ProbeRecord.candidate(
            timestamp = "t",
            source = "s",
            url = "https://x/\"quoted\"\nsecond line\ttab",
            stack = emptyList(),
        )
        assertEquals(1, record.lines().size)
        assertTrue(record.contains("\\n"))
        assertTrue(record.contains("\\\""))
    }

    @Test
    fun `escaping preserves visible characters`() {
        // Control chars are replaced, not dropped — text must survive intact.
        val escaped = ProbeRecord.escape("héllo 世界 /path?a=1&b=2")
        assertEquals("héllo 世界 /path?a=1&b=2", escaped)
    }

    @Test
    fun `frames from the module and framework are excluded`() {
        val stack = arrayOf(
            StackTraceElement("com.jiesa.xvideocatcher.ProbeLog", "candidate", null, 1),
            StackTraceElement("de.robv.android.xposed.XposedBridge", "hook", null, 1),
            StackTraceElement("com.twitter.android.abc", "load", null, 1),
        )
        assertEquals(listOf("com.twitter.android.abc.load"), ProbeRecord.relevantFrames(stack))
    }

    @Test
    fun `frame count is capped`() {
        val stack = Array(40) { StackTraceElement("app.C$it", "m", null, 1) }
        assertEquals(12, ProbeRecord.relevantFrames(stack).size)
    }

    @Test
    fun `note is single line json`() {
        val note = ProbeRecord.note("t", "layers active=[a, b]\nnext")
        assertEquals(1, note.lines().size)
        assertTrue(note.contains("\"note\""))
    }
}
