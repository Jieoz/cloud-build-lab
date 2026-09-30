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
    // NO per-process random suffix: DingTalk runs 3 processes and each was creating its OWN
    // file, so the user always saw a fraction of the picture (evidence: his file had only
    // package-ready + onCreate while hooks fired in other processes). One date-suffixed file;
    // appends use O_APPEND semantics so concurrent processes never corrupt each other's lines.
    @Volatile
    private var host: Context? = null

    @Volatile
    private var enabled: Boolean = false

    private val pending = ArrayDeque<String>()

    fun fileName(now: Date = Date()): String =
        "rimetmock-${SimpleDateFormat("yyyyMMdd", Locale.US).format(now)}$EXT"

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
        // MediaStore first: that is the row the Downloads app and "查看调试日志" actually open.
        // Direct Download, then the host's external-files dir, only if MediaStore fails.
        // The failure reason is reported once so a silent miss is visible in the LSPosed log.
        // Channel order is the verified pixelify contract: MediaStore first so the file is
        // indexed under Download/RimetMock/ and the "查看调试日志" button can open it.
        // Direct Download and the app-external dir are fallbacks, not the place the user reads.
        // A previous build wrote ONLY app-external; that file exists but never shows in Downloads.
        val ms = if (Build.VERSION.SDK_INT >= 29) appendMediaStore(context, row) else false
        val file = if (!ms) appendFile(row) else false
        val ext = if (!ms && !file) appendAppExternal(context, row) else false
        val summary = when {
            ms -> "mediastore -> ${relativePath()}${fileName()}"
            file -> "download-file -> ${File(publicDir(), "$DIR_NAME/${fileName()}").absolutePath}"
            ext -> "app-external -> ${appExternalTarget(context)}"
            else -> "FAILED all sinks (mediastore=${mediaStoreError ?: "insert/open returned false"})"
        }
        if (summary != reportedSink) {
            reportedSink = summary
            runCatching { RimetMockModule.framework.log(Log.INFO, TAG, "log file: $summary") }
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

    private fun appendFile(row: String): Boolean = runCatching {
        val dir = File(publicDir(), DIR_NAME)
        dir.mkdirs()
        val f = File(dir, fileName())
        f.appendText(row)
        // A raw File write into public Download is on disk but INVISIBLE to file managers and the
        // Files app until the media database indexes it. DingTalk (host) has no MANAGE_EXTERNAL,
        // so MediaStore.insert above failed and we landed here — meaning nothing indexed the file.
        // Kick MediaScanner so MT Manager / Files show it. Async, no permission needed.
        host?.let { ctx ->
            runCatching {
                android.media.MediaScannerConnection.scanFile(
                    ctx, arrayOf(f.absolutePath), arrayOf("text/plain"), null
                )
            }
        }
        true
    }.getOrDefault(false)

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
