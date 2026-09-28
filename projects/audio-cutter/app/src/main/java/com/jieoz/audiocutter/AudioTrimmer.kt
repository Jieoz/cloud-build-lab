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
 * It decodes the source with the platform decoder and re-encodes to AAC, so it
 * works for any format the device can decode (mp3 / m4a / aac / wav / ogg ...),
 * always producing a portable .m4a. One code path, no per-format branching.
 */
object AudioTrimmer {

    private const val TIMEOUT_US = 10_000L
    private const val OUT_BITRATE = 128_000

    fun interface Progress {
        fun onProgress(fraction: Float)
    }

    /**
     * @param input      readable source descriptor
     * @param output     writable destination descriptor (.m4a)
     * @param startUs    keep-from, microseconds
     * @param endUs      keep-to, microseconds
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

            val info = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false

            // Track the timestamp of the first emitted PCM so output starts at 0.
            var firstPtsUs = -1L

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

                // 2) drain decoder -> feed encoder
                if (!decoderDone) {
                    val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val pcm = decoder.getOutputBuffer(outIndex)
                        val inRange = info.presentationTimeUs in startUs..endUs && info.size > 0
                        if (inRange && pcm != null) {
                            if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                            feedEncoder(
                                encoder, pcm, info.offset, info.size,
                                info.presentationTimeUs - firstPtsUs
                            )
                            progress?.onProgress(
                                ((info.presentationTimeUs - startUs).toFloat()
                                        / (endUs - startUs)).coerceIn(0f, 1f)
                            )
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (eos) {
                            // signal end to encoder
                            val eIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (eIndex >= 0) {
                                encoder.queueInputBuffer(
                                    eIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                            }
                            decoderDone = true
                        }
                    }
                }

                // 3) drain encoder -> muxer
                val encIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    require(!muxerStarted) { "编码器格式变化两次" }
                    muxerTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (encIndex >= 0) {
                    val encoded = encoder.getOutputBuffer(encIndex)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0
                    }
                    if (info.size > 0 && muxerStarted) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(muxerTrack, encoded, info)
                    }
                    encoder.releaseOutputBuffer(encIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
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

    private fun feedEncoder(
        encoder: MediaCodec, pcm: ByteBuffer, offset: Int, size: Int, ptsUs: Long
    ) {
        var remaining = size
        var srcPos = offset
        while (remaining > 0) {
            val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex < 0) continue
            val dst = encoder.getInputBuffer(inIndex)!!
            dst.clear()
            val chunk = minOf(remaining, dst.capacity())
            val dup = pcm.duplicate()
            dup.position(srcPos)
            dup.limit(srcPos + chunk)
            dst.put(dup)
            encoder.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
            srcPos += chunk
            remaining -= chunk
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
