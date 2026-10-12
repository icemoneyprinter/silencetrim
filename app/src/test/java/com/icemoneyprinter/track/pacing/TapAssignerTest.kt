package com.icemoneyprinter.track.pacing

import com.icemoneyprinter.track.model.AudioStructure
import com.icemoneyprinter.track.model.PauseRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap semantics (plan D3/D4, as implemented in TapAssigner):
 * a tap's boundary is the START of the tapped image; the first tapped image covers
 * [0, second tap's boundary); the last tapped image runs to the audio end. A boundary
 * is `tapMs - 250` (reaction lag), snapped to the nearest pause edge within ±600ms
 * (ties prefer the pause end).
 */
class TapAssignerTest {

    private fun pause(from: Long, to: Long) = PauseRegion(from, to)

    private fun structure(
        vararg pauses: PauseRegion,
        durationMs: Long = 10000,
    ) = AudioStructure(durationMs, emptyList(), pauses.toList(), estimatedSegmentation = false)

    private fun tap(name: String, at: Long) = TapRecord("content://t/$name", name, at)

    @Test
    fun `tap time is corrected for reaction lag`() {
        val r = TapAssigner().assignFromTaps(
            listOf(tap("a", 2000), tap("b", 5000)),
            structure(durationMs = 10000),
        )
        // a taps at 2000 -> starts at 1750; b taps at 5000 -> starts at 4750.
        // First image runs from 0 to b's boundary.
        assertEquals(0L, r.timeline.slots[0].startMs)
        assertEquals(4750L, r.timeline.slots[0].endMs)
        assertEquals(4750L, r.timeline.slots[1].startMs)
        assertEquals(10000L, r.timeline.slots[1].endMs)
    }

    @Test
    fun `napoleon mid-sentence split works without any pause`() {
        // The real session: tap "napoleon" at playback start, tap "smallman" when
        // "wasn't a small man" begins (1550). No pause exists between words; the
        // corrected tap time IS the boundary.
        val r = TapAssigner().assignFromTaps(
            listOf(tap("napoleon", 0), tap("smallman", 1550)),
            structure(durationMs = 6700),
        )
        val slots = r.timeline.slots
        assertEquals(2, slots.size.toLong())
        assertEquals(0L, slots[0].startMs)
        assertEquals(1300L, slots[0].endMs)
        assertEquals("napoleon", slots[0].imageHash)
        assertEquals(1300L, slots[1].startMs)
        assertEquals(6700L, slots[1].endMs)
        assertEquals("smallman", slots[1].imageHash)
    }

    @Test
    fun `cut snaps to nearest pause edge`() {
        // A leading tap starts x at 0. pause 1000-1300; tap a@1800 -> intent 1550;
        // nearest edge 1300 (250 away) vs 1000 (550) -> cut at 1300.
        val r = TapAssigner().assignFromTaps(
            listOf(tap("x", 100), tap("a", 1800)),
            structure(pause(1000, 1300), durationMs = 4000),
        )
        assertEquals(1300L, r.timeline.slots[0].endMs)
        assertEquals("a", r.timeline.slots[1].imageHash)
        assertEquals(4000L, r.timeline.slots[1].endMs)
    }

    @Test
    fun `no snap when pause edge is outside the window`() {
        // intent 2760; nearest edge 1300 is 1460ms away -> no snap, raw boundary used.
        val r = TapAssigner().assignFromTaps(
            listOf(tap("x", 100), tap("a", 3000)),
            structure(pause(1000, 1300), durationMs = 5000),
        )
        assertEquals(2750L, r.timeline.slots[0].endMs)
        assertEquals("a", r.timeline.slots[1].imageHash)
    }

    @Test
    fun `first tapped image covers from zero`() {
        val r = TapAssigner().assignFromTaps(
            listOf(tap("a", 2000), tap("b", 4000)),
            structure(durationMs = 6000),
        )
        assertEquals(0L, r.timeline.slots[0].startMs)
        assertEquals(3750L, r.timeline.slots[0].endMs)
        assertEquals(6000L, r.timeline.slots[1].endMs)
    }

    @Test
    fun `colliding taps are dropped and counted`() {
        val r = TapAssigner().assignFromTaps(
            listOf(tap("a", 2000), tap("b", 2120), tap("c", 2200)),
            structure(durationMs = 8000),
        )
        // boundaries 1750, 1870, 1950; both b and c collide with a's floor -> dropped.
        assertEquals(2, r.droppedTapCount.toLong())
        assertEquals(1, r.timeline.slots.size.toLong())
        assertEquals(8000L, r.timeline.slots[0].endMs)
    }

    @Test
    fun `taps are sorted by boundary regardless of tap order`() {
        val r = TapAssigner().assignFromTaps(
            listOf(tap("late", 8000), tap("early", 2000)),
            structure(durationMs = 9000),
        )
        val hashes = r.timeline.slots.map { it.imageHash }
        assertEquals(listOf("early", "late"), hashes)
        assertEquals(7750L, r.timeline.slots[1].startMs)
    }

    @Test
    fun `no taps gives empty timeline`() {
        val r = TapAssigner().assignFromTaps(emptyList(), structure(durationMs = 5000))
        assertTrue(r.timeline.slots.isEmpty())
    }

    @Test
    fun `single tap spans remainder into audio end`() {
        val r = TapAssigner().assignFromTaps(listOf(tap("a", 4000)), structure(durationMs = 9000))
        assertEquals(1, r.timeline.slots.size.toLong())
        assertEquals(9000L, r.timeline.slots[0].endMs)
    }

    @Test
    fun `snap prefers pause end when intent is inside pause`() {
        // intent 1550 inside pause 1500-1600; tie between edges -> pause end 1600.
        val r = TapAssigner().assignFromTaps(
            listOf(tap("x", 100), tap("a", 1800)),
            structure(pause(1500, 1600), durationMs = 6000),
        )
        assertEquals(1600L, r.timeline.slots[0].endMs)
        assertEquals("a", r.timeline.slots[1].imageHash)
    }

    @Test
    fun `floor is enforced against absolute boundaries`() {
        val r = TapAssigner().assignFromTaps(
            listOf(tap("a", 1000), tap("b", 1400)),
            structure(durationMs = 5000),
        )
        // intent b = 1150; a boundary 750; 1150 < 750+600=1350 -> b dropped.
        assertEquals(1, r.droppedTapCount.toLong())
        assertEquals(1, r.timeline.slots.size.toLong())
        assertEquals(5000L, r.timeline.slots.last().endMs)
    }
}
