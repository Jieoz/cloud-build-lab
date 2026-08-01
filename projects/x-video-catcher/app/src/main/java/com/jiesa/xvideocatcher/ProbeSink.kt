package com.jiesa.xvideocatcher

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
     * Appends lines, creating the file on first use. Returns false when the write
     * failed, so the caller can report a dead sink instead of assuming success.
     */
    fun append(context: Context, lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        val payload = lines.joinToString(separator = "\n", postfix = "\n")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendViaMediaStore(context, payload)
        } else {
            appendViaFile(payload)
        }
    }

    /**
     * MediaStore has no append mode, so an existing row is reopened in "wa". The row is
     * looked up by RELATIVE_PATH + DISPLAY_NAME rather than remembered in a field: the
     * host process can be restarted at any time, and a stale cached Uri would send the
     * rest of the day's records nowhere.
     */
    private fun appendViaMediaStore(context: Context, payload: String): Boolean = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = fileName()
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/"

        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relative, name),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
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
    private fun appendViaFile(payload: String): Boolean = runCatching {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME,
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        File(dir, fileName()).appendText(payload)
        true
    }.getOrDefault(false)

    // --- test seams -------------------------------------------------------------
    // These read back through the same MediaStore path used for writing, so tests
    // assert on what actually landed on disk rather than on a stand-in.

    internal fun readLinesForTest(context: Context): List<String> = runCatching {
        val uri = locateForTest(context) ?: return emptyList()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
                .split("\n")
                .filter { it.isNotBlank() }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    internal fun existsForTest(context: Context): Boolean = locateForTest(context) != null

    internal fun deleteForTest(context: Context) {
        runCatching {
            locateForTest(context)?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun locateForTest(context: Context) = runCatching {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/", fileName()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        }
    }.getOrNull()
}
