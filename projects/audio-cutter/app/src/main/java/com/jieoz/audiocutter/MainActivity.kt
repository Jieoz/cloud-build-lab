package com.jieoz.audiocutter

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.RangeSlider
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var btnPick: MaterialButton
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnCut: MaterialButton
    private lateinit var btnZoom: MaterialButton
    private lateinit var btnZoomReset: MaterialButton
    private lateinit var tvFile: TextView
    private lateinit var tvRange: TextView
    private lateinit var tvWindow: TextView
    private lateinit var tvStart: TextView
    private lateinit var tvEnd: TextView
    private lateinit var tvStatus: TextView
    private lateinit var slider: RangeSlider
    private lateinit var progress: ProgressBar

    private lateinit var startNudges: List<MaterialButton>
    private lateinit var endNudges: List<MaterialButton>

    private var sourceUri: Uri? = null
    private var displayName: String = "audio"
    private var durationMs: Long = 0L
    private var player: MediaPlayer? = null

    // Absolute selection (source of truth), in ms.
    private var selStartMs: Long = 0L
    private var selEndMs: Long = 0L

    // Visible window the slider maps onto (zoom). Full track by default.
    private var winStartMs: Long = 0L
    private var winEndMs: Long = 0L

    // Guards the slider listener against feedback loops during programmatic sync.
    private var syncing = false

    private val io = Executors.newSingleThreadExecutor()

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFilePicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPick = findViewById(R.id.btnPick)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnCut = findViewById(R.id.btnCut)
        btnZoom = findViewById(R.id.btnZoom)
        btnZoomReset = findViewById(R.id.btnZoomReset)
        tvFile = findViewById(R.id.tvFile)
        tvRange = findViewById(R.id.tvRange)
        tvWindow = findViewById(R.id.tvWindow)
        tvStart = findViewById(R.id.tvStart)
        tvEnd = findViewById(R.id.tvEnd)
        tvStatus = findViewById(R.id.tvStatus)
        slider = findViewById(R.id.slider)
        progress = findViewById(R.id.progress)

        startNudges = listOf(
            findViewById(R.id.btnStartMinus1),
            findViewById(R.id.btnStartMinus01),
            findViewById(R.id.btnStartPlus01),
            findViewById(R.id.btnStartPlus1),
        )
        endNudges = listOf(
            findViewById(R.id.btnEndMinus1),
            findViewById(R.id.btnEndMinus01),
            findViewById(R.id.btnEndPlus01),
            findViewById(R.id.btnEndPlus1),
        )

        btnPick.setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }

        // Keep the ScrollView from stealing the horizontal drag from the slider,
        // so a tap or drag on the bar always reaches the thumbs.
        slider.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false // let the slider handle the touch itself
        }

        slider.addOnChangeListener { _, _, fromUser ->
            if (syncing || !fromUser) return@addOnChangeListener
            val v = slider.values
            if (v.size == 2) {
                selStartMs = permilleToMs(v[0])
                selEndMs = permilleToMs(v[1])
                refreshLabels()
            }
        }

        // Fine nudges: adjust the absolute selection by a fixed delta, clamped.
        findViewById<MaterialButton>(R.id.btnStartMinus1).setOnClickListener { nudgeStart(-1000) }
        findViewById<MaterialButton>(R.id.btnStartMinus01).setOnClickListener { nudgeStart(-100) }
        findViewById<MaterialButton>(R.id.btnStartPlus01).setOnClickListener { nudgeStart(100) }
        findViewById<MaterialButton>(R.id.btnStartPlus1).setOnClickListener { nudgeStart(1000) }
        findViewById<MaterialButton>(R.id.btnEndMinus1).setOnClickListener { nudgeEnd(-1000) }
        findViewById<MaterialButton>(R.id.btnEndMinus01).setOnClickListener { nudgeEnd(-100) }
        findViewById<MaterialButton>(R.id.btnEndPlus01).setOnClickListener { nudgeEnd(100) }
        findViewById<MaterialButton>(R.id.btnEndPlus1).setOnClickListener { nudgeEnd(1000) }

        btnZoom.setOnClickListener { zoomToSelection() }
        btnZoomReset.setOnClickListener { resetWindow() }

        btnPlay.setOnClickListener { previewSelection() }
        btnStop.setOnClickListener { stopPlayback() }
        btnCut.setOnClickListener { doCut() }
    }

    private fun onFilePicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { /* transient grant is fine */ }

        sourceUri = uri
        displayName = queryName(uri)
        durationMs = queryDuration(uri)

        if (durationMs <= 0) {
            tvStatus.text = "无法读取时长，文件可能损坏或不受支持"
            return
        }

        tvFile.text = "$displayName  (${formatMs(durationMs)})"
        selStartMs = 0L
        selEndMs = durationMs
        winStartMs = 0L
        winEndMs = durationMs

        enableControls(true)
        syncSliderFromSelection()
        refreshLabels()
        tvStatus.text = ""
    }

    // ---- selection <-> slider mapping (within the current zoom window) ----

    /** Maps a 0..1000 permille slider position to absolute ms inside the window. */
    private fun permilleToMs(permille: Float): Long {
        val span = (winEndMs - winStartMs).coerceAtLeast(1L)
        return (winStartMs + permille / 1000f * span).toLong().coerceIn(0L, durationMs)
    }

    /** Maps an absolute ms position to 0..1000 permille inside the window. */
    private fun msToPermille(ms: Long): Float {
        val span = (winEndMs - winStartMs).coerceAtLeast(1L)
        return ((ms - winStartMs).toFloat() / span * 1000f).coerceIn(0f, 1000f)
    }

    private fun syncSliderFromSelection() {
        syncing = true
        val lo = msToPermille(selStartMs)
        val hi = msToPermille(selEndMs)
        // Guarantee strictly ordered, in-range values to avoid RangeSlider validation crash.
        val a = lo.coerceIn(0f, 1000f)
        val b = hi.coerceIn(0f, 1000f)
        slider.setValues(minOf(a, b), maxOf(a, b))
        syncing = false
    }

    private fun nudgeStart(deltaMs: Long) {
        // Keep at least 100ms selection; don't cross the end.
        selStartMs = (selStartMs + deltaMs).coerceIn(0L, selEndMs - 100)
        syncSliderFromSelection()
        refreshLabels()
    }

    private fun nudgeEnd(deltaMs: Long) {
        selEndMs = (selEndMs + deltaMs).coerceIn(selStartMs + 100, durationMs)
        syncSliderFromSelection()
        refreshLabels()
    }

    private fun zoomToSelection() {
        val sel = selEndMs - selStartMs
        if (sel <= 0) return
        // Pad the window by 15% on each side so both handles stay reachable.
        val pad = (sel * 0.15f).toLong().coerceAtLeast(200L)
        winStartMs = (selStartMs - pad).coerceAtLeast(0L)
        winEndMs = (selEndMs + pad).coerceAtMost(durationMs)
        if (winEndMs - winStartMs < 300) { // guard against a degenerate window
            winStartMs = (selStartMs - 200).coerceAtLeast(0L)
            winEndMs = (selEndMs + 200).coerceAtMost(durationMs)
        }
        syncSliderFromSelection()
        refreshLabels()
    }

    private fun resetWindow() {
        winStartMs = 0L
        winEndMs = durationMs
        syncSliderFromSelection()
        refreshLabels()
    }

    private fun refreshLabels() {
        tvRange.text = "保留区间：${formatMs(selStartMs)} — ${formatMs(selEndMs)}" +
                "  (共 ${formatMs(selEndMs - selStartMs)})"
        tvStart.text = formatMs(selStartMs)
        tvEnd.text = formatMs(selEndMs)
        tvWindow.text = if (winStartMs == 0L && winEndMs == durationMs) {
            "显示范围：全曲"
        } else {
            "显示范围：${formatMs(winStartMs)} — ${formatMs(winEndMs)}（已放大，拖动更精细）"
        }
    }

    private fun previewSelection() {
        val uri = sourceUri ?: return
        val startMs = selStartMs
        val endMs = selEndMs
        stopPlayback()
        val mp = MediaPlayer()
        player = mp
        try {
            // Any decode/IO error surfaces here instead of crashing the process.
            mp.setOnErrorListener { _, what, extra ->
                runOnUiThread {
                    if (player === mp) {
                        stopPlayback()
                        tvStatus.text = "试听失败（错误码 $what/$extra）"
                    }
                }
                true // handled; do not propagate
            }
            mp.setDataSource(this, uri)
            mp.setOnPreparedListener {
                // The user may have started another preview before this one
                // finished preparing; only drive the player that is still current.
                if (player !== mp) return@setOnPreparedListener
                runCatching {
                    mp.seekTo(startMs.toInt())
                    mp.start()
                    btnStop.isEnabled = true
                }
                io.execute {
                    // Guard EVERY MediaPlayer call: the main thread can release
                    // `mp` at any moment (new preview / stop / activity teardown).
                    // `player === mp` alone is a TOCTOU check — a release can land
                    // between the check and the call, so calls that would touch a
                    // released player are wrapped and end the loop on failure.
                    while (player === mp) {
                        val keepGoing = runCatching {
                            mp.isPlaying && mp.currentPosition < endMs
                        }.getOrDefault(false)
                        if (!keepGoing) break
                        Thread.sleep(50)
                    }
                    if (player === mp) runOnUiThread { if (player === mp) stopPlayback() }
                }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            if (player === mp) stopPlayback()
            tvStatus.text = "试听失败：${e.message}"
        }
    }

    private fun stopPlayback() {
        player?.let {
            // Detach first so the polling thread's `player === mp` check fails
            // before we release, closing the race window.
            player = null
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        btnStop.isEnabled = false
    }

    private fun doCut() {
        val uri = sourceUri ?: return
        val startUs = selStartMs * 1000
        val endUs = selEndMs * 1000
        if (endUs - startUs < 100_000) {
            tvStatus.text = "选段太短（至少 0.1 秒）"
            return
        }

        setBusy(true)
        tvStatus.text = "正在剪切…"
        progress.progress = 0

        io.execute {
            try {
                val outName = "${baseName(displayName)}_cut_${System.currentTimeMillis()}.m4a"
                val outUri = createOutput(outName)
                    ?: throw IllegalStateException("无法创建输出文件")

                contentResolver.openFileDescriptor(uri, "r").use { inPfd ->
                    contentResolver.openFileDescriptor(outUri, "w").use { outPfd ->
                        AudioTrimmer.trim(
                            inPfd!!.fileDescriptor,
                            outPfd!!.fileDescriptor,
                            startUs, endUs
                        ) { f ->
                            runOnUiThread { progress.progress = (f * 100).toInt() }
                        }
                    }
                }
                runOnUiThread {
                    setBusy(false)
                    tvStatus.text = "已保存到 音乐/AudioCutter/$outName"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    tvStatus.text = "剪切失败：${e.message}"
                }
            }
        }
    }

    /** Writes into MediaStore (Music/AudioCutter) on Q+, or public Music dir below. */
    private fun createOutput(name: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + "/AudioCutter")
            }
            contentResolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            )
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "AudioCutter"
            )
            dir.mkdirs()
            Uri.fromFile(File(dir, name))
        }
    }

    private fun enableControls(on: Boolean) {
        slider.isEnabled = on
        btnPlay.isEnabled = on
        btnCut.isEnabled = on
        btnZoom.isEnabled = on
        btnZoomReset.isEnabled = on
        (startNudges + endNudges).forEach { it.isEnabled = on }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnPick.isEnabled = !busy
        enableControls(!busy)
    }

    private fun queryName(uri: Uri): String {
        var name = "audio"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx) ?: name
                }
            }
        return name
    }

    private fun queryDuration(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    private fun baseName(n: String) = n.substringBeforeLast('.', n)

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        val cs = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(m, s, cs)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        io.shutdownNow()
    }
}
