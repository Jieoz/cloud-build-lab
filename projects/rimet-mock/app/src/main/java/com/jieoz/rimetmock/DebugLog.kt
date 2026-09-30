package com.jieoz.rimetmock

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Log sink — copied VERBATIM from the verified pixelify-lsp102 DebugLog (only the names differ).
 * The hooked app (DingTalk) writes the file itself; it runs under DingTalk's UID and cannot
 * touch the module app's storage.
 *
 * Channel order is load-bearing and must NOT be changed: MediaStore FIRST on API 29+, because a
 * MediaStore-registered file is indexed and shows up immediately in the system Downloads / Files
 * app (which is how the "查看日志" button surfaces it — an in-app ContentResolver read is
 * owner-filtered on Android 11+ and would never see DingTalk's row). Direct-file + app-external
 * are fallbacks only. A prior port (DiagSink) wrote direct-file first, which is invisible to the
 * Downloads app until a media scan — that regression is why the log "disappeared".
 *
 * Switch OFF => [line] returns immediately; nothing is written and no marker file is left behind.
 */
object DebugLog {
    const val TAG = "RimetMock"
    const val DIR_NAME = "RimetMock"
    private const val EXT = ".txt"
    private val sessionSuffix: String = UUID.randomUUID().toString().substring(0, 6)

    fun fileName(now: Date = Date()): String =
        "rimetmock-${SimpleDateFormat("yyyyMMdd", Locale.US).format(now)}-${Process.myPid()}-$sessionSuffix$EXT"

    @Volatile
    private var enabled: Boolean = false

    private val pending = ArrayDeque<String>()

    @Volatile
    private var host: Context? = null

    fun bind(context: Context) {
        host = context.applicationContext ?: context
        val queued = synchronized(pending) { List(pending.size) { pending.removeFirst() } }
        queued.forEach { write(it) }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun line(message: String, always: Boolean = false) {
        // OFF = one volatile read and an immediate return. No timestamp, no Log.i, no framework
        // binder call, no queue, no MediaStore. This is the load-bearing part of the switch: with
        // it off, logging costs nothing and writes no file. Do not move work above this line.
        if (!enabled && !always) return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val row = "$stamp pid=${Process.myPid()} $message\n"
        Log.i(TAG, message)
        runCatching { RimetMockModule.framework.log(Log.INFO, TAG, message) }
        if (host == null) {
            synchronized(pending) { pending.addLast(row) }
            return
        }
        write(row)
    }

    @Volatile
    private var reportedSink: String? = null

    @Volatile
    private var mediaStoreError: String? = null

    private fun write(row: String) {
        val context = host ?: return
        val ext = appendAppExternal(context, row)
        val ms = if (Build.VERSION.SDK_INT >= 29) appendMediaStore(context, row) else false
        val file = if (!ms) appendFile(row) else null
        val summary = buildString {
            append(if (ext) "app-external -> ${appExternalTarget(context)}" else "app-external FAILED")
            append(if (ms) "; mediastore -> ${relativePath()}${fileName()}" else "; mediastore FAILED (${mediaStoreError ?: "insert/open returned false"})")
            if (!ms) append(if (file != null) "; download-file -> ${file.absolutePath}" else "; download-file FAILED")
        }
        if (summary != reportedSink) {
            reportedSink = summary
            runCatching { RimetMockModule.framework.log(Log.INFO, TAG, "log file: $summary") }
            if (ext) File(File(context.getExternalFilesDir(null), DIR_NAME), fileName())
                .appendText("log file: $summary\n")
        }
    }

    private fun appExternalTarget(context: Context): String = runCatching {
        File(File(context.getExternalFilesDir(null), DIR_NAME), fileName()).absolutePath
    }.getOrDefault("(app-external unavailable)")

    fun read(context: Context): String {
        val uri = newest(context)
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            if (text.isNotBlank()) return text
        }
        return readDirect()
    }

    private fun readDirect(): String {
        val dir = File(publicDir(), DIR_NAME)
        val latest = dir.listFiles { f -> f.name.startsWith("rimetmock-") && f.name.endsWith(EXT) }
            ?.maxByOrNull { it.lastModified() } ?: return ""
        return runCatching { latest.readText() }.getOrDefault("")
    }

    private fun appendAppExternal(context: Context, row: String): Boolean = runCatching {
        val base = context.getExternalFilesDir(null) ?: return false
        val dir = File(base, DIR_NAME)
        dir.mkdirs()
        File(dir, fileName()).appendText(row)
        true
    }.getOrDefault(false)

    private fun publicDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private fun appendFile(row: String): File? = runCatching {
        val dir = File(publicDir(), DIR_NAME)
        dir.mkdirs()
        val f = File(dir, fileName())
        f.appendText(row)
        f
    }.getOrNull()

    private fun appendMediaStore(context: Context, row: String): Boolean {
        mediaStoreError = null
        return try {
            val resolver = context.contentResolver
            val name = fileName()
            val uri = find(context, name) ?: resolver.insert(collection(), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
            }) ?: run {
                mediaStoreError = "insert returned null"
                return false
            }
            val wrote = resolver.openOutputStream(uri, "wa")?.use { it.write(row.toByteArray()) } != null
            if (!wrote) mediaStoreError = "openOutputStream returned null"
            wrote
        } catch (t: Throwable) {
            mediaStoreError = t.javaClass.simpleName + ": " + t.message
            false
        }
    }

    private fun newest(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val collection = collection()
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf(relativePath(), "rimetmock-%$EXT"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            }
        }.getOrNull()
    }

    private fun find(context: Context, name: String): Uri? {
        val collection = collection()
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath(), name),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    private fun collection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun relativePath(): String = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/"
}
