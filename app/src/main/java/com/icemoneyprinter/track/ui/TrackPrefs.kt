package com.icemoneyprinter.track.ui

import android.content.Context
import android.content.SharedPreferences

/** User-tunable pacing + quality settings (plan M4). */
data class TrackSettings(
    val minAutoSlotMs: Long = 3_000,
    val maxAutoSlotMs: Long = 6_000,
    val explicitFloorMs: Long = 600,
    val musicGain: Float = 0.35f,
    val highQuality: Boolean = true,
) {
    val pac: com.icemoneyprinter.track.model.PacingConfig
        get() = com.icemoneyprinter.track.model.PacingConfig(
            minAutoSlotMs = minAutoSlotMs,
            maxAutoSlotMs = maxAutoSlotMs,
            explicitFloorMs = explicitFloorMs,
        )
}

object TrackPrefs {

    private const val NAME = "track_prefs"
    private const val KEY_MIN = "min_auto_slot_ms"
    private const val KEY_MAX = "max_auto_slot_ms"
    private const val KEY_FLOOR = "explicit_floor_ms"
    private const val KEY_GAIN = "music_gain"
    private const val KEY_HQ = "high_quality"

    fun load(context: Context): TrackSettings {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return TrackSettings(
            minAutoSlotMs = p.getLong(KEY_MIN, 3_000),
            maxAutoSlotMs = p.getLong(KEY_MAX, 6_000),
            explicitFloorMs = p.getLong(KEY_FLOOR, 600),
            musicGain = p.getFloat(KEY_GAIN, 0.35f),
            highQuality = p.getBoolean(KEY_HQ, true),
        )
    }

    fun save(context: Context, s: TrackSettings) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_MIN, s.minAutoSlotMs)
            .putLong(KEY_MAX, s.maxAutoSlotMs)
            .putLong(KEY_FLOOR, s.explicitFloorMs)
            .putFloat(KEY_GAIN, s.musicGain)
            .putBoolean(KEY_HQ, s.highQuality)
            .apply()
    }
}
