package com.icemoneyprinter.track.pacing

import com.icemoneyprinter.track.model.AudioStructure
import com.icemoneyprinter.track.model.ImageItem
import com.icemoneyprinter.track.model.PacingConfig
import com.icemoneyprinter.track.model.SpeechSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineBuilderTest {

    private fun img(name: String) = ImageItem(
        uriString = "content://test/$name",
        displayName = name,
        contentHash = name,
        sizeBytes = 1L,
        lastModified = 0L,
    )

    private val pool = (1..6).map { img("img$it.jpg") }

    private fun structure(vararg spans: Pair<Long, Long>) =
        AudioStructure(
            durationMs = spans.last().second,
            spans = spans.map { SpeechSpan(it.first, it.second) },
            pauses = emptyList(),
            estimatedSegmentation = false,
        )

    @Test
    fun `timeline starts at zero and ends at audio end`() {
        val t = TimelineBuilder().buildAuto(pool, structure(500L to 2500L, 6000L to 9000L))
        assertEquals(0L, t.slots.first().startMs)
        assertTrue(t.durationMs >= 9000L)
    }

    @Test
    fun `slots do not overlap and are contiguous within a span`() {
        val t = TimelineBuilder().buildAuto(pool, structure(0L to 20000L))
        for (i in 1 until t.slots.size) {
            assertTrue(
                "slot $i start ${t.slots[i].startMs} < prev end ${t.slots[i - 1].endMs}",
                t.slots[i].startMs >= t.slots[i - 1].endMs,
            )
        }
    }

    @Test
    fun `long span is split across multiple images`() {
        val t = TimelineBuilder().buildAuto(pool, structure(0L to 20000L))
        assertTrue("expected multiple slots for a 20s span, got ${t.slots.size}", t.slots.size >= 4)
        t.slots.forEach { assertTrue("slot too long: ${it.durationMs}", it.durationMs <= PacingConfig().maxAutoSlotMs) }
    }

    @Test
    fun `short spans borrow dead air up to min duration`() {
        // three 2s spans separated by big gaps; non-final slots must extend to fill gaps
        val t = TimelineBuilder().buildAuto(
            pool,
            structure(200L to 2200L, 4000L to 6000L, 8000L to 10000L),
        )
        // (the final slot has nothing to borrow from and simply runs to the audio end)
        val short = t.slots.dropLast(1).filter { it.durationMs < PacingConfig().minAutoSlotMs }
        assertTrue("short slots should have borrowed dead air: ${t.slots}", short.isEmpty())
        assertEquals(10000L, t.durationMs)
    }

    @Test
    fun `no image repeats within the configured window`() {
        val t = TimelineBuilder().buildAuto(pool, structure(0L to 30000L))
        val window = PacingConfig().noRepeatWithinSlots
        t.slots.forEachIndexed { i, slot ->
            val from = maxOf(0, i - window)
            for (j in from until i) {
                val prev = t.slots[j]
                if (prev.imageHash == slot.imageHash && prev.imageUriString != slot.imageUriString) {
                    throw AssertionError("same image twice in window at $j/$i")
                }
            }
        }
    }

    @Test
    fun `pool wraps when images run out`() {
        val small = (1..3).map { img("small$it.jpg") }
        // 30s span at max 6s/slot = 5 slots from a pool of 3 -> wraps
        val t = TimelineBuilder().buildAuto(small, structure(0L to 30000L))
        assertEquals(5, t.slots.size.toLong())
        assertEquals(30000L, t.durationMs)
    }

    @Test
    fun `degenerate narration falls back to equal intervals`() {
        val t = TimelineBuilder().buildAuto(
            pool,
            AudioStructure(10000L, emptyList(), emptyList(), estimatedSegmentation = true),
        )
        assertTrue("expected fallback slots", t.slots.isNotEmpty())
        assertEquals(10000L, t.durationMs)
    }
}
