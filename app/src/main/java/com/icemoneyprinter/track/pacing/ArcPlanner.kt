package com.icemoneyprinter.track.pacing

import com.icemoneyprinter.track.model.Slot
import com.icemoneyprinter.track.model.Timeline
import kotlin.math.ceil

/** One render batch: a contiguous run of timeline slots. */
data class Arc(
    val index: Int,
    val slots: List<Slot>,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Splits the timeline into render arcs (plan: 45–90s, cut at slot boundaries only).
 *
 * Arcs carry checkpoints (resume after a kill), thermal relief (encoder gets
 * intermittent breaks), and honest progress UI. Memory is NOT the motivation —
 * plan notes: arcs exist for checkpoints/thermals, not RAM.
 */
object ArcPlanner {

    fun plan(
        timeline: Timeline,
        minMs: Long = 45_000,
        maxMs: Long = 90_000,
    ): List<Arc> {
        if (timeline.slots.isEmpty()) return emptyList()

        val arcs = ArrayList<Arc>()
        var group = ArrayList<Slot>()
        var groupStart = timeline.slots.first().startMs
        var groupEnd = groupStart
        var index = 0

        fun flush() {
            if (group.isNotEmpty()) {
                arcs.add(Arc(index, group.toList(), groupStart, groupEnd))
                index++
            }
            group = ArrayList()
        }

        for (slot in timeline.slots) {
            if (group.isEmpty()) {
                groupStart = slot.startMs
            }
            group.add(slot)
            groupEnd = slot.endMs
            val reachedMin = (groupEnd - groupStart) >= minMs
            val reachedMax = (groupEnd - groupStart) >= maxMs
            if (reachedMax || reachedMin) {
                flush()
            }
        }
        flush()

        // Safety net: a single slot longer than maxMs produces an arc longer than max
        // (unavoidable — slots are never split). Concat QA checks profile equality,
        // not duration.
        return arcs
    }

    /** Total video duration covered by the plan (last slot end). */
    fun totalDurationMs(timeline: Timeline): Long =
        timeline.slots.maxOfOrNull { it.endMs } ?: 0L
}
