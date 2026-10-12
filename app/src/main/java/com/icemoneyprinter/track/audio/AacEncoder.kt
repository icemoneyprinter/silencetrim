package com.icemoneyprinter.track.audio

/** One encoded AAC access unit. */
data class AacSample(
    val data: ByteArray,
    val ptsUs: Long,
    val flags: Int,
)

/**
 * Encodes PCM to AAC-LC for the single audio mux of the final export (plan D8:
 * the narration is encoded exactly once, never per-arc, so there is no drift).
 *
 * 24 kHz (Gemini TTS) is resampled to 48 kHz first because AAC encoder support for
 * 24 kHz input is inconsistent across devices.
 */
class AacEncoder {

    companion object {
        const val TARGET_SAMPLE_RATE = 48_000
    }

    /** Linear-interpolation resample (unit-testable; quality is ample for speech). */
    fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val ratio = toRate.toDouble() / fromRate
        val outLength = (samples.size * ratio).toInt()
        val out = ShortArray(outLength)
        for (i in 0 until outLength) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceIn(0, samples.size - 1)
            val frac = srcPos - i0
            out[i] = (samples[i0] * (1 - frac) + samples[i1] * frac).toInt().toShort()
        }
        return out
    }

    /**
     * Encodes the full PCM stream, optionally mixing a music bed under it. Returns the
     * encoder output format (for the muxer's addTrack) and the complete sample list.
     * Runs the MediaCodec synchronously on the calling thread — callers should be on a
     * background dispatcher.
     */
    fun encode(
        pcm: PcmMono,
        musicPcm: PcmMono? = null,
        musicGain: Float = 0.35f,
    ): Pair<android.media.MediaFormat, List<AacSample>>? {
        val narration = resample(pcm.samples, pcm.sampleRate, TARGET_SAMPLE_RATE)
        val samples = if (musicPcm == null) {
            narration
        } else {
            val music = resample(musicPcm.samples, musicPcm.sampleRate, TARGET_SAMPLE_RATE)
            val out = ShortArray(narration.size)
            for (i in narration.indices) {
                val m = if (i < music.size) music[i] * musicGain else 0f
                val mixed = narration[i] + m
                out[i] = mixed.coerceIn(-32768f, 32767f).toInt().toShort()
            }
            out
        }
        val sampleRate = TARGET_SAMPLE_RATE
        val bitrate = 192_000

        val format = android.media.MediaFormat.createAudioFormat(
            android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            1,
        ).apply {
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, 2) // AAC-LC
            setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val samplesOut = ArrayList<AacSample>()
        var outputFormat: android.media.MediaFormat? = null
        var inputOffset = 0
        var totalInputUs = 0L
        val bytesPerFrame = 2 // 16-bit mono
        val chunkSamples = 1024
        var eosQueued = false
        var done = false

        try {
            while (!done) {
                if (!eosQueued) {
                    val inBufIndex = codec.dequeueInputBuffer(10_000)
                    if (inBufIndex >= 0) {
                        val buffer = codec.getInputBuffer(inBufIndex)!!
                        buffer.clear()
                        val isLast = inputOffset >= samples.size
                        val bytes: ByteArray
                        val ptsUs: Long
                        if (isLast) {
                            bytes = ByteArray(0)
                            ptsUs = totalInputUs
                        } else {
                            val remaining = samples.size - inputOffset
                            val count = minOf(chunkSamples, remaining)
                            bytes = ByteArray(count * bytesPerFrame)
                            for (i in 0 until count) {
                                val v = samples[inputOffset + i].toInt()
                                bytes[i * 2] = (v and 0xFF).toByte()
                                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                            }
                            ptsUs = totalInputUs
                            totalInputUs += count * 1_000_000L / sampleRate
                            inputOffset += count
                        }
                        val flag = if (isLast) android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        if (isLast) eosQueued = true
                        codec.queueInputBuffer(inBufIndex, 0, bytes.size, ptsUs, flag)
                    }
                }

                val info = android.media.MediaCodec.BufferInfo()
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIndex)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        out.get(data, info.offset, info.size)
                        samplesOut.add(AacSample(data, info.presentationTimeUs, info.flags))
                    }
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        done = true
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                } else if (outIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = codec.outputFormat
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val fmt = outputFormat ?: return null
        return fmt to samplesOut
    }
}
