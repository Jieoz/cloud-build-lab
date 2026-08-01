package com.jiesa.xvideocatcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the failure that made the probe produce no file at all.
 *
 * Hooks are installed from `handleLoadPackage`, which the framework calls before the
 * host Application exists — so the attach records are produced while there is no
 * Context. The first version discarded anything written in that window, which meant
 * the log file only appeared if a media URL happened to be seen later, and the
 * "Downloads folder is empty means module not loaded" signal was never true.
 *
 * Robolectric is used deliberately: these assertions are about bytes reaching a real
 * MediaStore-backed file. A hand-written fake sink would pass while the real one
 * silently dropped everything, which is exactly how the bug shipped.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned to Jay's device API level; Robolectric 4.12.2 has no SDK 35 runtime jar and
// would otherwise default to targetSdk.
@Config(sdk = [34])
class ProbeLogRetentionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProbeLog.resetForTest()
        ProbeSink.deleteForTest(context)
    }

    @After
    fun tearDown() {
        ProbeLog.resetForTest()
        ProbeSink.deleteForTest(context)
    }

    @Test
    fun `records written before a Context exists are not lost`() {
        // The attach window: hooks report, but no Context has been supplied yet.
        ProbeLog.line("attached to com.twitter.android (process=com.twitter.android)")
        ProbeLog.line("probe layers active=[java.net.URL] inactive=[]")
        ProbeLog.flushNow()

        assertEquals(
            "nothing may reach disk before a Context is bound",
            emptyList<String>(),
            ProbeSink.readLinesForTest(context),
        )

        // The host Application is created; this is the first moment a write can work.
        ProbeLog.bindContext(context)

        val lines = ProbeSink.readLinesForTest(context)
        assertEquals("both attach records must survive the wait", 2, lines.size)
        assertTrue(
            "attach record must be the one that proves the module loaded: $lines",
            lines[0].contains("attached to com.twitter.android"),
        )
        assertTrue(lines[1].contains("probe layers active"))
    }

    @Test
    fun `binding a context writes the file even with no media seen`() {
        // The user-visible contract: force-stop X, reopen it, and the file exists —
        // without playing a video. Its absence then means "module not loaded".
        ProbeLog.line("attached to com.twitter.android (process=com.twitter.android)")
        ProbeLog.bindContext(context)

        assertTrue(
            "the log file must exist on attach alone",
            ProbeSink.existsForTest(context),
        )
    }

    @Test
    fun `records seen after binding are appended, not overwritten`() {
        ProbeLog.line("attached to com.twitter.android (process=com.twitter.android)")
        ProbeLog.bindContext(context)

        ProbeLog.candidate(
            source = "Cronet.newUrlRequestBuilder/media",
            url = "https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/abc.mp4",
            stack = arrayOf(
                StackTraceElement("com.twitter.android.xy", "a", "Unknown", 1),
            ),
        )
        ProbeLog.flushNow()

        val lines = ProbeSink.readLinesForTest(context)
        assertEquals("append must not truncate the earlier record", 2, lines.size)
        assertTrue(lines[0].contains("attached to"))
        assertTrue("the media URL must be recorded: ${lines[1]}", lines[1].contains("abc.mp4"))
    }
}
