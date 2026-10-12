package com.icemoneyprinter.track.model

/**
 * One image in the pool. Identity is the content hash so re-picks and re-sorts are
 * stable. The URI is kept as a String so the pacing/audio layers stay Android-free
 * (and unit-testable); the render layer parses it at the edge.
 */
data class ImageItem(
    val uriString: String,
    val displayName: String,
    val contentHash: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** A pause/silence region detected in the narration, in ms. */
data class PauseRegion(val startMs: Long, val endMs: Long)

/** A continuous speech region of the narration, in ms. */
data class SpeechSpan(val startMs: Long, val endMs: Long)

/** Structure of the narration as derived from energy analysis (plan decision D2). */
data class AudioStructure(
    val durationMs: Long,
    val spans: List<SpeechSpan>,
    val pauses: List<PauseRegion>,
    val estimatedSegmentation: Boolean,
)

/** One image slot on the timeline. */
data class Slot(
    val imageUriString: String,
    val imageHash: String,
    val startMs: Long,
    val endMs: Long,
    /** true when the boundary came from a user tap (bypasses pacing min/max; M2+) */
    val explicit: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
}

/** The complete plan for the video. */
data class Timeline(
    val slots: List<Slot>,
    val audioDurationMs: Long,
) {
    val durationMs: Long get() = slots.maxOfOrNull { it.endMs } ?: 0L
}

/** Pacing rule constants (M4 makes these user-tunable). */
data class PacingConfig(
    val minAutoSlotMs: Long = 3_000,
    val maxAutoSlotMs: Long = 6_000,
    val explicitFloorMs: Long = 600,
    val deadAirFreezeMs: Long = 1_500,
    val noRepeatWithinSlots: Int = 5,
)
