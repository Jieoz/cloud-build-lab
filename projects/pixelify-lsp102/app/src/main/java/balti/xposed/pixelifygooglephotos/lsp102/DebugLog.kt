package balti.xposed.pixelifygooglephotos.lsp102

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
 * Same sink as X Video Catcher: the hooked app writes the file itself.
 *
 * Photos cannot resolve a ContentProvider owned by this module, so the host process writes
 * Download/PixelifyLsp102/. The name is the date plus a random suffix chosen once per process.
 */
object DebugLog {
    const val TAG = "PixelifyLsp102"
    const val DIR_NAME = "PixelifyLsp102"
    private const val EXT = ".txt"
    private val sessionSuffix: String = UUID.randomUUID().toString().substring(0, 6)

    @Volatile
    private var host: Context? = null

    @Volatile
    private var enabled: Boolean = false

    private val pending = ArrayDeque<String>()

    fun fileName(now: Date = Date()): String =
        "pixelify-lsp102-${SimpleDateFormat("yyyyMMdd", Locale.US).format(now)}-$sessionSuffix$EXT"

    fun bind(context: Context) {
        host = context.applicationContext ?: context
        val queued = synchronized(pending) { List(pending.size) { pending.removeFirst() } }
        queued.forEach { write(it) }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun line(message: String, always: Boolean = false) {
        if (!enabled && !always) return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val row = "$stamp pid=${Process.myPid()} $message\n"
        Log.i(TAG, message)
        runCatching { PixelifyModule.framework.log(Log.INFO, TAG, message) }
        if (host == null) {
            synchronized(pending) { pending.addLast(row) }
            return
        }
        write(row)
    }

    private fun write(row: String) {
        val context = host ?: return
        if (Build.VERSION.SDK_INT >= 29 && appendMediaStore(context, row)) return
        appendFile(row)
        appendAppExternal(context, row)
    }

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
        val latest = dir.listFiles { f -> f.name.startsWith("pixelify-lsp102-") && f.name.endsWith(EXT) }
            ?.maxByOrNull { it.lastModified() } ?: return ""
        return runCatching { latest.readText() }.getOrDefault("")
    }

    private fun appendAppExternal(context: Context, row: String) {
        runCatching {
            val base = context.getExternalFilesDir(null) ?: return
            val dir = File(base, DIR_NAME)
            dir.mkdirs()
            File(dir, fileName()).appendText(row)
        }
    }

    private fun publicDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private fun appendFile(row: String) {
        runCatching {
            val dir = File(publicDir(), DIR_NAME)
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

    private fun newest(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val collection = collection()
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf(relativePath(), "pixelify-lsp102-%$EXT"),
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
