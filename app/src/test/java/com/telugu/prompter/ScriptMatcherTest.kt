package com.telugu.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptMatcherTest {

    private val script = "నమస్కారం అందరికీ. ఈ రోజు మనం ఒక కొత్త విషయం గురించి మాట్లాడుకుందాం. " +
        "మీరు ఈ వీడియో చివరి వరకు చూడండి. ఈ రోజు మనం చాలా విషయాలు నేర్చుకుందాం."

    @Test
    fun tokenizesTelugu() {
        val m = ScriptMatcher(script)
        assertEquals("నమస్కారం", m.words[0].text)
        assertEquals(22, m.words.size)
    }

    @Test
    fun followsPartialResultsAsTheyGrow() {
        val m = ScriptMatcher(script)
        assertTrue(m.onHeard("నమస్కారం అందరికీ"))
        assertEquals(2, m.position)
        assertTrue(m.onHeard("నమస్కారం అందరికీ ఈ రోజు మనం"))
        assertEquals(5, m.position)
    }

    @Test
    fun toleratesSpellingDifferences() {
        val m = ScriptMatcher(script)
        // Recognizer drops/changes a few vowel signs.
        assertTrue(m.onHeard("నమస్కారము అందరికి"))
        assertEquals(2, m.position)
    }

    @Test
    fun newUtteranceAfterPauseContinues() {
        val m = ScriptMatcher(script)
        m.onHeard("నమస్కారం అందరికీ ఈ రోజు మనం ఒక కొత్త విషయం గురించి మాట్లాడుకుందాం")
        assertEquals(10, m.position)
        assertTrue(m.onHeard("మీరు ఈ వీడియో"))
        assertEquals(13, m.position)
    }

    @Test
    fun repeatedPhraseDoesNotJumpAhead() {
        val m = ScriptMatcher(script)
        // "ఈ రోజు మనం" appears twice; at the start it must match the first one.
        m.onHeard("నమస్కారం అందరికీ ఈ రోజు మనం")
        assertEquals(5, m.position)
    }

    @Test
    fun ignoresUnrelatedSpeech() {
        val m = ScriptMatcher(script)
        assertFalse(m.onHeard("హలో టెస్టింగ్ మైక్"))
        assertEquals(0, m.position)
    }

    @Test
    fun skippedSentenceCatchesUp() {
        val m = ScriptMatcher(script)
        m.onHeard("నమస్కారం అందరికీ")
        assertTrue(m.onHeard("మీరు ఈ వీడియో చివరి వరకు"))
        assertEquals(15, m.position)
    }

    @Test
    fun neverMovesBackwards() {
        val m = ScriptMatcher(script)
        m.onHeard("నమస్కారం అందరికీ ఈ రోజు మనం ఒక కొత్త")
        val pos = m.position
        assertFalse(m.onHeard("నమస్కారం అందరికీ"))
        assertEquals(pos, m.position)
    }
}
