package com.icemoneyprinter.track.audio

import com.icemoneyprinter.track.model.SpeechSpan

/**
 * Maps script sentences onto detected speech spans for "where am I" highlighting
 * during guided tapping.
 *
 * Without word timestamps (plan D2) we don't know exactly which span carries which
 * sentence, so we align by order: sentences are distributed across spans in
 * proportion to cumulative character count, which is a good estimate because both
 * speech rate and sentence length are roughly proportional to characters.
 */
object ScriptAligner {

    /** Returns one entry per span: the script text that most likely covers it. */
    fun align(sentences: List<String>, spans: List<SpeechSpan>): List<String> {
        if (sentences.isEmpty() || spans.isEmpty()) return emptyList()
        if (sentences.size == spans.size) {
            return sentences.map { it.trim() }.filter { it.isNotEmpty() }.padTo(spans.size)
        }

        val lengths = sentences.map { it.length.coerceAtLeast(1) }
        val totalChars = lengths.sum().toLong()
        val cumChars = ArrayList<Long>(sentences.size)
        var running = 0L
        for (len in lengths) {
            running += len
            cumChars.add(running)
        }

        val out = ArrayList<String>(spans.size)
        var next = 0
        var lastText = sentences.last().trim()
        for (j in spans.indices) {
            val target = (j + 1) * totalChars / spans.size
            val sb = StringBuilder()
            while (next < sentences.size && cumChars[next] <= target) {
                sb.append(sentences[next].trim()).append(' ')
                next++
            }
            if (sb.isEmpty() && next < sentences.size) {
                sb.append(sentences[next].trim())
                next++
            }
            val text = sb.toString().trim()
            if (text.isNotEmpty()) lastText = text
            out.add(if (text.isEmpty()) lastText else text)
        }
        return out
    }
}

private fun List<String>.padTo(size: Int): List<String> {
    if (this.size == size) return this
    val out = ArrayList(this)
    while (out.size < size) out.add(lastOrNull() ?: "")
    return out
}
