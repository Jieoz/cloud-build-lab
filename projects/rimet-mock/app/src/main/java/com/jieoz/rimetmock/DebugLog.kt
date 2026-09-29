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
 * Same sink as the sibling modules: the hooked app writes the file itself.
 *
 * DingTalk cannot resolve a ContentProvider owned by this module, so the host process writes
 * Download/RimetMock/. The name is the date plus a random suffix chosen once per process.
 * Lines logged before the host Application binds are queued and flushed after [bind].
 *
 * EVERYTHING in here is gated by [setEnabled] (default OFF): when the switch is off there is
 * no queue, no thread and no disk write — the only cost is one boolean check per call site.
 * Install-time diagnostics never come through here; they go to the LSPosed manager log.
 */
object DebugLog {
    const val TAG = "RimetMock"
    const val DIR_NAME = "RimetMock"
    private const val EXT = ".txt"
    private val sessionSuffix: String = UUID.randomUUID().toString().substring(0, 6)

    @Volatile
    private var host: Context? = null

    @Volatile
    private var enabled: Boolean = false

    private val pending = ArrayDeque<String>()

    fun fileName(now: Date = Date()): String =
        "rimetmock-${SimpleDateFormat("yyyyMMdd", Locale.US).format(now)}-$sessionSuffix$EXT"

    fun bind(context: Context) {
        host = context.applicationContext ?: context
        val queued = synchronized(pending) { List(pending.size) { pending.removeFirst() } }
        queued.forEach { write(it) }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** No-op unless the switch is on: one boolean read when off. */
    fun line(message: String) {
        if (!enabled) return
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

    private fun write(row: String) {
        val context = host ?: return
        if (Build.VERSION.SDK_INT >= 29 && appendMediaStore(context, row)) return
        appendAppExternal(context, row)
    }

    /** Permission-free fallback (app-specific dir) — always writable, works below API 29 too. */
    private fun appendAppExternal(context: Context, row: String) {
        runCatching {
            val base = context.getExternalFilesDir(null) ?: return
            val dir = File(base, DIR_NAME)
            dir.mkdirs()
            File(dir, fileName()).appendText(row)
        }
    }

    private fun appendMediaStore(context: Context, row: String): Boolean = runCatching {
        val resolver = context.contentResolver
        val name = fileName()
        val uri = find(context, name) ?: resolver.insert(collection(), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
        }) ?: return false
        resolver.openOutputStream(uri, "wa")?.use { it.write(row.toByteArray()) } != null
    }.getOrDefault(false)

    private fun find(context: Context, name: String): Uri? {
        return context.contentResolver.query(
            collection(),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath(), name),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection(), cursor.getLong(0)) else null
        }
    }

    private fun collection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun relativePath(): String = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/"
}
