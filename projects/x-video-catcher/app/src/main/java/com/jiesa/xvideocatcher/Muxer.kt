package com.jiesa.xvideocatcher

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Combines a downloaded video track and audio track into one MP4.
 *
 * Why a real muxer and not concatenation: X's tracks are fragmented MP4, and while
 * `init + segments` concatenated *within one track* is a valid file (verified — a 1080x1920
 * h264 track and a 128kbps aac track both parsed correctly, matching durations of 24.017s and
 * 24.009s), concatenating the two *tracks* together is not. The result would hold two separate
 * `moov` headers and a player reads the first one only, so the "video with sound" the user
 * asked for would come out silent with a success message attached. That is the failure mode
 * this class exists to prevent.
 *
 * [MediaMuxer] is the platform's own remuxer: it copies encoded samples into a fresh container
 * without re-encoding, so there is no quality loss and no codec dependency. It is available on
 * every API this module supports (18+; the module requires 28+).
 *
 * Note this runs inside X's process, so the work is CPU-cheap by design — sample copying, no
 * decode — and touches no X state.
 */
object Muxer {

    class Failure(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Max size of a single encoded sample. 1080p keyframes exceed the common 256KB guess. */
    private const val BUFFER_BYTES = 2 * 1024 * 1024

    /**
     * Muxes [videoFile] and optional [audioFile] into [outputFile].
     *
     * [audioFile] is null for a genuinely silent upload (one captured master advertised no
     * audio rendition at all), in which case the output is a video-only MP4 — correct, and
     * distinct from the silent-by-accident case above.
     */
    fun mux(videoFile: File, audioFile: File?, outputFile: File) {
        var muxer: MediaMuxer? = null
        val extractors = mutableListOf<MediaExtractor>()
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val video = openTrack(videoFile, "video/") ?: throw Failure("no video track found")
            extractors += video.extractor
            val videoOut = muxer.addTrack(video.format)

            var audio: Track? = null
            var audioOut = -1
            if (audioFile != null) {
                audio = openTrack(audioFile, "audio/")
                if (audio != null) {
                    extractors += audio.extractor
                    audioOut = muxer.addTrack(audio.format)
                } else {
                    // Downloaded bytes that contain no audio track is a real defect worth
                    // failing on rather than quietly shipping a silent video: it means the
                    // audio playlist gave us something unexpected.
                    throw Failure("audio file contained no audio track")
                }
            }

            muxer.start()
            copy(video, muxer, videoOut)
            if (audio != null && audioOut >= 0) copy(audio, muxer, audioOut)
            muxer.stop()
        } catch (f: Failure) {
            throw f
        } catch (t: Throwable) {
            throw Failure("mux failed: ${t.javaClass.simpleName}", t)
        } finally {
            extractors.forEach { runCatching { it.release() } }
            runCatching { muxer?.release() }
        }
    }

    private class Track(
        val extractor: MediaExtractor,
        val index: Int,
        val format: MediaFormat,
    )

    private fun openTrack(file: File, mimePrefix: String): Track? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith(mimePrefix)) {
                    extractor.selectTrack(i)
                    return Track(extractor, i, format)
                }
            }
            extractor.release()
            null
        } catch (t: Throwable) {
            runCatching { extractor.release() }
            throw Failure("could not read ${file.name}", t)
        }
    }

    /**
     * Copies every sample of one track across, preserving presentation timestamps.
     *
     * Timestamps are taken from the extractor rather than synthesised: audio and video have
     * different sample durations (the 24.017s video and 24.009s audio measured on a real
     * capture do not divide evenly), so generated timing would drift audio out of sync over
     * the length of a clip.
     */
    private fun copy(track: Track, muxer: MediaMuxer, outIndex: Int) {
        val buffer = ByteBuffer.allocate(BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        val extractor = track.extractor
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outIndex, buffer, info)
            if (!extractor.advance()) break
        }
    }
}
