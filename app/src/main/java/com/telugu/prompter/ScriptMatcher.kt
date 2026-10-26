package com.telugu.prompter

import java.text.Normalizer

/** One word of the script, with where it sits in the original text. */
data class ScriptWord(val text: String, val norm: String, val start: Int, val end: Int)

/** A `# Heading` line in the script. [wordIndex] is the first spoken word after it. */
data class Section(val title: String, val wordIndex: Int, val charStart: Int)

/**
 * Follows a speaker through a script.
 *
 * The recognizer gives us a growing hypothesis of what was said in the current
 * utterance. We take the last few heard words and locally align them (Smith-Waterman)
 * against a window of the script just around the current position. Words are compared
 * with a fuzzy similarity on a phonetic key, so Telugu speech can follow a script typed
 * in Telugu letters or in Tenglish, despite small spelling differences.
 *
 * Position normally only moves forward. The one exception is a retake: when the speaker
 * clearly starts an earlier sentence again (3+ words matching from that sentence's start,
 * seen in two hypotheses in a row), position jumps back to it.
 *
 * Lines starting with `#` are section headings: shown on screen, never matched.
 */
class ScriptMatcher(text: String) {

    private val headingRanges: List<Pair<IntRange, String>> =
        HEADING_REGEX.findAll(text).map { it.range to it.groupValues[1].trim() }.toList()

    val words: List<ScriptWord> = tokenize(text).filter { w -> headingRanges.none { w.start in it.first } }

    val sections: List<Section> = headingRanges.map { (range, title) ->
        val idx = words.indexOfFirst { it.start > range.last }.let { if (it < 0) words.size else it }
        Section(title.ifBlank { "Section" }, idx, range.first)
    }

    /** Word indices that begin a sentence (or a line), where a retake can restart. */
    private val sentenceStarts: Set<Int> = words.indices.filter { i ->
        if (i == 0) return@filter true
        val gap = text.substring(words[i - 1].end, words[i].start)
        gap.any { it in SENTENCE_ENDS } || headingRanges.any { (r, _) -> r.first in words[i - 1].end until words[i].start }
    }.toSet()

    /** Index of the next word the speaker is expected to say (0..words.size). */
    var position: Int = 0
        private set

    /** Sentence start a retake was first seen at; confirmed by the next hypothesis. */
    private var pendingRetake = -1

    fun reset(to: Int = 0) {
        position = to.coerceIn(0, words.size)
        pendingRetake = -1
    }

    /** Index of the section containing [wordIndex], or -1 before the first heading. */
    fun sectionAt(wordIndex: Int): Int = sections.indexOfLast { it.wordIndex <= wordIndex }

    private class Alignment(val score: Double, val endI: Int, val endJ: Int, val matches: Int, val startJ: Int)

    /**
     * Best local alignment of [tail] against words[winStart, winEnd) that ends on one of the
     * last two heard words (the very last one may be a half-recognized partial).
     */
    private fun align(tail: List<String>, winStart: Int, winEnd: Int): Alignment? {
        if (winStart >= winEnd) return null
        val m = tail.size
        val n = winEnd - winStart
        val h = Array(m + 1) { DoubleArray(n + 1) }
        val matches = Array(m + 1) { IntArray(n + 1) }
        val start = Array(m + 1) { IntArray(n + 1) }
        var best: Alignment? = null

        for (i in 1..m) {
            for (j in 1..n) {
                val sim = wordSimilarity(tail[i - 1], words[winStart + j - 1].norm)
                val isMatch = sim >= MATCH_THRESHOLD
                val matchScore = if (isMatch) sim * 2 else MISMATCH
                val diag = h[i - 1][j - 1] + matchScore
                val up = h[i - 1][j] + GAP
                val left = h[i][j - 1] + GAP
                var v = 0.0
                var mc = 0
                var st = j - 1
                if (diag > v) {
                    v = diag
                    mc = matches[i - 1][j - 1] + if (isMatch) 1 else 0
                    st = if (h[i - 1][j - 1] > 0) start[i - 1][j - 1] else j - 1
                }
                if (up > v) { v = up; mc = matches[i - 1][j]; st = start[i - 1][j] }
                if (left > v) { v = left; mc = matches[i][j - 1]; st = start[i][j - 1] }
                h[i][j] = v
                matches[i][j] = mc
                start[i][j] = st

                if (i >= m - 1 && isMatch) {
                    val b = best
                    val better = b == null || v > b.score + 1e-9 ||
                        (kotlin.math.abs(v - b.score) < 1e-9 && i > b.endI)
                    if (better) best = Alignment(v, i, j, mc, st)
                }
            }
        }
        return best
    }

    /**
     * Feed the latest recognizer hypothesis. Returns true when [position] moved.
     */
    fun onHeard(hypothesis: String): Boolean {
        if (words.isEmpty()) return false
        val heard = tokenize(hypothesis).map { it.norm }.filter { it.isNotEmpty() }
        if (heard.isEmpty()) return false
        val tail = heard.takeLast(TAIL_WORDS)
        val m = tail.size

        // 1. Normal case: moving forward from the current position.
        val fwdStart = (position - BACK_WINDOW).coerceAtLeast(0)
        val fwd = align(tail, fwdStart, (position + AHEAD_WINDOW).coerceAtMost(words.size))
        if (fwd != null) {
            val newPos = fwdStart + fwd.endJ + (m - fwd.endI) // ended on the 2nd-last word → the last one is next
            val jump = newPos - position
            val confident = when {
                fwd.matches >= 2 -> true
                // A single word is enough only if it is close by and long/unambiguous.
                fwd.matches == 1 -> jump in 0..2 && tail[fwd.endI - 1].length >= 3
                else -> false
            }
            if (confident && newPos > position) {
                position = newPos.coerceAtMost(words.size)
                pendingRetake = -1
                return true
            }
            if (confident && newPos >= position - 1) {
                pendingRetake = -1 // still reading where we are; not a retake
                return false
            }
        }

        // 2. Retake: the speaker started an earlier sentence again.
        val backStart = (position - RETAKE_WINDOW).coerceAtLeast(0)
        val back = align(tail, backStart, position) ?: return false
        val sentenceStart = backStart + back.startJ
        val newPos = backStart + back.endJ + (m - back.endI)
        val isRetake = back.matches >= RETAKE_MIN_MATCHES &&
            sentenceStart in sentenceStarts &&
            newPos < position - 1
        if (!isRetake) return false
        if (pendingRetake != sentenceStart) {
            pendingRetake = sentenceStart // wait for the next hypothesis to agree on the sentence
            return false
        }
        position = newPos
        pendingRetake = -1
        return true
    }

    companion object {
        private const val TAIL_WORDS = 6
        private const val BACK_WINDOW = 3
        private const val AHEAD_WINDOW = 40
        private const val RETAKE_WINDOW = 60
        private const val RETAKE_MIN_MATCHES = 3
        private const val SENTENCE_ENDS = ".!?।|\n"
        private val HEADING_REGEX = Regex("(?m)^[ \\t]*#+[ \\t]*(.*)$")
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
