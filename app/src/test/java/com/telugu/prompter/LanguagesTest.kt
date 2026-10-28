package com.telugu.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Malayalam, Tamil and English alongside Telugu: detection, sound keys, and following speech. */
class LanguagesTest {

    private val malayalam = "എല്ലാവർക്കും നമസ്കാരം. ഇന്ന് നമ്മൾ ഒരു പുതിയ കാര്യത്തെക്കുറിച്ച് സംസാരിക്കാം."
    private val malayalamInEnglishLetters =
        "Ellavarkkum namaskaram. Innu nammal oru puthiya kaaryathekkurichu samsaarikkaam."
    private val tamil = "அனைவருக்கும் வணக்கம். இன்று நாம் ஒரு புதிய விஷயத்தைப் பற்றி பேசலாம்."
    private val tamilInEnglishLetters = "Anaivarukkum vanakkam. Indru naam oru pudhiya vishayathai patri pesalaam."

    @Test
    fun detectsTheLanguageFromTheLetters() {
        assertEquals(ScriptLanguage.TELUGU, ScriptLanguage.detect("నమస్కారం అందరికీ, ఈ రోజు video చూడండి"))
        assertEquals(ScriptLanguage.MALAYALAM, ScriptLanguage.detect(malayalam))
        assertEquals(ScriptLanguage.TAMIL, ScriptLanguage.detect(tamil))
        // English letters could be any of the four languages: the user's choice decides.
        assertNull(ScriptLanguage.detect(malayalamInEnglishLetters))
        assertNull(ScriptLanguage.detect("Hello everyone, today let's talk about something new"))
    }

    @Test
    fun malayalamLettersAndEnglishLettersGetTheSameKey() {
        val ml = ScriptLanguage.MALAYALAM
        assertEquals(Phonetic.normalize("namaskaram", ml), Phonetic.normalize("നമസ്കാരം", ml))
        assertEquals(Phonetic.normalize("avan", ml), Phonetic.normalize("അവൻ", ml)) // chillu ൻ
        assertEquals(Phonetic.normalize("ellavarkkum", ml), Phonetic.normalize("എല്ലാവർക്കും", ml))
        assertEquals(Phonetic.normalize("nammal", ml), Phonetic.normalize("നമ്മൾ", ml))
        assertEquals(Phonetic.normalize("samsaarikkaam", ml), Phonetic.normalize("സംസാരിക്കാം", ml))
    }

    @Test
    fun tamilLettersAndEnglishLettersGetTheSameKey() {
        val ta = ScriptLanguage.TAMIL
        assertEquals(Phonetic.normalize("vanakkam", ta), Phonetic.normalize("வணக்கம்", ta))
        // Tamil writes g/k with one letter: உங்கள் is said "ungal".
        assertEquals(Phonetic.normalize("ungal", ta), Phonetic.normalize("உங்கள்", ta))
        assertEquals(Phonetic.normalize("tamizh", ta), Phonetic.normalize("தமிழ்", ta))
        assertEquals(Phonetic.normalize("tamil", ta), Phonetic.normalize("தமிழ்", ta))
        assertEquals(Phonetic.normalize("pudhiya", ta), Phonetic.normalize("புதிய", ta))
    }

    @Test
    fun teluguKeysAreUnchanged() {
        assertEquals(Phonetic.normalize("namaskaram"), Phonetic.normalize("నమస్కారం"))
        assertEquals(Phonetic.normalize("kotha"), Phonetic.normalize("కొత్త"))
        assertEquals(Phonetic.normalize("gurinchi"), Phonetic.normalize("గురించి"))
    }

    private fun followsEveryWord(script: String, heardWords: List<String>, language: ScriptLanguage) {
        val m = ScriptMatcher(script, language)
        assertEquals(heardWords.size, m.words.size)
        val heard = mutableListOf<String>()
        heardWords.forEachIndexed { i, w ->
            heard += w
            m.onHeard(heard.takeLast(12).joinToString(" "))
            assertTrue("word $i '$w': at ${m.position}", m.position in i..i + 1)
        }
        assertEquals(m.words.size, m.position)
    }

    @Test
    fun followsMalayalamSpeechThroughBothKindsOfScript() {
        val speech = ScriptMatcher.tokenize(malayalam).map { it.text } // the recognizer writes Malayalam letters
        followsEveryWord(malayalam, speech, ScriptLanguage.MALAYALAM)
        followsEveryWord(malayalamInEnglishLetters, speech, ScriptLanguage.MALAYALAM)
    }

    @Test
    fun followsTamilSpeechThroughBothKindsOfScript() {
        val speech = ScriptMatcher.tokenize(tamil).map { it.text }
        followsEveryWord(tamil, speech, ScriptLanguage.TAMIL)
        followsEveryWord(tamilInEnglishLetters, speech, ScriptLanguage.TAMIL)
    }

    @Test
    fun followsEnglishSpeech() {
        val script = "Hello everyone. Today let's talk about three free tools that save you hours every week."
        val speech = listOf("hello", "everyone", "today", "let's", "talk", "about", "3", "free", "tools", "that",
            "save", "you", "hours", "every", "week")
        val m = ScriptMatcher(script, ScriptLanguage.ENGLISH)
        val heard = mutableListOf<String>()
        speech.forEach { heard += it; m.onHeard(heard.takeLast(12).joinToString(" ")) }
        assertTrue("ended at ${m.position} of ${m.words.size}", m.position >= m.words.size - 1)
    }
}
