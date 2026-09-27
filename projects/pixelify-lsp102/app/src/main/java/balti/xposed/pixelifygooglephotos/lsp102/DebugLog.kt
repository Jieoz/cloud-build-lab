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

/**
 * File log the user can share back.
 *
 * The hook runs inside Google Photos and writes Download/PixelifyLsp102/.
 * The module UI reads that same public file and shares a copy.
 */
object DebugLog {
    const val TAG = "PixelifyLsp102"
    const val DIR_NAME = "PixelifyLsp102"
    const val FILE_NAME = "pixelify-lsp102-debug.log"

    @Volatile
    private var host: Context? = null

    @Volatile
    private var enabled: Boolean = true

    fun bind(context: Context) {
        host = context.applicationContext
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun line(message: String) {
        if (!enabled) return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val row = "$stamp pid=${Process.myPid()} $message\n"
        Log.i(TAG, message)
        runCatching { PixelifyModule.framework.log(Log.INFO, TAG, message) }
        val context = host
        if (context != null && Build.VERSION.SDK_INT >= 29) {
            appendMediaStore(context, row)
        } else {
            appendFile(row)
        }
    }

    fun read(context: Context): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = find(context) ?: return ""
            return runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
        }
        val target = publicFile()
        if (!target.exists()) return ""
        return runCatching { target.readText() }.getOrDefault("")
    }

    fun clear(context: Context) {
        if (Build.VERSION.SDK_INT >= 29) {
            find(context)?.let { context.contentResolver.delete(it, null, null) }
        } else {
            runCatching { publicFile().delete() }
        }
    }

    private fun publicFile(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$DIR_NAME/$FILE_NAME")

    private fun appendFile(row: String) {
        runCatching {
            val target = publicFile()
            target.parentFile?.mkdirs()
            target.appendText(row)
        }
    }

    private fun appendMediaStore(context: Context, row: String) {
        runCatching {
            val resolver = context.contentResolver
            val uri = find(context) ?: resolver.insert(collection(), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
            }) ?: return
            resolver.openOutputStream(uri, "wa")?.use { it.write(row.toByteArray()) }
        }
    }

    private fun find(context: Context): Uri? {
        val collection = collection()
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath(), FILE_NAME),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    private fun collection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun relativePath(): String = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/"
}
