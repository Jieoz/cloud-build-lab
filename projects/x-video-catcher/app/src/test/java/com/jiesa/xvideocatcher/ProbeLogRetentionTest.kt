package com.jiesa.xvideocatcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the failure that made the probe produce no file at all.
 *
 * Hooks are installed from `handleLoadPackage`, which the framework calls before the
 * host Application exists, so the attach records are produced while there is no
 * Context. The first version discarded anything written in that window: the log file
 * only appeared if a media URL happened to be seen later, and the intended signal
 * ("empty Downloads folder means the module did not load") was never true.
 *
 * The byte destination is substituted here — Robolectric has no MediaStore provider,
 * so the real 29+ branch cannot run under test. What is being verified is the
 * retention contract: which records reach the writer, in what order, and whether a
 * failed write loses them. That is where the bug was.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned to Jay's device API level; Robolectric 4.12.2 has no SDK 35 runtime jar and
// would otherwise default to targetSdk.
@Config(sdk = [34])
class ProbeLogRetentionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var dir: File

    private fun lines(): List<String> {
        val f = File(dir, ProbeSink.fileName())
        return if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = temp.newFolder("downloads")
        ProbeLog.resetForTest()
        ProbeSink.useFileWriterForTest(dir)
    }

    @After
    fun tearDown() {
        ProbeSink.restoreWriterForTest()
        ProbeLog.resetForTest()
    }

    @Test
    fun `records written before a Context exists are not lost`() {
        // The attach window: hooks report, but no Context has been supplied yet.
        ProbeLog.line("attached to com.twitter.android (process=com.twitter.android)")
        ProbeLog.line("probe layers active=[java.net.URL] inactive=[]")
        ProbeLog.flushNow()

        assertEquals("nothing can reach disk before a Context is bound", emptyList<String>(), lines())
        // Distinguishes "held for later" from "silently dropped" — the original bug
        // looked identical to this point and differed only here.
        assertEquals("records must be retained, not discarded", 2, ProbeLog.retainedCountForTest())

        // The host Application is created; this is the first moment a write can work.
        ProbeLog.bindContext(context)

        val written = lines()
        assertEquals("both attach records must survive the wait: $written", 2, written.size)
        assertTrue(
            "the attach record is what proves the module loaded: $written",
            written[0].contains("attached to com.twitter.android"),
        )
        assertTrue(written[1].contains("probe layers active"))
    }

    @Test
    fun `binding a context writes the file even when no media was seen`() {
        // The user-visible contract: force-stop X, reopen it, and the file exists
        // without playing a video — so its absence means "module not loaded".
        ProbeLog.line("attached to com.twitter.android (process=com.twitter.android)")
        ProbeLog.bindContext(context)

        assertTrue("the log file must exist on attach alone", File(dir, ProbeSink.fileName()).exists())
    }

    @Test
    fun `records seen after binding are appended, not overwritten`() {
        ProbeLog.line("attached to com.twitter.android (process=com.twitter.android)")
        ProbeLog.bindContext(context)

        ProbeLog.candidate(
            source = "Cronet.newUrlRequestBuilder/media",
            url = "https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/abc.mp4",
            stack = arrayOf(StackTraceElement("com.twitter.android.xy", "a", "Unknown", 1)),
        )
        ProbeLog.flushNow()

        val written = lines()
        assertEquals("append must not truncate the earlier record: $written", 2, written.size)
        assertTrue(written[0].contains("attached to"))
        assertTrue("the media URL must be recorded: ${written[1]}", written[1].contains("abc.mp4"))
    }

    @Test
    fun `a failed write retains records for the next attempt`() {
        ProbeSink.useFailingWriterForTest()
        ProbeLog.bindContext(context)
        ProbeLog.line("attached to com.twitter.android (process=com.twitter.android)")
        ProbeLog.flushNow()

        // Sink recovers — a transient MediaStore failure must not cost the records.
        ProbeSink.useFileWriterForTest(dir)
        ProbeLog.flushNow()

        val written = lines()
        assertEquals("the retained record must be delivered on retry: $written", 1, written.size)
        assertTrue(written[0].contains("attached to com.twitter.android"))
    }
}
