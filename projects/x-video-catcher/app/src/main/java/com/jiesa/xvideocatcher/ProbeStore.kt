package com.jiesa.xvideocatcher

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Owns the log file inside *this module's* private storage.
 *
 * The point of routing records here rather than leaving them in X's cache dir is that
 * this directory is reachable without root: the module can read it back and hand it to
 * a share intent.
 */
class ProbeStore(private val context: Context) {

    private val lock = Any()

    val logFile: File
        get() = File(context.filesDir, FILE_NAME)

    /**
     * Appends a batch of already-formatted JSONL records.
     *
     * Rotation is by size and deliberately crude — one generation is kept. Playback
     * generates records continuously, and an unbounded file would eventually fill
     * storage and produce an export too large to send.
     */
    fun append(lines: List<String>): Int {
        if (lines.isEmpty()) return 0
        synchronized(lock) {
            val target = logFile
            try {
                if (target.length() > MAX_BYTES) rotate(target)
                target.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
            } catch (e: IOException) {
                // Losing a log line must never surface as a failure in the hooked app.
                return 0
            }
            return lines.size
        }
    }

    fun lineCount(): Int = synchronized(lock) {
        val target = logFile
        if (!target.exists()) return 0
        return runCatching { target.useLines { seq -> seq.count { it.isNotBlank() } } }.getOrDefault(0)
    }

    fun sizeBytes(): Long = synchronized(lock) { logFile.let { if (it.exists()) it.length() else 0L } }

    fun clear(): Boolean = synchronized(lock) {
        val target = logFile
        val previous = File(target.parentFile, "$FILE_NAME.1")
        runCatching { previous.delete() }
        return runCatching { if (target.exists()) target.delete() else true }.getOrDefault(false)
    }

    /**
     * Materialises the export the user actually shares. It lives in a dedicated
     * `export/` subdir because that is the only path published through FileProvider —
     * the live log file itself is never exposed, so a share target cannot hold a
     * handle on the file the hook is still appending to.
     */
    fun buildExport(header: List<String>): File? = synchronized(lock) {
        val dir = File(context.filesDir, EXPORT_DIR).apply { mkdirs() }
        val out = File(dir, EXPORT_NAME)
        return runCatching {
            out.bufferedWriter().use { writer ->
                header.forEach { line ->
                    writer.write(line)
                    writer.write("\n")
                }
                val source = logFile
                if (source.exists()) {
                    source.forEachLine { line ->
                        if (line.isNotBlank()) {
                            writer.write(line)
                            writer.write("\n")
                        }
                    }
                }
            }
            out
        }.getOrNull()
    }

    private fun rotate(target: File) {
        val previous = File(target.parentFile, "$FILE_NAME.1")
        runCatching { previous.delete() }
        runCatching { target.renameTo(previous) }
    }

    companion object {
        const val FILE_NAME = "xvc-probe.jsonl"
        const val EXPORT_DIR = "export"
        const val EXPORT_NAME = "xvc-probe-export.jsonl"
        private const val MAX_BYTES = 2L * 1024 * 1024
    }
}
