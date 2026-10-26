package com.telugu.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TenglishTest {

    private val script = "Namaskaram andariki. Ee roju manam oka kotha vishayam gurinchi maatladukundaam. " +
        "Meeru ee video chivari varaku chudandi. Channel ni subscribe cheyyandi."

    @Test
    fun soundKeysMatchAcrossScripts() {
        assertEquals(Phonetic.normalize("namaskaram"), Phonetic.normalize("నమస్కారం"))
        assertEquals(Phonetic.normalize("Namaskaaram"), Phonetic.normalize("నమస్కారం"))
        assertEquals(Phonetic.normalize("kotha"), Phonetic.normalize("కొత్త"))
        assertEquals(Phonetic.normalize("gurinchi"), Phonetic.normalize("గురించి"))
        assertEquals(Phonetic.normalize("maatladukundaam"), Phonetic.normalize("మాట్లాడుకుందాం"))
        assertEquals(Phonetic.normalize("chudandi"), Phonetic.normalize("చూడండి"))
        assertEquals(Phonetic.normalize("meeru"), Phonetic.normalize("మీరు"))
        assertEquals(Phonetic.normalize("cheyyandi"), Phonetic.normalize("చెయ్యండి"))
    }

    @Test
    fun englishWordsWrittenInTeluguStillMatch() {
        assertTrue(ScriptMatcher.wordSimilarity(ScriptMatcher.normalize("video"), ScriptMatcher.normalize("వీడియో")) >= 0.6)
        assertTrue(ScriptMatcher.wordSimilarity(ScriptMatcher.normalize("subscribe"), ScriptMatcher.normalize("సబ్స్క్రైబ్")) >= 0.6)
        assertTrue(ScriptMatcher.wordSimilarity(ScriptMatcher.normalize("channel"), ScriptMatcher.normalize("ఛానల్")) >= 0.6)
    }

    @Test
    fun followsTeluguSpeechThroughTenglishScript() {
        val m = ScriptMatcher(script)
        assertTrue(m.onHeard("నమస్కారం అందరికీ"))
        assertEquals(2, m.position)
        assertTrue(m.onHeard("నమస్కారం అందరికీ ఈ రోజు మనం ఒక కొత్త విషయం గురించి మాట్లాడుకుందాం"))
        assertEquals(10, m.position)
        assertTrue(m.onHeard("మీరు ఈ వీడియో చివరి వరకు చూడండి"))
        assertEquals(16, m.position)
        assertTrue(m.onHeard("ఛానల్ ని సబ్స్క్రైబ్ చెయ్యండి"))
        assertEquals(20, m.position)
    }

    @Test
    fun englishWordsHeardInLatinAlsoMatch() {
        val m = ScriptMatcher(script)
        m.onHeard("నమస్కారం అందరికీ ఈ రోజు మనం ఒక కొత్త విషయం గురించి మాట్లాడుకుందాం మీరు ఈ")
        assertTrue(m.onHeard("video చివరి వరకు"))
        assertEquals(15, m.position)
    }

    @Test
    fun unrelatedSpeechDoesNotMove() {
        val m = ScriptMatcher(script)
        assertFalse(m.onHeard("హలో మైక్ టెస్టింగ్"))
        assertEquals(0, m.position)
    }
}
