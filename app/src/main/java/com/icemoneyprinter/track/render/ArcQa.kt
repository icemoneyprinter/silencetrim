package com.icemoneyprinter.track.render

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

/** Encoder profile of a rendered arc, used to gate stream-copy concat. */
data class ArcProfile(
    val mime: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val profile: Int,
    val level: Int,
)

/**
 * Per-arc QA (plan M3): verify every arc was encoded with identical codec settings
 * (required for a clean stream-copy concat) and that the first frame is not black
 * (a known device-encoder failure mode; the arc is re-rendered once on failure).
 */
object ArcQa {

    fun probe(file: File): ArcProfile? = runCatching {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var result: ArcProfile? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue
            result = ArcProfile(
                mime = mime,
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else {
                    0
                },
                profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                    format.getInteger(MediaFormat.KEY_PROFILE)
                } else {
                    -1
                },
                level = if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                    format.getInteger(MediaFormat.KEY_LEVEL)
                } else {
                    -1
                },
            )
            break
        }
        extractor.release()
        result
    }.getOrNull()

    fun sameProfile(a: ArcProfile, b: ArcProfile): Boolean =
        a.mime == b.mime &&
            a.width == b.width &&
            a.height == b.height &&
            a.frameRate == b.frameRate &&
            a.profile == b.profile &&
            a.level == b.level

    /** true when the first decoded frame is (near) all black. */
    fun firstFrameIsBlack(file: File): Boolean = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val bitmap: Bitmap? = retriever.getFrameAtTime(0)
        retriever.release()
        if (bitmap == null) {
            true
        } else {
            val w = bitmap.width.coerceAtMost(64)
            val h = bitmap.height.coerceAtMost(64)
            val scaled = if (w < bitmap.width || h < bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                bitmap
            }
            var sum = 0L
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += (r + g + b) / 3
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            (sum / pixels.size) < 10
        }
    }.getOrDefault(false)
}
