package com.icemoneyprinter.track.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WavParserTest {

    private fun wav(
        sampleRate: Int,
        samples: ShortArray,
        channels: Int = 1,
        bits: Int = 16,
    ): ByteArray {
        val dataBytes = samples.size * channels * (bits / 8)
        val out = java.io.ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
        ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels); le32(sampleRate)
        le32(sampleRate * channels * bits / 8); le16(channels * bits / 8); le16(bits)
        ascii("data"); le32(dataBytes)
        for (s in samples) for (c in 0 until channels) le16(s.toInt())
        return out.toByteArray()
    }

    @Test
    fun `parses gemini shape mono 24khz`() {
        val sr = 24000
        val samples = ShortArray(2400) { if (it < 1200) 10000 else -10000 }
        val pcm = WavParser.parse(wav(sr, samples))
        assertNotNull(pcm)
        assertEquals(sr, pcm!!.sampleRate)
        assertEquals(2400, pcm.samples.size.toLong())
        assertEquals(10000.toShort(), pcm.samples[0])
    }

    @Test
    fun `downmixes stereo to mono`() {
        val sr = 24000
        val left = ShortArray(100) { 1000 }
        val right = ShortArray(100) { 3000 }
        // interleave manually
        val out = java.io.ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
        val dataBytes = left.size * 4
        ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(2); le32(sr); le32(sr * 4); le16(4); le16(16)
        ascii("data"); le32(dataBytes)
        for (i in left.indices) { le16(left[i].toInt()); le16(right[i].toInt()) }
        val pcm = WavParser.parse(out.toByteArray())
        assertNotNull(pcm)
        assertEquals(100, pcm!!.samples.size.toLong())
        assertEquals(2000.toShort(), pcm.samples[0])
    }

    @Test
    fun `rejects non-wav and compressed`() {
        assertNull(WavParser.parse(ByteArray(10)))
        val junk = ByteArray(100)
        junk[0] = 'R'.code.toByte()
        assertNull(WavParser.parse(junk))
    }
}
