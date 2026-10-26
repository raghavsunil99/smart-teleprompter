package com.telugu.prompter

/**
 * Turns Telugu-script words and Tenglish (Telugu typed in English letters) words into
 * the same rough "sound key", so నమస్కారం, namaskaram and namaskaaram all compare equal.
 */
object Phonetic {

    private val VOWELS = mapOf(
        'అ' to "a", 'ఆ' to "aa", 'ఇ' to "i", 'ఈ' to "ii", 'ఉ' to "u", 'ఊ' to "uu",
        'ఋ' to "ru", 'ౠ' to "ruu", 'ఎ' to "e", 'ఏ' to "e", 'ఐ' to "ai",
        'ఒ' to "o", 'ఓ' to "o", 'ఔ' to "au",
    )

    private val VOWEL_SIGNS = mapOf(
        'ా' to "aa", 'ి' to "i", 'ీ' to "ii", 'ు' to "u", 'ూ' to "uu",
        'ృ' to "ru", 'ౄ' to "ruu", 'ె' to "e", 'ే' to "e", 'ై' to "ai",
        'ొ' to "o", 'ో' to "o", 'ౌ' to "au",
    )

    private val CONSONANTS = mapOf(
        'క' to "k", 'ఖ' to "kh", 'గ' to "g", 'ఘ' to "gh", 'ఙ' to "n",
        'చ' to "ch", 'ఛ' to "chh", 'జ' to "j", 'ఝ' to "jh", 'ఞ' to "n",
        'ట' to "t", 'ఠ' to "th", 'డ' to "d", 'ఢ' to "dh", 'ణ' to "n",
        'త' to "t", 'థ' to "th", 'ద' to "d", 'ధ' to "dh", 'న' to "n",
        'ప' to "p", 'ఫ' to "ph", 'బ' to "b", 'భ' to "bh", 'మ' to "m",
        'య' to "y", 'ర' to "r", 'ఱ' to "r", 'ల' to "l", 'ళ' to "l", 'ఴ' to "l",
        'వ' to "v", 'శ' to "sh", 'ష' to "sh", 'స' to "s", 'హ' to "h",
        'ౘ' to "ts", 'ౙ' to "dz",
    )

    private const val VIRAMA = '్'
    private const val ANUSVARA = 'ం'
    private const val VISARGA = 'ః'
    private const val CHANDRABINDU = 'ఁ'

    /** Telugu script → plain Latin letters. Latin input passes through unchanged. */
    fun transliterate(word: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < word.length) {
            val c = word[i]
            val cons = CONSONANTS[c]
            when {
                cons != null -> {
                    out.append(cons)
                    val next = word.getOrNull(i + 1)
                    when {
                        next == VIRAMA -> i++
                        next != null && VOWEL_SIGNS.containsKey(next) -> { out.append(VOWEL_SIGNS[next]); i++ }
                        else -> out.append('a')
                    }
                }
                VOWELS.containsKey(c) -> out.append(VOWELS[c])
                VOWEL_SIGNS.containsKey(c) -> out.append(VOWEL_SIGNS[c])
                c == ANUSVARA -> out.append('m')
                c == CHANDRABINDU -> out.append('n')
                c == VISARGA -> out.append('h')
                c in '౦'..'౯' -> out.append('0' + (c - '౦'))
                else -> out.append(c)
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

    /** Latin text → sound key that ignores the usual Tenglish spelling variations. */
    fun key(latin: String): String {
        var s = latin.lowercase().replace(NON_ALNUM, "")
        s = s.replace("ee", "i").replace("ii", "i").replace("oo", "u").replace("uu", "u")
        s = s.replace("ph", "f").replace("x", "ks").replace("q", "k").replace("z", "j").replace("w", "v")
        s = s.replace(SOFT_C, "s").replace(HARD_C, "k") // English c: city → sity, camera → kamera
        s = s.replace(ASPIRATE, "$1").replace(ASPIRATE, "$1") // th→t, dh→d, sh→s, ch→c, chh→c ...
        s = s.replace(ANUSVARA_M, "n")           // samtosham → santosam
        s = s.replace(DOUBLES, "$1")             // kotta → kota, aa → a
        return s
    }

    fun normalize(word: String): String = key(transliterate(word))

    /** Consonants only, for English words whose spelling doesn't follow sound (subscribe ≈ sabskraib). */
    fun skeleton(key: String): String = key.replace(VOWEL_CHARS, "")
}
