package com.jiesa.xvideocatcher

import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exercises the real sink against a real Android context.
 *
 * The bug this replaces was exactly here: the previous sink targeted a ContentProvider
 * the host app could never resolve, so every write was dropped and the export was
 * always empty. A test that mocked the resolver would have passed anyway, so these
 * assert on bytes that actually landed on disk.
 *
 * Pinned to 28, where the sink takes the direct-file branch and Robolectric backs
 * external storage with a real temp dir. The MediaStore branch (29+) cannot be
 * meaningfully faked — its correctness is verified on-device by the log file appearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProbeSinkTest {

    private fun logFile(): File = File(
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            ProbeSink.DIR_NAME,
        ),
        ProbeSink.fileName(),
    )

    @Test
    fun `append creates the file and writes one line per record`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertTrue(ProbeSink.append(context, listOf("""{"a":1}""", """{"b":2}""")))

        val file = logFile()
        assertTrue("sink reported success but wrote no file", file.exists())
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), file.readLines())
    }

    @Test
    fun `append adds to an existing file instead of truncating it`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        ProbeSink.append(context, listOf("""{"first":1}"""))
        ProbeSink.append(context, listOf("""{"second":2}"""))

        // Truncation here would mean losing every record from earlier in the session.
        assertEquals(
            listOf("""{"first":1}""", """{"second":2}"""),
            logFile().readLines(),
        )
    }

    @Test
    fun `every record stays on its own line so the export parses as JSONL`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val record = ProbeRecord.candidate(
            timestamp = "2026-08-01 12:00:00.000",
            source = "Cronet/manifest",
            // A raw newline in a URL would split the record and break the whole file.
            url = "https://video.twimg.com/a\nb/pl/720/x.m3u8",
            stack = listOf("a.b.c\nd"),
        )

        ProbeSink.append(context, listOf(record))

        assertEquals(1, logFile().readLines().size)
    }

    @Test
    fun `empty batch is a no-op rather than an empty file`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertTrue(ProbeSink.append(context, emptyList()))

        assertTrue("empty flush must not create a file", !logFile().exists())
    }

    @Test
    fun `advertised path matches where the sink actually writes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProbeSink.append(context, listOf("""{"x":1}"""))

        // The UI tells Jay this path. If it drifts from the real one he looks in the
        // wrong folder and reports "no log" on a working build.
        assertEquals("Download/${ProbeSink.DIR_NAME}/${ProbeSink.fileName()}", ProbeSink.displayPath())
        assertTrue(logFile().absolutePath.endsWith("${ProbeSink.DIR_NAME}/${ProbeSink.fileName()}"))
    }

    @Test
    fun `file name is dated so one day's log cannot grow without bound`() {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        assertEquals("xvc-probe-$day.jsonl", ProbeSink.fileName())
    }
}
