package com.telugu.prompter

import java.text.Normalizer

/** One word of the script, with where it sits in the original text. */
data class ScriptWord(val text: String, val norm: String, val start: Int, val end: Int)

/**
 * Follows a speaker through a script.
 *
 * The recognizer gives us a growing hypothesis of what was said in the current
 * utterance. We take the last few heard words and locally align them (Smith-Waterman)
 * against a window of the script just around the current position. Words are compared
 * with a fuzzy similarity on a phonetic key, so Telugu speech can follow a script typed
 * in Telugu letters or in Tenglish, despite small spelling differences.
 *
 * Position only ever moves forward; the user can tap a word to re-sync manually.
 */
class ScriptMatcher(text: String) {

    val words: List<ScriptWord> = tokenize(text)

    /** Index of the next word the speaker is expected to say (0..words.size). */
    var position: Int = 0
        private set

    fun reset(to: Int = 0) {
        position = to.coerceIn(0, words.size)
    }

    /**
     * Feed the latest recognizer hypothesis. Returns true when [position] moved.
     */
    fun onHeard(hypothesis: String): Boolean {
        if (words.isEmpty()) return false
        val heard = tokenize(hypothesis).map { it.norm }.filter { it.isNotEmpty() }
        if (heard.isEmpty()) return false
        val tail = heard.takeLast(TAIL_WORDS)

        val winStart = (position - BACK_WINDOW).coerceAtLeast(0)
        val winEnd = (position + AHEAD_WINDOW).coerceAtMost(words.size)
        if (winStart >= winEnd) return false
        val window = words.subList(winStart, winEnd)

        val m = tail.size
        val n = window.size
        val h = Array(m + 1) { DoubleArray(n + 1) }
        var bestScore = 0.0
        var bestI = 0
        var bestJ = 0
        var bestMatches = 0
        val matches = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                val sim = wordSimilarity(tail[i - 1], window[j - 1].norm)
                val matchScore = if (sim >= MATCH_THRESHOLD) sim * 2 else MISMATCH
                val diag = h[i - 1][j - 1] + matchScore
                val up = h[i - 1][j] + GAP
                val left = h[i][j - 1] + GAP
                var v = 0.0
                var mc = 0
                if (diag > v) { v = diag; mc = matches[i - 1][j - 1] + if (sim >= MATCH_THRESHOLD) 1 else 0 }
                if (up > v) { v = up; mc = matches[i - 1][j] }
                if (left > v) { v = left; mc = matches[i][j - 1] }
                h[i][j] = v
                matches[i][j] = mc

                // Only accept alignments that end on one of the last two heard words
                // (the very last one may be a half-recognized partial).
                if (i >= m - 1 && sim >= MATCH_THRESHOLD) {
                    val better = v > bestScore + 1e-9 ||
                        (kotlin.math.abs(v - bestScore) < 1e-9 && i > bestI)
                    if (better) {
                        bestScore = v; bestI = i; bestJ = j; bestMatches = mc
                    }
                }
            }
        }
        if (bestJ == 0) return false

        val newPos = winStart + bestJ + (m - bestI) // if we ended on the 2nd-last word, the last one is the next
        val jump = newPos - position
        val confident = when {
            bestMatches >= 2 -> true
            // A single word is enough only if it is close by and long/unambiguous.
            bestMatches == 1 -> jump in 0..2 && tail[bestI - 1].length >= 3
            else -> false
        }
        if (!confident || newPos <= position) return false
        position = newPos.coerceAtMost(words.size)
        return true
    }

    companion object {
        private const val TAIL_WORDS = 6
        private const val BACK_WINDOW = 3
        private const val AHEAD_WINDOW = 40
        private const val MATCH_THRESHOLD = 0.6
        private const val MISMATCH = -1.0
        private const val GAP = -0.7

        private val WORD_REGEX = Regex("[\\p{L}\\p{M}\\p{N}\\u200C\\u200D]+")

        fun tokenize(text: String): List<ScriptWord> =
            WORD_REGEX.findAll(text).map {
                ScriptWord(it.value, normalize(it.value), it.range.first, it.range.last + 1)
            }.toList()

        /** Telugu script and Tenglish both become the same Latin sound key (see [Phonetic]). */
        fun normalize(word: String): String =
            Phonetic.normalize(
                Normalizer.normalize(word, Normalizer.Form.NFC)
                    .replace("\u200C", "")
                    .replace("\u200D", "")
            )

        /** Similarity of two sound keys, falling back to their consonant skeletons. */
        fun wordSimilarity(a: String, b: String): Double {
            val full = similarity(a, b)
            if (full >= MATCH_THRESHOLD) return full
            val sa = Phonetic.skeleton(a)
            val sb = Phonetic.skeleton(b)
            if (sa.length < 3 || sb.length < 3) return full
            return maxOf(full, similarity(sa, sb) * 0.9)
        }

        /** 1.0 = identical, 0.0 = nothing in common (normalized Levenshtein on code points). */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val x = a.codePoints().toArray()
            val y = b.codePoints().toArray()
            var prev = IntArray(y.size + 1) { it }
            var cur = IntArray(y.size + 1)
            for (i in 1..x.size) {
                cur[0] = i
                for (j in 1..y.size) {
                    val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                val t = prev; prev = cur; cur = t
            }
            return 1.0 - prev[y.size].toDouble() / maxOf(x.size, y.size)
        }
    }
}
