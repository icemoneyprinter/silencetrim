package com.icemoneyprinter.track.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordsJsonTest {

    /** transcribe-style shape: seconds, nested keys. */
    private val secondsJson = """
        {
          "words": [
            {"word": "Napoleon", "start": 0.0, "end": 0.62},
            {"word": "Bonaparte", "start": 0.62, "end": 1.31},
            {"word": "wasn't", "start": 1.42, "end": 1.83},
            {"word": "a", "start": 1.83, "end": 1.94},
            {"word": "small", "start": 1.94, "end": 2.4},
            {"word": "man", "start": 2.4, "end": 2.9}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses seconds into ms`() {
        val data = WordsJson.parse(secondsJson)
        assertNotNull(data)
        assertEquals(6, data!!.words.size.toLong())
        assertEquals(620L, data.words[0].endMs)
        assertEquals(1420L, data.words[2].startMs)
    }

    @Test
    fun `gap boundaries are post-gap starts`() {
        val data = WordsJson.parse(secondsJson)!!
        val gaps = data.gapBoundaries(150)
        // the 110ms Napoleon-internal gap is below threshold; no sentence gaps in this sample
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `real sentence gaps are detected`() {
        val json = """
            {"words":[
              {"word":"one","start":0,"end":1000},
              {"word":"two","start":1000,"end":2000},
              {"word":"three","start":2600,"end":3600},
              {"word":"four","start":3600,"end":4000}]}
        """.trimIndent()
        val data = WordsJson.parse(json)!!
        assertEquals(listOf(2600L), data.gapBoundaries(150))
    }

    @Test
    fun `wordIndexAt finds the current word`() {
        val data = WordsJson.parse(secondsJson)!!
        assertEquals(0, data.wordIndexAt(100))
        assertEquals(2, data.wordIndexAt(1500))
        assertEquals(5, data.wordIndexAt(2800))
        assertEquals(5, data.wordIndexAt(99_000))
    }

    @Test
    fun `milliseconds already in ms are kept`() {
        val msJson = """{"words":[{"word":"hi","start":0,"end":620},{"word":"there","start":700,"end":1300},{"word":"buddy","start":1400,"end":2000}]}"""
        val data = WordsJson.parse(msJson)!!
        assertEquals(620L, data.words[0].endMs)
        assertEquals(2000L, data.durationMs)
    }

    @Test
    fun `junk returns null`() {
        assertNull(WordsJson.parse("not json at all"))
        assertNull(WordsJson.parse("""{"words":[{"word":"x"}]}"""))
    }

    @Test
    fun `extraneous fields and ordering tolerated`() {
        val json = """
            {"result":1,"words":[
              {"end":1.5,"text":"alpha","extra":true,"start":0.5},
              {"end":2.5,"word":"beta","start":1.5},
              {"end":3.5,"word":"gamma","start":2.5}]}
        """.trimIndent()
        val data = WordsJson.parse(json)
        assertNotNull(data)
        assertEquals(3, data!!.words.size.toLong())
        assertEquals("alpha", data.words[0].word)
    }
}
