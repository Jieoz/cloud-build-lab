package com.jiesa.xvideocatcher

import android.app.Application
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Probe output sink.
 *
 * Two destinations, both best-effort:
 *  - XposedBridge.log, readable from the LSPosed manager log screen.
 *  - a JSONL file inside the *hooked* app's own cache dir, so `adb shell run-as`
 *    or a root shell can pull it without granting the module storage permissions.
 *
 * Nothing here may throw into the host app: a probe that crashes X is worse than
 * a probe that logs nothing.
 */
object ProbeLog {

    private const val TAG = "XVideoCatcher"
    private const val FILE_NAME = "xvc-probe.jsonl"

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var sink: File? = null

    @Volatile
    private var sinkResolved = false

    fun line(message: String) {
        val text = "[$TAG] $message"
        runCatching { XposedBridge.log(text) }
        runCatching { appendFile(text) }
    }

    fun error(message: String, t: Throwable) {
        runCatching { XposedBridge.log("[$TAG] $message: ${t.javaClass.name}: ${t.message}") }
        runCatching { XposedBridge.log(t) }
    }

    /**
     * Records a candidate media URL together with the call site. The stack trace is
     * the actually useful part: it names the (obfuscated) classes that fetch media,
     * which is what a later, more targeted hook needs.
     */
    fun candidate(source: String, url: String, stack: Array<StackTraceElement>) {
        val json = StringBuilder()
        json.append('{')
        json.append("\"ts\":\"").append(stamp.format(Date())).append("\",")
        json.append("\"source\":\"").append(escape(source)).append("\",")
        json.append("\"url\":\"").append(escape(url)).append("\",")
        json.append("\"stack\":[")
        // Skip our own frames, keep a shallow slice: enough to identify the caller
        // without flooding the log on every media segment request.
        val frames = stack
            .filterNot { it.className.startsWith("com.jiesa.xvideocatcher") }
            .filterNot { it.className.startsWith("de.robv.android.xposed") }
            .take(12)
        frames.forEachIndexed { index, frame ->
            if (index > 0) json.append(',')
            json.append('"').append(escape("${frame.className}.${frame.methodName}")).append('"')
        }
        json.append("]}")
        line(json.toString())
    }

    private fun appendFile(text: String) {
        val target = resolveSink() ?: return
        target.appendText(text + "\n")
    }

    private fun resolveSink(): File? {
        if (sinkResolved) return sink
        synchronized(this) {
            if (sinkResolved) return sink
            sinkResolved = true
            sink = runCatching {
                val app = currentApplication() ?: return@runCatching null
                File(app.cacheDir, FILE_NAME)
            }.getOrNull()
            return sink
        }
    }

    /**
     * AndroidAppHelper is Xposed-only API; reflection keeps this file compilable and
     * testable without the framework present.
     */
    private fun currentApplication(): Application? = runCatching {
        val helper = Class.forName("android.app.AndroidAppHelper")
        helper.getMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    private fun escape(raw: String): String {
        val out = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(' ') else out.append(c)
            }
        }
        return out.toString()
    }
}
