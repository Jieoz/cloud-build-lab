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
 * Writing needs a [Context], and there is none when the module attaches: hooks are
 * installed from `handleLoadPackage`, which the framework calls *before* the host's
 * Application object is created. Records produced in that window are therefore
 * retained until [bindContext] supplies a Context, rather than discarded — dropping
 * them is what made the log file never appear at all.
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

    /** Cap on records held while no Context exists yet, or while writes are failing. */
    private const val RETAIN_CAPACITY = 512

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)

    /** Records accepted but not yet on disk. Guarded by [retainLock]. */
    private val retained = ArrayDeque<String>()
    private val retainLock = Any()

    @Volatile
    private var writerStarted = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var dropped = 0L

    /** Set once a write actually succeeded, so a dead sink can be reported as such. */
    @Volatile
    private var sinkVerified = false

    /**
     * Supplies the host Context once the framework has created its Application, and
     * immediately writes everything collected before that point — including the attach
     * record, which is what makes the log file's existence proof that the module loaded.
     */
    fun bindContext(context: Context) {
        appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        flushNow()
    }

    /**
     * Mirrors a line into the LSPosed log. The Xposed API is `compileOnly`, so under
     * unit tests the class is absent and this throws NoClassDefFoundError — an Error,
     * not an Exception. Catching Throwable is therefore required, and deliberate: the
     * file sink is the real destination and must not depend on the framework log.
     */
    private fun bridgeLog(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // Intentionally ignored; see above.
        }
    }

    fun line(message: String) {
        bridgeLog { XposedBridge.log("[$TAG] $message") }
        enqueue(ProbeRecord.note(stamp.format(Date()), message))
    }

    fun error(message: String, t: Throwable) {
        bridgeLog { XposedBridge.log("[$TAG] $message: ${t.javaClass.name}: ${t.message}") }
        bridgeLog { XposedBridge.log(t) }
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
        bridgeLog { XposedBridge.log("[$TAG] $record") }
        enqueue(record)
    }

    /** Attempts to put everything collected so far on disk, in the calling thread. */
    fun flushNow() {
        ensureWriter()
        val batch = ArrayList<String>(BATCH_SIZE)
        queue.drainTo(batch, BATCH_SIZE)
        writeBatch(batch)
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
                batch.clear()
                val first = queue.poll(FLUSH_MS, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    queue.drainTo(batch, BATCH_SIZE - 1)
                }
                // Retained records must be retried even when nothing new arrived,
                // otherwise an idle probe never writes what it collected at attach.
                if (batch.isNotEmpty() || hasRetained()) writeBatch(batch)
            } catch (t: Throwable) {
                // Never let the writer thread die and silently stop collecting.
                bridgeLog { XposedBridge.log("[$TAG] writer recovered: ${t.javaClass.name}") }
            }
        }
    }

    private fun hasRetained(): Boolean = synchronized(retainLock) { retained.isNotEmpty() }

    /**
     * Adds [newRecords] to the retained set and tries to write the whole set. On
     * failure — including "no Context yet" — records stay retained (bounded, oldest
     * dropped first) so a later attempt can still deliver them.
     */
    private fun writeBatch(newRecords: List<String>): Boolean {
        // Held in a local val so the null check narrows the type for the write below.
        val context: Context = appContext ?: currentApplication() ?: run {
            synchronized(retainLock) {
                retained.addAll(newRecords)
                trimRetainedLocked()
            }
            return false
        }

        val toWrite: List<String>
        synchronized(retainLock) {
            retained.addAll(newRecords)
            trimRetainedLocked()
            if (retained.isEmpty()) return true
            toWrite = retained.toList()
            retained.clear()
        }

        val ok = ProbeSink.append(context, toWrite)
        if (ok) {
            if (!sinkVerified) {
                sinkVerified = true
                bridgeLog { XposedBridge.log("[$TAG] sink ok -> ${ProbeSink.displayPath()}") }
            }
        } else {
            synchronized(retainLock) {
                // Restore order: the failed batch precedes anything queued since.
                val merged = ArrayList<String>(toWrite.size + retained.size)
                merged.addAll(toWrite)
                merged.addAll(retained)
                retained.clear()
                retained.addAll(merged)
                trimRetainedLocked()
            }
            bridgeLog { XposedBridge.log("[$TAG] sink write FAILED -> ${ProbeSink.displayPath()}") }
        }
        return ok
    }

    private fun trimRetainedLocked() {
        while (retained.size > RETAIN_CAPACITY) {
            retained.removeFirst()
            dropped++
        }
    }

    /**
     * AndroidAppHelper is Xposed-only API; reflection keeps this file compilable and
     * testable without the framework present. Used only as a fallback — it returns
     * null during early attach, which is exactly when [bindContext] has not run yet.
     */
    private fun currentApplication(): Application? = runCatching {
        val helper = Class.forName("android.app.AndroidAppHelper")
        helper.getMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    /** Test seam: resets process-global state between cases. */
    internal fun resetForTest() {
        appContext = null
        sinkVerified = false
        dropped = 0
        queue.clear()
        synchronized(retainLock) { retained.clear() }
    }
}
