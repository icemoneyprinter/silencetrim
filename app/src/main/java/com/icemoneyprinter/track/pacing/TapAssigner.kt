package com.icemoneyprinter.track.pacing

import com.icemoneyprinter.track.model.AudioStructure
import com.icemoneyprinter.track.model.Slot
import com.icemoneyprinter.track.model.Timeline

/** A user tap during guided mode: image tapped at [tapMs] of narration playback. */
data class TapRecord(
    val imageUriString: String,
    val imageHash: String,
    val tapMs: Long,
)

/** Result of turning a tap session into a timeline. */
data class TapAssignment(
    val timeline: Timeline,
    val droppedTapCount: Int,
)

/**
 * Guided-tap → timeline conversion (plan decisions D3/D4).
 *
 * A tap means "this image should START roughly where I tapped". Human reaction lag is
 * ~200-300ms, so the raw boundary is `tapMs - 250`. If a real pause edge sits within
 * ±600ms of that intent, the cut snaps to the pause edge (nearest one), which makes the
 * boundary land where speech actually breaks. No pause nearby (e.g. the Napoleon
 * mid-sentence case) → the corrected tap time is the boundary as-is.
 *
 * Taps sort by boundary; the first tapped image covers [0, first boundary); the last
 * tapped image runs to max(last slot, audio end) so the narration tail is never cut.
 * Taps that collide with the previous boundary below the explicit floor are dropped
 * (the earlier boundary wins), and counted in [droppedTapCount].
 */
class TapAssigner(
    private val tapLagMs: Long = 250,
    private val snapWindowMs: Long = 600,
    private val explicitFloorMs: Long = 600,
) {

    fun assignFromTaps(
        taps: List<TapRecord>,
        structure: AudioStructure,
    ): TapAssignment {
        if (taps.isEmpty()) {
            return TapAssignment(Timeline(emptyList(), structure.durationMs), 0)
        }

        // Pause edges are the snapping candidates (both boundaries of every pause).
        val edges = structure.pauses.flatMap { listOf(it.startMs, it.endMs) }.sorted()

        data class Bounded(val record: TapRecord, var boundary: Long)

        val bounded = taps.map { tap ->
            val intent = maxOf(0, tap.tapMs - tapLagMs)
            var boundary = intent
            if (edges.isNotEmpty()) {
                var best: Long? = null
                var bestDist = Long.MAX_VALUE
                for (edge in edges) {
                    val dist = kotlin.math.abs(edge - intent)
                    // '<=' tie-breaks toward the later edge (pause end): when the intent
                    // lands inside a pause, cutting at the pause end starts the new image
                        // with the next sentence.
                    if (dist <= snapWindowMs && dist <= bestDist) {
                        best = edge
                        bestDist = dist
                    }
                }
                if (best != null) boundary = best
            }
            Bounded(tap, boundary)
        }.sortedBy { it.boundary }

        // Enforce the explicit floor; on collision the earlier boundary wins.
        val slots = ArrayList<Slot>()
        var dropped = 0
        var prevBoundary = -1L

        for (b in bounded) {
            if (prevBoundary >= 0 && b.boundary < prevBoundary + explicitFloorMs) {
                dropped++
                continue
            }
            prevBoundary = b.boundary
            slots.add(
                Slot(
                    imageUriString = b.record.imageUriString,
                    imageHash = b.record.imageHash,
                    startMs = b.boundary,
                    endMs = b.boundary, // patched below
                ),
            )
        }

        if (slots.isEmpty()) {
            return TapAssignment(Timeline(emptyList(), structure.durationMs), dropped)
        }

        // First slot covers from 0; each slot runs to the next boundary.
        val first = slots[0]
        slots[0] = first.copy(startMs = 0)

        val end = maxOf(slots.last().startMs, structure.durationMs)
        for (i in slots.indices) {
            val start = slots[i].startMs
            val slotEnd = if (i == slots.lastIndex) end else slots[i + 1].startMs
            slots[i] = slots[i].copy(startMs = start, endMs = slotEnd)
        }

        return TapAssignment(Timeline(slots, structure.durationMs), dropped)
    }
}
