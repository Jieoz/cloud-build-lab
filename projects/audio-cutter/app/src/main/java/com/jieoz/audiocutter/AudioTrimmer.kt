package com.jieoz.audiocutter

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Trims [startUs, endUs] out of an audio source and writes an .m4a (AAC) file.
 *
 * Decodes the source with the platform decoder and re-encodes to AAC, so it
 * works for any format the device can decode (mp3 / m4a / aac / wav / ogg ...),
 * always producing a portable .m4a. One code path, no per-format branching.
 *
 * The loop is a single non-blocking state machine that ALWAYS drains the
 * encoder output every iteration. The previous version fed all decoded PCM
 * into the encoder input buffers in a tight inner loop WITHOUT draining the
 * encoder output; once the input pool filled, dequeueInputBuffer spun on -1
 * forever and the export hung with the progress bar frozen. A codec must never
 * be fed without being drained in the same loop.
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

            // Separate BufferInfo objects: sharing one between the decoder and
            // encoder dequeue calls in the same iteration corrupts sample size
            // and timestamps.
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderEosQueued = false
            var encoderDone = false

            // Timestamp of the first emitted PCM so the output starts at 0.
            var firstPtsUs = -1L

            // One decoded PCM buffer can exceed one encoder input buffer; hold
            // the remainder and push it over several iterations while still
            // draining the encoder each pass.
            var pendingPcm: ByteBuffer? = null
            var pendingPtsUs = 0L

            while (!encoderDone) {
                // 1) feed encoded samples into the decoder
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

                // 2) pull ONE decoded PCM buffer, only when the last is drained
                if (!decoderDone && pendingPcm == null) {
                    val outIndex = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val eos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val pcm = decoder.getOutputBuffer(outIndex)
                        val inRange = decInfo.presentationTimeUs in startUs..endUs && decInfo.size > 0
                        if (inRange && pcm != null) {
                            if (firstPtsUs < 0) firstPtsUs = decInfo.presentationTimeUs
                            // Copy out; the decoder buffer is released right after.
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

                // 3) push pending PCM into the encoder, one input buffer per pass
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
                        // Advance pts by the real duration of the bytes consumed
                        // so split chunks never emit non-monotonic timestamps.
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

                // 4) drain encoder -> muxer EVERY iteration (prevents the
                //    input-buffer-starvation deadlock)
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
