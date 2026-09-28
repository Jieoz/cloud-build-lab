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
    private lateinit var tvFile: TextView
    private lateinit var tvRange: TextView
    private lateinit var tvStatus: TextView
    private lateinit var slider: RangeSlider
    private lateinit var progress: ProgressBar

    private var sourceUri: Uri? = null
    private var displayName: String = "audio"
    private var durationMs: Long = 0L
    private var player: MediaPlayer? = null

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
        tvFile = findViewById(R.id.tvFile)
        tvRange = findViewById(R.id.tvRange)
        tvStatus = findViewById(R.id.tvStatus)
        slider = findViewById(R.id.slider)
        progress = findViewById(R.id.progress)

        btnPick.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }

        slider.addOnChangeListener { _, _, _ -> updateRangeLabel() }
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
        // Slider runs on a fixed 0..1000 permille scale (see XML). Binding it directly
        // to raw millisecond floats loses precision on long tracks and makes the two
        // thumbs cross by a sub-pixel sliver mid-drag, which throws in validateValues
        // and hard-crashes. Keep the slider range constant; map to ms in code.
        slider.setValues(0f, 1000f)
        slider.isEnabled = true
        btnPlay.isEnabled = true
        btnCut.isEnabled = true
        tvStatus.text = ""
        updateRangeLabel()
    }

    private fun updateRangeLabel() {
        val v = slider.values
        if (v.size == 2) {
            val startMs = permilleToMs(v[0])
            val endMs = permilleToMs(v[1])
            tvRange.text = "保留区间：${formatMs(startMs)} — ${formatMs(endMs)}" +
                    "  (共 ${formatMs(endMs - startMs)})"
        }
    }

    /** Maps a 0..1000 permille slider position to milliseconds in the current track. */
    private fun permilleToMs(permille: Float): Long =
        (permille / 1000f * durationMs).toLong().coerceIn(0L, durationMs)

    private fun previewSelection() {
        val uri = sourceUri ?: return
        val v = slider.values
        val startMs = permilleToMs(v[0])
        val endMs = permilleToMs(v[1])
        stopPlayback()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(this, uri)
            mp.setOnPreparedListener {
                mp.seekTo(startMs.toInt())
                mp.start()
                btnStop.isEnabled = true
                io.execute {
                    while (player === mp && mp.isPlaying && mp.currentPosition < endMs) {
                        Thread.sleep(50)
                    }
                    if (player === mp) runOnUiThread { stopPlayback() }
                }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            tvStatus.text = "试听失败：${e.message}"
        }
    }

    private fun stopPlayback() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            it.release()
        }
        player = null
        btnStop.isEnabled = false
    }

    private fun doCut() {
        val uri = sourceUri ?: return
        val v = slider.values
        val startUs = permilleToMs(v[0]) * 1000
        val endUs = permilleToMs(v[1]) * 1000
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

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnPick.isEnabled = !busy
        btnCut.isEnabled = !busy
        btnPlay.isEnabled = !busy
        slider.isEnabled = !busy
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
