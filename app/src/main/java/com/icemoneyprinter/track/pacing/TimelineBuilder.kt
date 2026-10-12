package com.icemoneyprinter.track.pacing

import com.icemoneyprinter.track.model.AudioStructure
import com.icemoneyprinter.track.model.ImageItem
import com.icemoneyprinter.track.model.PacingConfig
import com.icemoneyprinter.track.model.Slot
import com.icemoneyprinter.track.model.Timeline
import kotlin.math.ceil

/**
 * Auto-mapping timeline builder (M1): images are assigned sequentially to speech spans,
 * split at span boundaries, with the plan's pacing rules applied:
 *
 * - min 3s / max 6s for auto slots (span longer than max is split across images)
 * - slots shorter than min borrow time from the following dead air (pause)
 * - timeline always starts at 0 and ends at max(last slot, audio end) so the
 *   narration tail is never clipped
 * - pool wrap-around with a no-repeat-within-N penalty
 *
 * Explicit tap boundaries (M2) bypass min/max; this builder stays pure-Kotlin and
 * unit-tested.
 */
class TimelineBuilder {

    fun buildAuto(
        pool: List<ImageItem>,
        structure: AudioStructure,
        config: PacingConfig = PacingConfig(),
    ): Timeline {
        require(pool.isNotEmpty()) { "image pool is empty" }
        if (structure.spans.isEmpty()) {
            // Degenerate narration (no detectable speech): cover the whole duration
            // with the first N images at equal intervals rather than failing.
            val fallback = structure.durationMs.takeIf { it > 0 } ?: config.maxAutoSlotMs
            val count = ceil(fallback.toDouble() / config.maxAutoSlotMs).toInt().coerceAtLeast(1)
            val each = fallback / count
            val spills = (0 until count).map { i ->
                val item = pool[i % pool.size]
                Slot(item.uriString, item.contentHash, i * each, (i + 1) * each)
            }
            return Timeline(spills, structure.durationMs)
        }

        val slots = ArrayList<Slot>()
        val recent = ArrayDeque<Int>()
        var cursor = 0L

        fun nextImageIndex(): Int {
            for (k in pool.indices) {
                if (pool.size > config.noRepeatWithinSlots && recent.contains(k)) continue
                recent.addLast(k)
                while (recent.size > config.noRepeatWithinSlots) recent.removeFirst()
                return k
            }
            val k = pool.size - 1
            recent.addLast(k)
            while (recent.size > config.noRepeatWithinSlots) recent.removeFirst()
            return k
        }

        for (span in structure.spans) {
            val spanStart = span.startMs
            val duration = (span.endMs - span.startMs).coerceAtLeast(1)
            val count = ceil(duration.toDouble() / config.maxAutoSlotMs).toInt().coerceAtLeast(1)
            val each = duration / count
            for (i in 0 until count) {
                val idx = nextImageIndex()
                val start = spanStart + i * each
                val end = if (i == count - 1) spanStart + duration else spanStart + (i + 1) * each
                slots.add(Slot(pool[idx].uriString, pool[idx].contentHash, start, end))
            }
        }

        // Dead-air borrow: extend short slots forward into the pause that follows them.
        for (i in slots.indices) {
            val slot = slots[i]
            if (slot.durationMs < config.minAutoSlotMs) {
                val nextStart = slots.getOrNull(i + 1)?.startMs
                val limit = nextStart ?: (structure.durationMs)
                if (limit > slot.endMs) {
                    slots[i] = slot.copy(endMs = limit)
                }
            }
        }

        // First slot covers from 0 (leading silence), last covers to audio end.
        if (slots.isNotEmpty() && slots[0].startMs > 0) {
            slots[0] = slots[0].copy(startMs = 0)
        }
        if (slots.isNotEmpty()) {
            val last = slots.size - 1
            val end = maxOf(slots[last].endMs, structure.durationMs)
            slots[last] = slots[last].copy(endMs = end)
        }

        // Enforce no-overlap / monotonicity (defensive).
        for (i in 1 until slots.size) {
            if (slots[i].startMs < slots[i - 1].endMs) {
                slots[i] = slots[i].copy(startMs = slots[i - 1].endMs)
            }
        }

        return Timeline(slots, structure.durationMs)
    }
}
