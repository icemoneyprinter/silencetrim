package com.icemoneyprinter.track.pacing

import com.icemoneyprinter.track.model.Slot
import com.icemoneyprinter.track.model.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcPlannerTest {

    private fun slot(name: String, start: Long, end: Long) =
        Slot("content://t/$name", name, start, end)

    private fun timeline(
        vararg spans: Triple<String, Long, Long>,
        durationMs: Long = 600_000,
    ) = Timeline(spans.map { slot(it.first, it.second, it.third) }, durationMs)

    @Test
    fun `empty timeline has no arcs`() {
        assertTrue(ArcPlanner.plan(Timeline(emptyList(), 1000)).isEmpty())
    }

    @Test
    fun `shorter than min is one arc`() {
        val t = timeline(Triple("a", 0, 30_000))
        val arcs = ArcPlanner.plan(t)
        assertEquals(1, arcs.size.toLong())
        assertEquals(30_000, arcs[0].durationMs.toLong())
    }

    @Test
    fun `cuts happen at slot boundaries only`() {
        val t = timeline(
            Triple("a", 0, 50_000),
            Triple("b", 50_000, 96_000),
            Triple("c", 96_000, 150_000),
        )
        val arcs = ArcPlanner.plan(t)
        assertTrue("expected multiple arcs, got ${arcs.size}", arcs.size >= 2)
        val slotStarts = t.slots.map { it.startMs }.toSet() + 0L
        val slotEnds = t.slots.map { it.endMs }.toSet()
        arcs.forEach { arc ->
            assertTrue(
                "arc ${arc.index} start ${arc.startMs} is not a slot boundary",
                arc.startMs in slotStarts,
            )
            assertTrue(
                "arc ${arc.index} end ${arc.endMs} is not a slot boundary",
                arc.endMs in slotEnds,
            )
        }
    }

    @Test
    fun `arcs never exceed max unless a single slot does`() {
        val slots = (0 until 30).map { i -> slot("s$i", i * 5_000L, (i + 1) * 5_000L) }
        val t = Timeline(slots, 150_000)
        val arcs = ArcPlanner.plan(t)
        arcs.forEach { arc ->
            assertTrue("arc too long: ${arc.durationMs}", arc.durationMs <= 90_000 + 1)
        }
    }

    @Test
    fun `arcs are contiguous and cover the whole timeline`() {
        val slots = (0 until 40).map { i -> slot("s$i", i * 4_000L, (i + 1) * 4_000L) }
        val t = Timeline(slots, 160_000)
        val arcs = ArcPlanner.plan(t)
        assertEquals(0L, arcs.first().startMs)
        assertEquals(160_000L, arcs.last().endMs)
        for (i in 1 until arcs.size) {
            assertEquals("gap before arc $i", arcs[i - 1].endMs, arcs[i].startMs)
        }
    }

    @Test
    fun `totalDurationMs is last slot end`() {
        val t = timeline(
            Triple("a", 0, 20_000),
            Triple("b", 20_000, 75_000),
        )
        assertEquals(75_000L, ArcPlanner.totalDurationMs(t))
    }
}
