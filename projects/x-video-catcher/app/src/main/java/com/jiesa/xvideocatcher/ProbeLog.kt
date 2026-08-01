package com.jiesa.xvideocatcher

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Probe output sink, running inside the *hooked* app's process.
 *
 * Records go to two destinations, both best-effort:
 *  - `XposedBridge.log`, readable from the LSPosed manager log screen.
 *  - a JSONL file in shared Downloads via [ProbeSink], written by the host process
 *    itself so it needs no permission and no cross-process provider lookup.
 *
 * Nothing here may throw into the host app: a probe that crashes X is worse than a
 * probe that logs nothing.
 */
object ProbeLog {

    private const val TAG = "XVideoCatcher"

    /**
     * A file write per URL would be unacceptable on the network hot path, so records
     * are queued and flushed by one background thread. The queue is bounded and drops
     * on overflow — losing records under a flood is correct; blocking X's threads or
     * growing without limit is not.
     */
    private const val QUEUE_CAPACITY = 512
    private const val BATCH_SIZE = 64
    private const val FLUSH_MS = 1500L

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)

    @Volatile
    private var writerStarted = false

    @Volatile
    private var dropped = 0L

    /** Set once a write actually succeeded, so a dead sink can be reported as such. */
    @Volatile
    private var sinkVerified = false

    fun line(message: String) {
        runCatching { XposedBridge.log("[$TAG] $message") }
        enqueue(ProbeRecord.note(stamp.format(Date()), message))
    }

    fun error(message: String, t: Throwable) {
        runCatching { XposedBridge.log("[$TAG] $message: ${t.javaClass.name}: ${t.message}") }
        runCatching { XposedBridge.log(t) }
        enqueue(ProbeRecord.note(stamp.format(Date()), "$message: ${t.javaClass.name}: ${t.message}"))
    }

    /**
     * Records a candidate media URL together with the call site. The stack trace is
     * the actually useful part: it names the (obfuscated) classes that fetch media,
     * which is what a later, more targeted hook needs.
     */
    fun candidate(source: String, url: String, stack: Array<StackTraceElement>) {
        val record = ProbeRecord.candidate(
            timestamp = stamp.format(Date()),
            source = source,
            url = url,
            stack = ProbeRecord.relevantFrames(stack),
        )
        runCatching { XposedBridge.log("[$TAG] $record") }
        enqueue(record)
    }

    /**
     * Flushes pending records immediately. Called right after attach so the log file
     * exists as soon as the module loads: an empty Downloads folder then means "not
     * loaded", instead of being indistinguishable from "loaded but sink broken".
     */
    fun flushNow() {
        ensureWriter()
        val batch = ArrayList<String>(BATCH_SIZE)
        queue.drainTo(batch, BATCH_SIZE)
        if (batch.isNotEmpty()) flush(batch)
    }

    private fun enqueue(record: String) {
        ensureWriter()
        if (!queue.offer(record)) dropped++
    }

    private fun ensureWriter() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
            Thread({ drainLoop() }, "xvc-probe-writer").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    private fun drainLoop() {
        val batch = ArrayList<String>(BATCH_SIZE)
        while (true) {
            try {
                val first = queue.poll(FLUSH_MS, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    queue.drainTo(batch, BATCH_SIZE - 1)
                }
                if (batch.isNotEmpty()) {
                    flush(batch)
                    batch.clear()
                }
            } catch (t: Throwable) {
                // Never let the writer thread die and silently stop collecting.
                batch.clear()
                runCatching { XposedBridge.log("[$TAG] writer recovered: ${t.javaClass.name}") }
            }
        }
    }

    private fun flush(batch: List<String>) {
        val context: Context = currentApplication() ?: return
        val ok = ProbeSink.append(context, batch)
        if (ok) {
            if (!sinkVerified) {
                sinkVerified = true
                runCatching {
                    XposedBridge.log("[$TAG] sink ok -> ${ProbeSink.displayPath()}")
                }
            }
        } else {
            runCatching { XposedBridge.log("[$TAG] sink write FAILED -> ${ProbeSink.displayPath()}") }
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
}
