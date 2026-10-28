package com.telugu.prompter

/**
 * Turns words into a rough Latin "sound key", so the same word compares equal whether it's
 * written in Telugu, Malayalam or Tamil letters or typed in English letters:
 * నమస్కారం (Telugu), നമസ്കാരം (Malayalam), நமஸ்காரம் (Tamil) and namaskaram all get the same key.
 *
 * Telugu, Malayalam and Tamil sit in Unicode blocks with the same layout (the same letter is at
 * the same offset in each), so one table, indexed by offset within the block, covers all three.
 */
object Phonetic {

    private const val TELUGU_BASE = 0x0C00
    private const val MALAYALAM_BASE = 0x0D00
    private const val TAMIL_BASE = 0x0B80

    /** Offset of [c] within its Indic block, or -1 if it isn't Telugu, Malayalam or Tamil. */
    private fun offsetOf(c: Char): Int = when (c.code) {
        in TELUGU_BASE..TELUGU_BASE + 0x7F -> c.code - TELUGU_BASE
        in MALAYALAM_BASE..MALAYALAM_BASE + 0x7F -> c.code - MALAYALAM_BASE
        in TAMIL_BASE..TAMIL_BASE + 0x7F -> c.code - TAMIL_BASE
        else -> -1
    }

    private val VOWELS = mapOf(
        0x05 to "a", 0x06 to "aa", 0x07 to "i", 0x08 to "ii", 0x09 to "u", 0x0A to "uu",
        0x0B to "ru", 0x0C to "lu", 0x0E to "e", 0x0F to "e", 0x10 to "ai",
        0x12 to "o", 0x13 to "o", 0x14 to "au", 0x60 to "ruu",
    )

    private val VOWEL_SIGNS = mapOf(
        0x3E to "aa", 0x3F to "i", 0x40 to "ii", 0x41 to "u", 0x42 to "uu",
        0x43 to "ru", 0x44 to "ruu", 0x46 to "e", 0x47 to "e", 0x48 to "ai",
        0x4A to "o", 0x4B to "o", 0x4C to "au",
    )

    private val CONSONANTS = mapOf(
        0x15 to "k", 0x16 to "kh", 0x17 to "g", 0x18 to "gh", 0x19 to "n",
        0x1A to "ch", 0x1B to "chh", 0x1C to "j", 0x1D to "jh", 0x1E to "n",
        0x1F to "t", 0x20 to "th", 0x21 to "d", 0x22 to "dh", 0x23 to "n",
        0x24 to "t", 0x25 to "th", 0x26 to "d", 0x27 to "dh", 0x28 to "n",
        0x29 to "n", // Tamil ன, Malayalam ഩ
        0x2A to "p", 0x2B to "ph", 0x2C to "b", 0x2D to "bh", 0x2E to "m",
        0x2F to "y", 0x30 to "r", 0x31 to "r", 0x32 to "l", 0x33 to "l",
        0x34 to "zh", // Tamil ழ, Malayalam ഴ (tamizh / tamil), Telugu ఴ
        0x35 to "v", 0x36 to "sh", 0x37 to "sh", 0x38 to "s", 0x39 to "h",
    )

    /** Malayalam chillu letters: a consonant with no vowel, written as one letter. */
    private val CHILLUS = mapOf(
        0x7A to "n", 0x7B to "n", 0x7C to "r", 0x7D to "l", 0x7E to "l", 0x7F to "k",
    )

    private const val VIRAMA = 0x4D
    private const val ANUSVARA = 0x02
    private const val VISARGA = 0x03
    private const val CHANDRABINDU = 0x01

    /** Telugu, Malayalam or Tamil letters → plain Latin letters. Latin input passes through unchanged. */
    fun transliterate(word: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < word.length) {
            val c = word[i]
            val o = offsetOf(c)
            if (o < 0) {
                out.append(c)
                i++
                continue
            }
            val telugu = c.code in TELUGU_BASE..TELUGU_BASE + 0x7F
            val cons = CONSONANTS[o] ?: if (telugu && o == 0x58) "ts" else if (telugu && o == 0x59) "dz" else null
            when {
                cons != null -> {
                    out.append(cons)
                    val next = word.getOrNull(i + 1)?.let { offsetOf(it) } ?: -1
                    when {
                        next == VIRAMA -> i++
                        VOWEL_SIGNS.containsKey(next) -> { out.append(VOWEL_SIGNS[next]); i++ }
                        else -> out.append('a')
                    }
                }
                VOWELS.containsKey(o) -> out.append(VOWELS[o])
                VOWEL_SIGNS.containsKey(o) -> out.append(VOWEL_SIGNS[o])
                !telugu && CHILLUS.containsKey(o) -> out.append(CHILLUS[o])
                o == ANUSVARA -> out.append('m')
                o == CHANDRABINDU -> out.append('n')
                o == VISARGA -> out.append('h')
                o in 0x66..0x6F -> out.append('0' + (o - 0x66))
                // Length marks, dot reph and other signs don't change the sound key.
            }
            i++
        }
        return out.toString()
    }

    private val NON_ALNUM = Regex("[^a-z0-9]")
    private val ASPIRATE = Regex("([kgcjtdbsr])h")
    private val ANUSVARA_M = Regex("m(?=[kgcjtdnsrlvyh])")
    private val DOUBLES = Regex("(.)\\1+")
    private val SOFT_C = Regex("c(?=[eiy])")
    private val HARD_C = Regex("c(?![h])")
    private val VOWEL_CHARS = Regex("[aeiou]")

    /** Latin text → sound key that ignores the usual spelling variations of typed Indian languages. */
    fun key(latin: String, language: ScriptLanguage = ScriptLanguage.TELUGU): String {
        var s = latin.lowercase().replace(NON_ALNUM, "")
        s = s.replace("zh", "l")                 // tamizh = tamil, ezhuthu = eluthu
        s = s.replace("ee", "i").replace("ii", "i").replace("oo", "u").replace("uu", "u")
        s = s.replace("ph", "f").replace("x", "ks").replace("q", "k").replace("z", "j").replace("w", "v")
        s = s.replace(SOFT_C, "s").replace(HARD_C, "k") // English c: city → sity, camera → kamera
        s = s.replace(ASPIRATE, "$1").replace(ASPIRATE, "$1") // th→t, dh→d, sh→s, ch→c, chh→c ...
        s = s.replace(ANUSVARA_M, "n")           // samtosham → santosam
        if (language == ScriptLanguage.TAMIL) {
            // Tamil writes k/g, t/d, p/b and ch/s/j with the same letters (உங்கள் is said "ungal").
            s = s.replace('g', 'k').replace('d', 't').replace('b', 'p').replace('j', 's').replace('c', 's')
        }
        s = s.replace(DOUBLES, "$1")             // kotta → kota, aa → a
        return s
    }

    fun normalize(word: String, language: ScriptLanguage = ScriptLanguage.TELUGU): String =
        key(transliterate(word), language)

    /** Consonants only, for English words whose spelling doesn't follow sound (subscribe ≈ sabskraib). */
    fun skeleton(key: String): String = key.replace(VOWEL_CHARS, "")
}
