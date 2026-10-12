package com.icemoneyprinter.track.render

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.icemoneyprinter.track.audio.AacSample
import java.io.File
import java.nio.ByteBuffer

/**
 * Stitches rendered arc videos into the final MP4:
 *
 * - video: **stream copy** (byte-level, no re-encode) of every arc's video track in
 *   order, rebasing each segment's PTS to a continuous timeline
 * - audio: the narration's AAC samples (encoded exactly once, plan D8) written with
 *   their original timeline PTS
 *
 * Callers MUST verify profile equality up front via [ArcQa]. A mismatch means
 * stream copy is unsafe — the pipeline falls back to a single-pass re-render.
 */
object VideoConcat {

    /** Stream-copies [arcFiles] video, rebasing PTS so segments play continuously. */
    fun concatVideo(arcFiles: List<File>, out: File): Result<Unit> = runCatching {
        if (out.exists()) out.delete()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val extractors = arcFiles.map { file ->
                val ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
                val track = (0 until ex.trackCount)
                    .first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ex.selectTrack(track)
                ex to track
            }
            val firstFormat = extractors.first().first.getTrackFormat(extractors.first().second)
            muxer.addTrack(firstFormat)
            muxer.start()

            var offsetUs = 0L
            val buffer = ByteBuffer.allocateDirect(1 shl 20)
            val info = MediaCodec.BufferInfo()
            for ((ex, track) in extractors) {
                var segmentEndUs = offsetUs
                while (true) {
                    info.offset = 0
                    info.size = ex.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    val sampleTimeUs = ex.sampleTime
                    info.presentationTimeUs = sampleTimeUs + offsetUs
                    info.flags = ex.sampleFlags
                    muxer.writeSampleData(0, buffer, info)
                    segmentEndUs = offsetUs + sampleTimeUs
                    ex.advance()
                }
                // next segment starts right after this one's last sample
                val lastDurationUs = ex.getTrackFormat(track)
                    .let { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else 0L }
                offsetUs = maxOf(segmentEndUs, offsetUs + lastDurationUs)
                ex.unselectTrack(track)
                ex.release()
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /**
     * Full concat used by the pipeline: stream-copy the arc videos, then append the
     * single narration audio track.
     */
    fun concat(
        arcFiles: List<File>,
        audioFormat: MediaFormat,
        audioSamples: List<AacSample>,
        out: File,
    ): Result<Unit> = runCatching {
        if (out.exists()) out.delete()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val extractors = arcFiles.map { file ->
                val ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
                val track = (0 until ex.trackCount)
                    .first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ex.selectTrack(track)
                ex to track
            }
            muxer.addTrack(extractors.first().first.getTrackFormat(extractors.first().second))
            muxer.addTrack(audioFormat)
            muxer.start()

            // Video: stream-copy all arcs with continuous PTS.
            val buffer = ByteBuffer.allocateDirect(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var offsetUs = 0L
            for ((ex, track) in extractors) {
                var lastSampleUs = 0L
                while (true) {
                    info.offset = 0
                    info.size = ex.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    lastSampleUs = ex.sampleTime
                    info.presentationTimeUs = lastSampleUs + offsetUs
                    info.flags = ex.sampleFlags
                    muxer.writeSampleData(0, buffer, info)
                    ex.advance()
                }
                val durUs = ex.getTrackFormat(track)
                    .let { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else 0L }
                offsetUs = maxOf(lastSampleUs + 33_000, offsetUs + durUs)
                ex.unselectTrack(track)
                ex.release()
            }

            // Audio: single narration track on the original timeline.
            for (sample in audioSamples) {
                val buf = ByteBuffer.wrap(sample.data)
                info.offset = 0
                info.size = sample.data.size
                info.presentationTimeUs = sample.ptsUs
                info.flags = sample.flags
                muxer.writeSampleData(1, buf, info)
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
