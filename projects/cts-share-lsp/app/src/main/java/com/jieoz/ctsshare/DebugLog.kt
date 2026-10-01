package com.jieoz.ctsshare

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
 * The hooked app (Google) writes the file itself; it runs under Google's UID and cannot
 * touch the module app's storage.
 *
 * Channel order is load-bearing and must NOT be changed: MediaStore FIRST on API 29+, because a
 * MediaStore-registered file is indexed and shows up immediately in the system Downloads / Files
 * app (which is how the "查看日志" button surfaces it — an in-app ContentResolver read is
 * owner-filtered on Android 11+ and would never see Google's row). Direct-file + app-external
 * are fallbacks only. A prior port (DiagSink) wrote direct-file first, which is invisible to the
 * Downloads app until a media scan — that regression is why the log "disappeared".
 *
 * Switch OFF => [line] returns immediately; nothing is written and no marker file is left behind.
 */
object DebugLog {
    const val TAG = "CTSShareLSP"
    const val DIR_NAME = "CtsShare"
    private const val EXT = ".txt"
    private val sessionSuffix: String = UUID.randomUUID().toString().substring(0, 6)

    fun fileName(now: Date = Date()): String =
        "ctsshare-${SimpleDateFormat("yyyyMMdd", Locale.US).format(now)}-${Process.myPid()}-$sessionSuffix$EXT"

    @Volatile
    private var enabled: Boolean = false

    private val pending = ArrayDeque<String>()

    @Volatile
    private var host: Context? = null

    fun bind(context: Context) {
        host = context.applicationContext ?: context
        val queued = synchronized(pending) { List(pending.size) { pending.removeFirst() } }
        queued.forEach { if (!queue.offer(it)) dropped.incrementAndGet() }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    val isOn: Boolean get() = enabled

    fun line(message: String, always: Boolean = false) {
        // OFF = one volatile read and an immediate return. No timestamp, no Log.i, no framework
        // binder call, no queue, no MediaStore. This is the load-bearing part of the switch: with
        // it off, logging costs nothing and writes no file. Do not move work above this line.
        if (!enabled && !always) return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val row = "$stamp pid=${Process.myPid()} $message\n"
        // Never touch disk / MediaStore / the framework binder on the caller's thread: callers are
        // Google's caption parser and UI thread. A synchronous MediaStore append per line (0.1-0.2)
        // stalled a 327-cue srv3 parse for ~6 s, so captions appeared late or not at all.
        if (host == null) {
            synchronized(pending) { pending.addLast(row) }
            return
        }
        if (!queue.offer(row)) dropped.incrementAndGet()
    }

    private const val QUEUE_CAP = 20_000
    private val queue = java.util.concurrent.LinkedBlockingQueue<String>(QUEUE_CAP)
    private val dropped = java.util.concurrent.atomic.AtomicInteger()

    // Single background writer: drains everything queued and appends it as ONE write.
    private val writer = Thread({
        val batch = ArrayList<String>(256)
        while (true) {
            try {
                batch.add(queue.take())
                queue.drainTo(batch, 2_000)
                val lost = dropped.getAndSet(0)
                if (lost > 0) batch.add("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} pid=${Process.myPid()} log-dropped $lost lines (queue full)\n")
                write(batch.joinToString(""))
                batch.clear()
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return@Thread
            } catch (_: Throwable) {
                batch.clear()
            }
        }
    }, "CtsShare-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }

    @Volatile
    private var reportedSink: String? = null

    @Volatile
    private var mediaStoreError: String? = null

    @Volatile
    private var downloadError: String? = null

    private fun write(row: String) {
        val context = host ?: return
        val main = isMainProcess(context)
        val ms = if (main && Build.VERSION.SDK_INT >= 29) appendMediaStore(context, row) else false
        val file = if (main && !ms) appendFile(row) else null
        if (file != null) registerDownload(context, file)
        val summary = buildString {
            if (!main) {
                append("public log skipped (not target process)")
            } else {
                append(if (ms) "mediastore -> ${relativePath()}${fileName()}" else "mediastore FAILED (${mediaStoreError ?: "insert/open returned false"})")
                append(if (file != null) "; download-file -> ${file.absolutePath}" else if (!ms) "; download-file FAILED" else "")
                if (!ms) append(if (downloadError == null) "; download-manager registered" else "; download-manager FAILED ($downloadError)")
            }
        }
        if (summary != reportedSink) {
            reportedSink = summary
            runCatching { CtsShareModule.framework.log(Log.INFO, TAG, "log file: $summary") }
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
        val latest = dir.listFiles { f -> f.name.startsWith("ctsshare-") && f.name.endsWith(EXT) }
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

    private fun isMainProcess(context: Context): Boolean {
        val name = runCatching {
            val thread = Class.forName("android.app.ActivityThread")
            thread.getMethod("currentProcessName").invoke(null) as? String
        }.getOrNull()
        // Google's Circle to Search runs in the ":googleapp" subprocess, not the main process.
        return name == null || name == Constants.TARGET_PROCESS
    }

    private fun publicDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private fun appendFile(row: String): File? = runCatching {
        val dir = File(publicDir(), DIR_NAME)
        dir.mkdirs()
        val f = File(dir, fileName())
        f.appendText(row)
        f
    }.getOrNull()

    private fun registerDownload(context: Context, file: File) {
        downloadError = null
        try {
            val managerClass = Class.forName("android.app.DownloadManager")
            val service = context.getSystemService(Context.DOWNLOAD_SERVICE)
                ?: run { downloadError = "service null"; return }
            val uri = Uri.fromFile(file)
            val queryClass = Class.forName("android.app.DownloadManager\$Query")
            val query = queryClass.getConstructor().newInstance()
            val cursor = managerClass.getMethod("query", queryClass).invoke(service, query) as android.database.Cursor
            val localIndex = cursor.getColumnIndex("local_uri")
            val exists = cursor.use {
                if (localIndex < 0) return@use false
                while (it.moveToNext()) if (it.getString(localIndex) == uri.toString()) return@use true
                false
            }
            if (exists) return
            val id = managerClass.getMethod(
                "addCompletedDownload",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            ).invoke(
                service,
                file.name,
                "CtsShare log",
                true,
                "text/plain",
                file.absolutePath,
                file.length(),
                true
            ) as Long
            if (id <= 0L) downloadError = "addCompletedDownload returned $id"
        } catch (t: Throwable) {
            downloadError = t.javaClass.simpleName + ": " + (t.cause?.message ?: t.message)
        }
    }

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
                arrayOf(relativePath(), "ctsshare-%$EXT"),
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
