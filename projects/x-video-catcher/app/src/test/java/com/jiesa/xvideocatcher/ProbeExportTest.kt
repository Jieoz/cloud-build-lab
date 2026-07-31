package com.jiesa.xvideocatcher

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.content.Context

/**
 * Exercises the real export path end to end: records arrive the way the hook sends
 * them (through the provider, from another UID), get persisted, and come back out as
 * an export file.
 *
 * This is the part worth testing against a genuine Android context — a hand-rolled
 * fake would not catch the cross-process contract breaking, which is exactly the
 * failure that leaves Jay with an empty log and nothing to send.
 */
@RunWith(RobolectricTestRunner::class)
class ProbeExportTest {

    private lateinit var context: Context
    private lateinit var store: ProbeStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ProbeStore(context)
        store.clear()
    }

    /** Provider with a caller identity we control, since tests have no binder. */
    private class TestProvider(private val caller: String?) : ProbeProvider() {
        override fun resolveCallingPackage(): String? = caller
    }

    private fun provider(caller: String? = "com.twitter.android"): ProbeProvider =
        TestProvider(caller).apply {
            attachInfo(ApplicationProvider.getApplicationContext(), null)
        }

    @Test
    fun `records inserted by the hook are persisted and exported`() {
        val p = provider()
        val batch = listOf(
            ProbeRecord.note("t", "attached"),
            ProbeRecord.candidate("t", "Cronet/manifest", listOf("a.b.c")),
        ).joinToString("\n")

        val uri = p.insert(
            ProbeContract.LOG_URI,
            ContentValues().apply { put(ProbeContract.COLUMN_LINES, batch) },
        )
        assertNotNull(uri)
        assertEquals(2, store.lineCount())

        val export = store.buildExport(listOf(ProbeRecord.note("t", "export header")))
        assertNotNull(export)
        val lines = export!!.readLines().filter { it.isNotBlank() }
        // Header first, then every collected record.
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("export header"))
        assertTrue(lines.any { it.contains("Cronet/manifest") })
        // Every exported line must be one self-contained record.
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
    }

    @Test
    fun `export lives under the shared export dir only`() {
        val p = provider()
        p.insert(
            ProbeContract.LOG_URI,
            ContentValues().apply { put(ProbeContract.COLUMN_LINES, ProbeRecord.note("t", "x")) },
        )
        val export = store.buildExport(emptyList())
        assertNotNull(export)
        // FileProvider only publishes files/export/ — an export written anywhere else
        // would fail to share at runtime with an opaque IllegalArgumentException.
        assertEquals(
            java.io.File(context.filesDir, ProbeStore.EXPORT_DIR).canonicalPath,
            export!!.parentFile!!.canonicalPath,
        )
        assertFalse(store.logFile.canonicalPath == export.canonicalPath)
    }

    @Test
    fun `foreign apps cannot write or read the log`() {
        val hostile = provider(caller = "com.evil.app")
        val values = ContentValues().apply { put(ProbeContract.COLUMN_LINES, ProbeRecord.note("t", "x")) }

        var insertRejected = false
        try {
            hostile.insert(ProbeContract.LOG_URI, values)
        } catch (e: SecurityException) {
            insertRejected = true
        }
        assertTrue("foreign insert must be rejected", insertRejected)

        var queryRejected = false
        try {
            hostile.query(ProbeContract.LOG_URI, null, null, null, null)
        } catch (e: SecurityException) {
            queryRejected = true
        }
        assertTrue("foreign query must be rejected", queryRejected)

        assertEquals(0, store.lineCount())
    }

    @Test
    fun `clear empties the log`() {
        val p = provider()
        p.insert(
            ProbeContract.LOG_URI,
            ContentValues().apply { put(ProbeContract.COLUMN_LINES, ProbeRecord.note("t", "x")) },
        )
        assertEquals(1, store.lineCount())
        assertEquals(1, p.delete(ProbeContract.LOG_URI, null, null))
        assertEquals(0, store.lineCount())
    }

    @Test
    fun `query reports collected stats`() {
        val p = provider()
        p.insert(
            ProbeContract.LOG_URI,
            ContentValues().apply {
                put(ProbeContract.COLUMN_LINES, listOf("{\"a\":1}", "{\"b\":2}").joinToString("\n"))
            },
        )
        p.query(ProbeContract.LOG_URI, null, null, null, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow("lines")))
            assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("bytes")) > 0)
        }
    }

    @Test
    fun `blank lines in a batch are not stored`() {
        val p = provider()
        p.insert(
            ProbeContract.LOG_URI,
            ContentValues().apply { put(ProbeContract.COLUMN_LINES, "{\"a\":1}\n\n\n{\"b\":2}\n") },
        )
        assertEquals(2, store.lineCount())
    }
}
