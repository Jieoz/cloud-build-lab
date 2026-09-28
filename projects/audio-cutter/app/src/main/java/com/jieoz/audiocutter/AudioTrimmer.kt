package com.jieoz.audiocutter

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Trims [startUs, endUs] out of an audio source and writes an .m4a file.
 *
 * Two engines, picked automatically:
 *
 *  1. REMUX (stream copy) — the fast path. Copies the already-compressed audio
 *     frames straight into a new MP4 container without decoding or re-encoding,
 *     the equivalent of `ffmpeg -c copy`. Near-instant and lossless. Used when
 *     the source is AAC (the only codec MediaMuxer can reliably mux into MP4),
 *     which covers most phone recordings and shared .m4a/.mp4 audio.
 *     Audio frames are all independently decodable, so cutting to the nearest
 *     frame is effectively sample-accurate (no keyframe problem like video).
 *
 *  2. TRANSCODE (decode -> PCM -> AAC) — the fallback. Only used for formats
 *     MediaMuxer cannot stream-copy (mp3 / wav / ogg / flac ...). This re-renders
 *     the audio and is therefore roughly real-time and slightly lossy, but it
 *     guarantees a playable .m4a for any decodable input.
 */
object AudioTrimmer {

    private const val TIMEOUT_US = 10_000L
    private const val OUT_BITRATE = 128_000

    fun interface Progress {
        fun onProgress(fraction: Float)
    }

    /**
     * @param input   readable source descriptor
     * @param output  writable destination descriptor (.m4a)
     * @param startUs keep-from, microseconds
     * @param endUs   keep-to, microseconds
     */
    fun trim(
        input: FileDescriptor,
        output: FileDescriptor,
        startUs: Long,
        endUs: Long,
        progress: Progress? = null,
    ) {
        require(endUs > startUs) { "结束时间必须晚于开始时间" }

        // Peek at the source codec to choose the engine.
        val probe = MediaExtractor()
        val mime: String
        try {
            probe.setDataSource(input)
            val t = firstAudioTrack(probe)
            require(t >= 0) { "文件里没有音频轨道" }
            mime = probe.getTrackFormat(t).getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("无法识别音频编码")
        } finally {
            runCatching { probe.release() }
        }

        val muxable = mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
        if (muxable) {
            remux(input, output, startUs, endUs, progress)
        } else {
            transcode(input, output, startUs, endUs, progress)
        }
    }

    /** Fast path: copy compressed AAC frames straight into a new MP4, no re-encode. */
    private fun remux(
        input: FileDescriptor,
        output: FileDescriptor,
        startUs: Long,
        endUs: Long,
        progress: Progress?,
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input)
            val track = firstAudioTrack(extractor)
            require(track >= 0) { "文件里没有音频轨道" }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)

            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
            val buffer = ByteBuffer.allocate(maxInput.coerceAtLeast(64 * 1024))

            muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dstTrack = muxer.addTrack(format)
            muxer.start()

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val info = MediaCodec.BufferInfo()
            val span = (endUs - startUs).toFloat().coerceAtLeast(1f)
            var firstPtsUs = -1L

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                if (pts >= startUs) {
                    if (firstPtsUs < 0) firstPtsUs = pts
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = pts - firstPtsUs
                    info.flags =
                        if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(dstTrack, buffer, info)
                    progress?.onProgress(((pts - startUs) / span).coerceIn(0f, 1f))
                }
                if (!extractor.advance()) break
            }
            progress?.onProgress(1f)
        } finally {
            runCatching { muxer?.stop() }; runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Fallback: decode to PCM and re-encode to AAC for non-muxable inputs. */
    private fun transcode(
        input: FileDescriptor,
        output: FileDescriptor,
        startUs: Long,
        endUs: Long,
        progress: Progress?,
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input)
            val trackIndex = firstAudioTrack(extractor)
            require(trackIndex >= 0) { "文件里没有音频轨道" }
            extractor.selectTrack(trackIndex)
            val srcFormat = extractor.getTrackFormat(trackIndex)

            val sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val bytesPerFrame = 2 * channelCount // decoder emits 16-bit PCM

            val mime = srcFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("无法识别音频编码")

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(srcFormat, null, null, 0)
                start()
            }

            val outFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, OUT_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrack = -1
            var muxerStarted = false

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderEosQueued = false
            var encoderDone = false
            var firstPtsUs = -1L

            var pendingPcm: ByteBuffer? = null
            var pendingPtsUs = 0L

            while (!encoderDone) {
                if (!extractorDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        val sampleTime = extractor.sampleTime
                        if (size < 0 || sampleTime > endUs) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone && pendingPcm == null) {
                    val outIndex = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val eos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val pcm = decoder.getOutputBuffer(outIndex)
                        val inRange = decInfo.presentationTimeUs in startUs..endUs && decInfo.size > 0
                        if (inRange && pcm != null) {
                            if (firstPtsUs < 0) firstPtsUs = decInfo.presentationTimeUs
                            pcm.position(decInfo.offset)
                            pcm.limit(decInfo.offset + decInfo.size)
                            val copy = ByteBuffer.allocate(decInfo.size)
                            copy.put(pcm)
                            copy.flip()
                            pendingPcm = copy
                            pendingPtsUs = decInfo.presentationTimeUs - firstPtsUs
                            progress?.onProgress(
                                ((decInfo.presentationTimeUs - startUs).toFloat()
                                        / (endUs - startUs)).coerceIn(0f, 1f)
                            )
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (eos) decoderDone = true
                    }
                }

                if (pendingPcm != null) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val dst = encoder.getInputBuffer(inIndex)!!
                        dst.clear()
                        val chunk = minOf(pendingPcm!!.remaining(), dst.capacity())
                        val slice = pendingPcm!!.duplicate()
                        slice.limit(slice.position() + chunk)
                        dst.put(slice)
                        encoder.queueInputBuffer(inIndex, 0, chunk, pendingPtsUs, 0)
                        pendingPcm!!.position(pendingPcm!!.position() + chunk)
                        if (bytesPerFrame > 0) {
                            val frames = chunk / bytesPerFrame
                            pendingPtsUs += frames.toLong() * 1_000_000L / sampleRate
                        }
                        if (!pendingPcm!!.hasRemaining()) pendingPcm = null
                    }
                } else if (decoderDone && !encoderEosQueued) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        encoder.queueInputBuffer(
                            inIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        encoderEosQueued = true
                    }
                }

                val encIndex = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    require(!muxerStarted) { "编码器格式变化两次" }
                    muxerTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (encIndex >= 0) {
                    val encoded = encoder.getOutputBuffer(encIndex)!!
                    if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encInfo.size = 0
                    }
                    if (encInfo.size > 0 && muxerStarted) {
                        encoded.position(encInfo.offset)
                        encoded.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(muxerTrack, encoded, encInfo)
                    }
                    encoder.releaseOutputBuffer(encIndex, false)
                    if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true
                    }
                }
            }
            progress?.onProgress(1f)
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { muxer?.stop() }; runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun firstAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return -1
    }
}
