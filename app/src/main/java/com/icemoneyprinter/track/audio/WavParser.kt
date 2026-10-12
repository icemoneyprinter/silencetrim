package com.icemoneyprinter.track.audio

/** Decoded PCM narration, mixed down to mono shorts at its native sample rate. */
data class PcmMono(
    val samples: ShortArray,
    val sampleRate: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is PcmMono && samples.contentEquals(other.samples) && sampleRate == other.sampleRate

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
}

/**
 * Minimal RIFF/WAVE parser. Handles the Gemini TTS output shape (16-bit PCM mono 24 kHz)
 * and any standard linear PCM WAV. Returns null for compressed formats (those go through
 * MediaCodec decoding, which lands in M2).
 */
object WavParser {

    fun parse(bytes: ByteArray): PcmMono? {
        if (bytes.size < 44) return null
        fun ascii(off: Int, len: Int) = String(bytes, off, len, Charsets.US_ASCII)
        if (ascii(0, 4) != "RIFF" || ascii(8, 4) != "WAVE") return null

        var offset = 12
        var sampleRate = -1
        var channels = -1
        var bits = -1
        var dataStart = -1
        var dataLen = -1

        while (offset + 8 <= bytes.size) {
            val chunkId = ascii(offset, 4)
            val chunkSize = readIntLe(bytes, offset + 4)
            val bodyStart = offset + 8
            when (chunkId) {
                "fmt " -> {
                    val audioFormat = readShortLe(bytes, bodyStart)
                    channels = readShortLe(bytes, bodyStart + 2)
                    sampleRate = readIntLe(bytes, bodyStart + 4)
                    bits = readShortLe(bytes, bodyStart + 14)
                    if (audioFormat != 1) return null // PCM only
                }
                "data" -> {
                    dataStart = bodyStart
                    dataLen = minOf(chunkSize, bytes.size - bodyStart)
                }
            }
            // chunks are word-aligned
            offset = bodyStart + chunkSize + (chunkSize and 1)
        }
        if (sampleRate <= 0 || channels <= 0 || bits != 16 || dataStart < 0) return null

        val totalFrames = dataLen / 2
        val frameCount = totalFrames / channels
        val out = ShortArray(frameCount)
        var p = dataStart
        for (i in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channels) {
                sum += readShortLe(bytes, p)
                p += 2
            }
            out[i] = (sum / channels).toShort()
        }
        return PcmMono(out, sampleRate)
    }

    private fun readShortLe(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)

    private fun readIntLe(b: ByteArray, off: Int): Int =
        readShortLe(b, off) or (readShortLe(b, off + 2) shl 16)
}
