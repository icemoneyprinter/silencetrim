package com.icemoneyprinter.track.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyAnalyzerTest {

    private val sr = 24000

    /** tone: amplitude-segments in ms; silence = zeros. */
    private fun pcm(spec: List<Pair<Long, Int>>): PcmMono {
        val total = spec.sumOf { (durMs, _) -> durMs * sr / 1000 }.toInt()
        val out = ShortArray(total)
        var p = 0
        for ((durMs, amp) in spec) {
            val n = (durMs * sr / 1000).toInt()
            if (amp == 0) {
                java.util.Arrays.fill(out, p, p + n, 0.toShort())
            } else {
                for (i in 0 until n) {
                    out[p + i] = (amp * kotlin.math.sin(2.0 * Math.PI * 220.0 * i / sr)).toInt().toShort()
                }
            }
            p += n
        }
        return PcmMono(out, sr)
    }

    @Test
    fun `detects spans and pauses from clean tts-like audio`() {
        val analyzer = EnergyAnalyzer()
        val audio = pcm(
            listOf(
                1000L to 12000,   // speech 0-1000
                1200L to 0,       // pause
                2000L to 12000,   // speech 2200-4200
                1500L to 0,       // pause
                1000L to 12000,   // speech 5700-6700
                100L to 0,
            ),
        )
        val structure = analyzer.analyze(audio)
        assertFalse(structure.estimatedSegmentation)
        assertEquals(3, structure.spans.size.toLong())
        // boundaries within one 20ms frame
        structure.spans[0].let { assertTrue(Math.abs(it.startMs) <= 20); assertTrue(Math.abs(it.endMs - 1000) <= 20) }
        structure.spans[1].let { assertTrue(Math.abs(it.startMs - 2200) <= 20); assertTrue(Math.abs(it.endMs - 4200) <= 20) }
        structure.spans[2].let { assertTrue(Math.abs(it.startMs - 5700) <= 20); assertTrue(Math.abs(it.endMs - 6700) <= 20) }
        assertEquals(2, structure.pauses.size.toLong())
    }

    @Test
    fun `continuous speech yields a single span`() {
        val audio = pcm(listOf(5000L to 12000, 200L to 0))
        val structure = EnergyAnalyzer().analyze(audio)
        assertEquals(1, structure.spans.size.toLong())
        assertTrue(structure.spans[0].endMs - structure.spans[0].startMs >= 4900)
    }

    @Test
    fun `digital silence everywhere is flagged estimated`() {
        val structure = EnergyAnalyzer().analyze(PcmMono(ShortArray(sr), sr))
        assertTrue(structure.estimatedSegmentation)
        assertEquals(1000L, structure.durationMs)
    }

    @Test
    fun `short gaps inside speech are merged`() {
        // 100ms gaps between bursts must NOT become pauses
        val structure = EnergyAnalyzer().analyze(
            pcm(
                listOf(
                    500L to 12000, 100L to 0,
                    500L to 12000, 100L to 0,
                    500L to 12000, 200L to 0,
                ),
            ),
        )
        assertEquals(1, structure.spans.size.toLong())
        assertEquals(1700.0, structure.spans[0].endMs.toDouble(), 20.0)
    }
}
