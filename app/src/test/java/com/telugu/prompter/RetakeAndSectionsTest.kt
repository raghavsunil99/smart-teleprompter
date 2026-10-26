package com.telugu.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetakeAndSectionsTest {

    private val script = "# Intro\n" +
        "Namaskaram andariki. Ee roju manam oka kotha vishayam gurinchi maatladukundaam.\n" +
        "# Main point\n" +
        "Meeru ee video chivari varaku chudandi. Channel ni subscribe cheyyandi."

    @Test
    fun headingsAreSectionsAndNotMatched() {
        val m = ScriptMatcher(script)
        assertEquals(listOf("Intro", "Main point"), m.sections.map { it.title })
        assertEquals("Namaskaram", m.words[0].text)
        assertEquals(0, m.sections[0].wordIndex)
        assertEquals("Meeru", m.words[m.sections[1].wordIndex].text)
        assertTrue(m.words.none { it.text == "Intro" || it.text == "Main" })
    }

    @Test
    fun readingFlowsAcrossAHeading() {
        val m = ScriptMatcher(script)
        m.onHeard("నమస్కారం అందరికీ ఈ రోజు మనం ఒక కొత్త విషయం గురించి మాట్లాడుకుందాం")
        assertEquals(10, m.position)
        assertTrue(m.onHeard("మీరు ఈ వీడియో"))
        assertEquals(13, m.position)
        assertEquals(1, m.sectionAt(m.position))
    }

    @Test
    fun retakeJumpsBackToTheSentenceStart() {
        val m = ScriptMatcher(script)
        m.onHeard("నమస్కారం అందరికీ ఈ రోజు మనం ఒక కొత్త విషయం")
        assertEquals(8, m.position)
        // Speaker stops and starts sentence 2 ("Ee roju manam...") again.
        assertFalse(m.onHeard("ఈ రోజు మనం")) // first sighting: wait for confirmation
        assertEquals(8, m.position)
        assertTrue(m.onHeard("ఈ రోజు మనం ఒక")) // confirmed
        assertEquals(6, m.position)
    }

    @Test
    fun twoWordsAreNotEnoughForARetake() {
        val m = ScriptMatcher(script)
        m.onHeard("నమస్కారం అందరికీ ఈ రోజు మనం ఒక కొత్త విషయం")
        assertFalse(m.onHeard("ఈ రోజు"))
        assertFalse(m.onHeard("ఈ రోజు"))
        assertEquals(8, m.position)
    }

    @Test
    fun retakeMustStartAtASentence() {
        val m = ScriptMatcher(script)
        m.onHeard("నమస్కారం అందరికీ ఈ రోజు మనం ఒక కొత్త విషయం గురించి మాట్లాడుకుందాం మీరు ఈ వీడియో చివరి వరకు")
        val pos = m.position
        // Mid-sentence words from earlier ("oka kotha vishayam") are not a retake.
        assertFalse(m.onHeard("ఒక కొత్త విషయం"))
        assertFalse(m.onHeard("ఒక కొత్త విషయం గురించి"))
        assertEquals(pos, m.position)
    }
}
