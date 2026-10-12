package com.icemoneyprinter.track.audio

/** One word with its timestamps (shape from gemini-3.5-transcribe word annotations). */
data class WordStamp(
    val word: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Word-level timing data from an optional words.json dropped in the folder
 * (exported from AI Studio's transcribe model). Upgrades tapping precision:
 * snapping lands on real word boundaries instead of pause edges, and the tap
 * screen can highlight the current word.
 *
 * Parsed defensively — any shape mismatch returns null and the pipeline keeps
 * working from pause-based snapping (plan D2 default).
 */
object WordsJson {

    /** Tolerant parser: fields matched by name, order irrelevant, extra fields ignored. */
    fun parse(text: String): WordsData? {
        // Flat scan for objects containing word/text/start/end-like keys.
        val objectPattern = Regex("""\{[^{}]*\}""")
        val startPattern = Regex(""""(start|startMs|start_ms|startOffset|begin)"\s*[:=]\s*"?([0-9.]+)"?""")
        val endPattern = Regex(""""(end|endMs|end_ms|endOffset|stop)"\s*[:=]\s*"?([0-9.]+)"?""")
        val wordPattern = Regex(""""(word|text|token|transcript)"\s*[:=]\s*"([^"]*)"""")

        val raw = ArrayList<Pair<String, Pair<Double, Double>>>()
        for (match in objectPattern.findAll(text)) {
            val obj = match.value
            val startMatch = startPattern.find(obj) ?: continue
            val endMatch = endPattern.find(obj) ?: continue
            val wordMatch = wordPattern.find(obj) ?: continue
            val startRaw = startMatch.groupValues[2].toDoubleOrNull() ?: continue
            val endRaw = endMatch.groupValues[2].toDoubleOrNull() ?: continue
            val word = wordMatch.groupValues[2].trim()
            if (word.isEmpty() || endRaw <= startRaw) continue
            raw.add(word to (startRaw to endRaw))
        }
        if (raw.size < 3) return null
        val sorted = raw.sortedBy { it.second.first }
        // Unit heuristic: transcribe reports seconds. A video whose last word ends under
        // 20 (i.e. clearly not a 20-second-long millisecond value) must be seconds; our
        // videos run minutes, so anything >= 20 is already milliseconds.
        val scale = if (sorted.last().second.second < 20.0) 1000.0 else 1.0
        val words = sorted.map { (word, times) ->
            WordStamp(
                word = word,
                startMs = (times.first * scale).toLong(),
                endMs = (times.second * scale).toLong(),
            )
        }
        return WordsData(words)
    }
}

data class WordsData(val words: List<WordStamp>) {
    val durationMs: Long get() = words.lastOrNull()?.endMs ?: 0L

    /** Word boundaries that represent real gaps (>= gapThresholdMs) between words. */
    fun gapBoundaries(gapThresholdMs: Long = 150): List<Long> {
        val out = ArrayList<Long>()
        for (i in 0 until words.size - 1) {
            if (words[i + 1].startMs - words[i].endMs >= gapThresholdMs) {
                out.add(words[i + 1].startMs)
            }
        }
        return out
    }

    /** All candidate boundaries (word starts and ends), ascending. */
    fun allBoundaries(): List<Long> = buildList {
        words.forEach { add(it.startMs); add(it.endMs) }
    }.sorted()

    fun wordIndexAt(positionMs: Long): Int {
        if (words.isEmpty()) return -1
        var lo = 0
        var hi = words.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (words[mid].endMs < positionMs) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
