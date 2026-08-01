package com.jiesa.xvideocatcher

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File sink for probe records, running inside the *hooked* app's process.
 *
 * Why not a ContentProvider in the module app (the previous design): since Android 11,
 * package visibility filtering means a process can only resolve a `content://`
 * authority it declares in its own `<queries>`. The client here is X, whose manifest
 * we cannot edit, so every insert from X failed to resolve the provider and all
 * records were silently dropped. That approach cannot be fixed from our side.
 *
 * Instead X writes the log itself, into shared Downloads. The writing app owns the
 * file, so no permission and no cross-process lookup is involved — and the result
 * lands somewhere the user can reach with any file manager, which is the actual goal.
 *
 * Nothing here may throw into the host app.
 */
object ProbeSink {

    /** Subdirectory under Downloads, so the probe never litters the folder root. */
    const val DIR_NAME = "XVideoCatcher"
    const val MIME = "application/x-ndjson"

    private val dayStamp = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** One file per day: bounded growth, and easy to name when asking for it back. */
    fun fileName(now: Date = Date()): String = "xvc-probe-${dayStamp.format(now)}.jsonl"

    /** Path shown in the UI. Must match where [append] actually writes. */
    fun displayPath(now: Date = Date()): String = "Download/$DIR_NAME/${fileName(now)}"

    /**
     * Byte destination, replaceable in tests.
     *
     * Robolectric ships no MediaStore provider implementation — its ShadowMediaStore
     * covers thumbnails and cloud-media only — so `insert` into the Downloads
     * collection always returns null under test. Asserting on MediaStore bytes there
     * would only ever prove the shadow is missing, not that the sink works. The seam
     * therefore sits at the byte-append boundary: tests substitute a plain file and
     * verify the retention and append contracts, while the MediaStore specifics are
     * verified on-device.
     */
    internal interface Writer {
        /** Appends [payload] to [name], creating it if needed. False on failure. */
        fun append(context: Context, name: String, payload: String): Boolean
    }

    private val mediaStoreWriter = object : Writer {
        override fun append(context: Context, name: String, payload: String): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendViaMediaStore(context, name, payload)
            } else {
                appendViaFile(name, payload)
            }
    }

    @Volatile
    private var writer: Writer = mediaStoreWriter

    /**
     * Appends lines, creating the file on first use. Returns false when the write
     * failed, so the caller can retain the records instead of assuming success.
     */
    fun append(context: Context, lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        val payload = lines.joinToString(separator = "\n", postfix = "\n")
        return runCatching { writer.append(context, fileName(), payload) }.getOrDefault(false)
    }

    /**
     * MediaStore has no append mode, so an existing row is reopened in "wa". The row is
     * looked up by RELATIVE_PATH + DISPLAY_NAME rather than remembered in a field: the
     * host process can be restarted at any time, and a stale cached Uri would send the
     * rest of the day's records nowhere.
     */
    private fun appendViaMediaStore(
        context: Context,
        name: String,
        payload: String,
    ): Boolean = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/"

        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relative, name),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        }

        val uri = existing ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            },
        ) ?: return false

        resolver.openOutputStream(uri, "wa")?.use { it.write(payload.toByteArray()) } ?: return false
        true
    }.getOrDefault(false)

    /** API 28 has no scoped storage; the legacy path works when the host holds the permission. */
    private fun appendViaFile(name: String, payload: String): Boolean = runCatching {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME,
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        File(dir, name).appendText(payload)
        true
    }.getOrDefault(false)

    // --- test seam --------------------------------------------------------------

    /** Routes writes into [dir] as plain files. */
    internal fun useFileWriterForTest(dir: File) {
        writer = object : Writer {
            override fun append(context: Context, name: String, payload: String): Boolean {
                if (!dir.exists() && !dir.mkdirs()) return false
                File(dir, name).appendText(payload)
                return true
            }
        }
    }

    /** Makes every write fail, to verify records are retained rather than lost. */
    internal fun useFailingWriterForTest() {
        writer = object : Writer {
            override fun append(context: Context, name: String, payload: String) = false
        }
    }

    internal fun restoreWriterForTest() {
        writer = mediaStoreWriter
    }
}
