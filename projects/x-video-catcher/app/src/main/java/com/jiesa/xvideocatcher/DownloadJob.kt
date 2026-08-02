package com.jiesa.xvideocatcher

import android.content.Context
import java.io.File

/**
 * Runs one download end to end: fetch the master, pick renditions, pull both tracks, mux, save.
 *
 * The order here is not arbitrary. The master playlist is fetched **first and always**, because
 * quality cannot be chosen from what the probe captured: on 8 of 10 videos in Jay's captures X
 * had only ever requested the 32000 audio track while the master advertised 128000, and two
 * videos advertised 1080x1920 with no 1080 URL ever captured. Choosing from the capture ships a
 * measurably worse file than the one X itself could have played.
 *
 * Every failure is a distinct [Outcome] rather than an exception escaping into X's UI thread.
 * This code is injected into someone else's app: an uncaught throw in a menu handler crashes X,
 * and a user whose X crashes when they tap Download has no way to tell what went wrong.
 */
object DownloadJob {

    sealed interface Outcome {
        data class Saved(val fileName: String, val bytes: Long, val silent: Boolean) : Outcome
        data class AlreadySaved(val fileName: String) : Outcome
        /** The master advertised nothing playable, or the URL was not a master at all. */
        data class NoRenditions(val detail: String) : Outcome
        data class Failed(val stage: String, val detail: String) : Outcome
    }

    /** Progress for the UI: which track, and how far through it. */
    fun interface Progress {
        fun onProgress(stage: String, done: Int, total: Int)
    }

    /**
     * Downloads the video identified by [masterUrl].
     *
     * Scratch files are written to X's cache dir and deleted in a `finally`, so a failed
     * download does not leave tens of megabytes behind in a foreign app's storage.
     */
    fun downloadVideo(
        context: Context,
        mediaId: String,
        masterUrl: String,
        progress: Progress? = null,
    ): Outcome {
        val scratch = MediaSaver.scratchDir(context)
        val videoFile = File(scratch, "v_$mediaId.mp4")
        val audioFile = File(scratch, "a_$mediaId.mp4")
        val muxedFile = File(scratch, "m_$mediaId.mp4")
        try {
            progress?.onProgress("master", 0, 0)
            val body = try {
                Http.text(masterUrl)
            } catch (t: Throwable) {
                return Outcome.Failed("master", t.message ?: t.javaClass.simpleName)
            }

            val master = HlsPlaylist.parseMaster(masterUrl, body)
                ?: return Outcome.NoRenditions("URL was not a master playlist")
            val fetch = DownloadPlan.fetchFor(mediaId, master)
                ?: return Outcome.NoRenditions("master advertised no video rendition")

            val spec = DownloadTarget.videoSpec(mediaId, fetch.width, fetch.height)

            progress?.onProgress("video", 0, 0)
            try {
                TrackDownloader.downloadTo(fetch.videoPlaylist, videoFile) { done, total ->
                    progress?.onProgress("video", done, total)
                }
            } catch (t: Throwable) {
                return Outcome.Failed("video", t.message ?: t.javaClass.simpleName)
            }

            var audio: File? = null
            if (fetch.audioPlaylist != null) {
                progress?.onProgress("audio", 0, 0)
                try {
                    TrackDownloader.downloadTo(fetch.audioPlaylist, audioFile) { done, total ->
                        progress?.onProgress("audio", done, total)
                    }
                    audio = audioFile
                } catch (t: Throwable) {
                    // Audio failing does not have to lose the video, but the user must be
                    // told the result is silent rather than discovering it on playback.
                    return Outcome.Failed("audio", t.message ?: t.javaClass.simpleName)
                }
            }

            progress?.onProgress("mux", 0, 0)
            try {
                Muxer.mux(videoFile, audio, muxedFile)
            } catch (t: Throwable) {
                return Outcome.Failed("mux", t.message ?: t.javaClass.simpleName)
            }

            progress?.onProgress("save", 0, 0)
            return when (val saved = MediaSaver.save(context, spec) { out ->
                muxedFile.inputStream().buffered().use { it.copyTo(out) }
                muxedFile.length()
            }) {
                is MediaSaver.Result.Saved ->
                    Outcome.Saved(spec.fileName, saved.bytes, silent = fetch.isSilent)
                is MediaSaver.Result.AlreadyExists -> Outcome.AlreadySaved(spec.fileName)
                is MediaSaver.Result.Failed -> Outcome.Failed("save", saved.reason)
            }
        } finally {
            listOf(videoFile, audioFile, muxedFile).forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Downloads one photo.
     *
     * Single request, no muxing — but [MediaUrls.highestQualityPhoto] still matters here: the
     * timeline requests `name=large` and `name=tiny`, never the full image, so saving the URL
     * as captured would silently save a downscaled copy.
     */
    fun downloadPhoto(context: Context, url: String): Outcome {
        val full = MediaUrls.highestQualityPhoto(url)
        val spec = DownloadTarget.photoSpec(full)
            ?: return Outcome.Failed("plan", "not a recognisable photo URL")
        return when (val saved = MediaSaver.save(context, spec) { out -> Http.copyTo(full, out) }) {
            is MediaSaver.Result.Saved -> Outcome.Saved(spec.fileName, saved.bytes, silent = false)
            is MediaSaver.Result.AlreadyExists -> Outcome.AlreadySaved(spec.fileName)
            is MediaSaver.Result.Failed -> Outcome.Failed("save", saved.reason)
        }
    }
}
