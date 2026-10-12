package com.icemoneyprinter.track.audio

import com.icemoneyprinter.track.model.AudioStructure
import com.icemoneyprinter.track.model.PauseRegion
import com.icemoneyprinter.track.model.SpeechSpan
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Energy-based speech structure detection (plan decision D2: no ASR, no models).
 *
 * Clean TTS audio is ideal for this: the noise floor is near-digital-silence and Gemini
 * inserts real pauses at sentence/paragraph boundaries. The analyzer therefore only
 * needs a decent threshold, not a model.
 */
class EnergyAnalyzer(
    private val frameMs: Int = 20,
    private val mergeGapMs: Long = 120,
    private val minPauseMs: Long = 150,
) {

    fun analyze(pcm: PcmMono): AudioStructure {
        val samples = pcm.samples
        val sampleRate = pcm.sampleRate
        val durationMs = if (sampleRate == 0) 0 else samples.size * 1000L / sampleRate
        if (samples.isEmpty() || sampleRate <= 0) {
            return AudioStructure(durationMs, emptyList(), emptyList(), estimatedSegmentation = true)
        }

        val frameSize = maxOf(1, sampleRate * frameMs / 1000)
        val frameCount = ceil(samples.size.toDouble() / frameSize).toInt()
        val frameDb = DoubleArray(frameCount)

        var speechPeakDb = -120.0
        for (f in 0 until frameCount) {
            val from = f * frameSize
            val to = minOf(from + frameSize, samples.size)
            var sum = 0.0
            for (i in from until to) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            val rms = sqrt(sum / maxOf(1, to - from))
            frameDb[f] = if (rms <= 0.0) {
                -120.0
            } else {
                val db = 20.0 * log10(rms) - 20.0 * log10(32768.0)
                if (db > speechPeakDb) speechPeakDb = db
                db
            }
        }

        // Threshold relative to the speech peak, not to the low percentile of frames:
        // continuous-speech audio has no silence frames, so a percentile-based floor
        // would sit ON the speech level and classify everything as silence.
        val sorted = frameDb.copyOf().also { it.sort() }
        val thresholdDb = maxOf(speechPeakDb - 35.0, sorted.first() + 6.0)

        val isSpeech = BooleanArray(frameCount) { frameDb[it] > thresholdDb }

        val frameMsL = frameMs.toLong()
        val spans = ArrayList<SpeechSpan>()
        val pauses = ArrayList<PauseRegion>()

        var f = 0
        var pendingSpeechStart = -1
        var pendingPauseStart = -1
        var lastSpeechEndFrame = -1

        while (f < frameCount) {
            if (isSpeech[f]) {
                if (pendingPauseStart >= 0) {
                    val pauseLenMs = (f - pendingPauseStart) * frameMsL
                    if (pauseLenMs >= minPauseMs) {
                        if (pendingSpeechStart >= 0) {
                            spans.add(SpeechSpan(pendingSpeechStart * frameMsL, pendingPauseStart * frameMsL))
                        }
                        pauses.add(
                            PauseRegion(
                                if (pendingSpeechStart >= 0) pendingPauseStart * frameMsL else 0,
                                f * frameMsL,
                            ),
                        )
                        pendingSpeechStart = f
                    }
                    // short gap: merge, keep current span open
                    pendingPauseStart = -1
                }
                if (pendingSpeechStart < 0) pendingSpeechStart = f
                lastSpeechEndFrame = f
            } else if (pendingSpeechStart >= 0 && pendingPauseStart < 0) {
                pendingPauseStart = f
            }
            f++
        }
        if (pendingSpeechStart >= 0) {
            val endFrame = if (pendingPauseStart >= 0) pendingPauseStart else frameCount
            spans.add(SpeechSpan(pendingSpeechStart * frameMsL, endFrame * frameMsL))
        }

        // Collapse tiny gaps *inside* spans that survived merging (defensive).
        val mergedSpans = mergeSpans(spans, mergeGapMs)
        val rePauses = derivePauses(mergedSpans, durationMs)

        val noSpeech = mergedSpans.isEmpty() || mergedSpans.sumOf { it.endMs - it.startMs } < durationMs / 20
        return AudioStructure(
            durationMs = durationMs,
            spans = mergedSpans,
            pauses = rePauses,
            estimatedSegmentation = noSpeech,
        )
    }

    private fun mergeSpans(spans: List<SpeechSpan>, mergeGapMs: Long): List<SpeechSpan> {
        if (spans.isEmpty()) return spans
        val out = ArrayList<SpeechSpan>()
        var cur = spans[0]
        for (i in 1 until spans.size) {
            val next = spans[i]
            if (next.startMs - cur.endMs < mergeGapMs) {
                cur = SpeechSpan(cur.startMs, next.endMs)
            } else {
                out.add(cur)
                cur = next
            }
        }
        out.add(cur)
        return out
    }

    private fun derivePauses(spans: List<SpeechSpan>, durationMs: Long): List<PauseRegion> {
        val pauses = ArrayList<PauseRegion>()
        var cursor = 0L
        for (s in spans) {
            if (s.startMs - cursor >= minPauseMs) pauses.add(PauseRegion(cursor, s.startMs))
            cursor = s.endMs
        }
        if (durationMs - cursor >= minPauseMs) pauses.add(PauseRegion(cursor, durationMs))
        return pauses
    }
}
