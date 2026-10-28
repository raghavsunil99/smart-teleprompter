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
 * with a fuzzy similarity on a phonetic key, so speech can follow a script written in Telugu,
 * Malayalam or Tamil letters or typed in English letters, despite small spelling differences.
 *
 * Position normally only moves forward. The one exception is a retake: when the speaker
 * clearly starts an earlier sentence again (3+ words matching from that sentence's start,
 * seen in two hypotheses in a row), position jumps back to it.
 *
 * Lines starting with `#` are section headings: shown on screen, never matched.
 */
class ScriptMatcher(text: String, val language: ScriptLanguage = ScriptLanguage.DEFAULT) {

    private val headingRanges: List<Pair<IntRange, String>> =
        HEADING_REGEX.findAll(text).map { it.range to it.groupValues[1].trim() }.toList()

    val words: List<ScriptWord> = tokenize(text, language).filter { w -> headingRanges.none { w.start in it.first } }

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

    /** Target of a long forward jump with only 4 matching words; confirmed by the next hypothesis. */
    private var pendingFar = -1

    fun reset(to: Int = 0) {
        position = to.coerceIn(0, words.size)
        pendingRetake = -1
        pendingFar = -1
    }

    /** Index of the section containing [wordIndex], or -1 before the first heading. */
    fun sectionAt(wordIndex: Int): Int = sections.indexOfLast { it.wordIndex <= wordIndex }

    private class Alignment(val score: Double, val endI: Int, val endJ: Int, val matches: Int, val startJ: Int)

    /** Words of the previous hypothesis, to tell which heard words are new. */
    private var previousHeard: List<String> = emptyList()

    /**
     * Index in [heard] where new words begin. Recognizers re-send what they heard so far (growing
     * partials, or the laptop's rolling last-N words), and re-using already-matched words lets
     * "tool Google" match the *next* "tool Google" in the script. So only new words may move us.
     */
    private fun firstNewWord(heard: List<String>): Int {
        val prev = previousHeard
        var common = 0
        while (common < prev.size && common < heard.size && prev[common] == heard[common]) common++
        var overlap = 0
        for (len in minOf(prev.size, heard.size) downTo 1) {
            if (prev.subList(prev.size - len, prev.size) == heard.subList(0, len)) { overlap = len; break }
        }
        return maxOf(common, overlap)
    }

    /**
     * Best local alignment of [tail] against words[winStart, winEnd) that ends on one of the
     * last two heard words (the very last one may be a half-recognized partial).
     */
    private fun align(tail: List<String>, winStart: Int, winEnd: Int, firstNewI: Int = 0): Alignment? {
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

                // Must end on a newly heard word (i is 1-based into tail).
                if (i >= m - 1 && i > firstNewI && isMatch) {
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
        val heard = tokenize(hypothesis, language).map { it.norm }.filter { it.isNotEmpty() }
        if (heard.isEmpty()) return false
        val firstNew = firstNewWord(heard)
        previousHeard = heard
        if (firstNew >= heard.size) return false // nothing new since last time
        val tail = heard.takeLast(TAIL_WORDS)
        val m = tail.size
        val firstNewI = (firstNew - (heard.size - m)).coerceAtLeast(0) // in tail coordinates

        // 1. Normal case: moving forward. Look nearest first, and the further ahead a match is,
        //    the more matching words it needs: scripts repeat words ("Google", "tool", "video"),
        //    and two of them can easily match a later section by accident.
        val fwdStart = (position - BACK_WINDOW).coerceAtLeast(0)
        // If everything heard (old words included) still lines up best right here, the new word
        // is noise or a filler: stay put rather than let it pull us to a later repeat.
        val here = align(tail, fwdStart, (position + FORWARD_REACHES[0]).coerceAtMost(words.size))
        if (here != null && here.matches >= 2) {
            val herePos = fwdStart + here.endJ + (m - here.endI)
            if (herePos in position - 1..position) {
                pendingRetake = -1
                pendingFar = -1
                return false
            }
        }
        for (reach in FORWARD_REACHES) {
            val fwd = align(tail, fwdStart, (position + reach).coerceAtMost(words.size), firstNewI) ?: continue
            val newPos = fwdStart + fwd.endJ + (m - fwd.endI) // ended on the 2nd-last word → the last one is next
            val jump = newPos - position
            if (newPos <= position) {
                if (fwd.matches >= 2 && newPos >= position - 1) {
                    pendingRetake = -1 // still reading where we are: don't look further away
                    pendingFar = -1
                    return false
                }
                continue
            }
            val needed = when {
                jump <= 3 -> 2
                jump <= 12 -> 3
                else -> 4
            }
            // A single word is enough only if it is the very next one or two and long/unambiguous.
            val singleOk = fwd.matches == 1 && jump <= 2 && tail[fwd.endI - 1].length >= 3
            if (fwd.matches < needed && !singleOk) continue
            // Real matches are mostly contiguous; a few common words spread across a long stretch
            // (e.g. a filler "okay" fuzzily matching "okkate" 9 words ahead) are not.
            val span = fwd.endJ - fwd.startJ
            if (jump > 2 && fwd.matches * 3 < span * 2) continue
            // Long jumps (skipped a sentence) need a 5-word match, or the next hypothesis to agree.
            if (jump > 12 && fwd.matches < 5 && (pendingFar < 0 || newPos !in pendingFar..pendingFar + 3)) {
                pendingFar = newPos
                return false
            }
            position = newPos.coerceAtMost(words.size)
            pendingRetake = -1
            pendingFar = -1
            return true
        }

        // 2. Retake: the speaker started an earlier sentence again.
        val backStart = (position - RETAKE_WINDOW).coerceAtLeast(0)
        val back = align(tail, backStart, position, firstNewI) ?: return false
        val sentenceStart = backStart + back.startJ
        val newPos = backStart + back.endJ + (m - back.endI)
        val span = back.endJ - back.startJ // aligned script words
        val isRetake = back.matches >= RETAKE_MIN_MATCHES &&
            back.matches * 4 >= span * 3 && // mostly matching, not a few common words spread out
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
        /** Forward search windows, nearest first (words ahead of the current position). */
        private val FORWARD_REACHES = intArrayOf(4, 13, AHEAD_WINDOW)
        private const val RETAKE_WINDOW = 60
        private const val RETAKE_MIN_MATCHES = 3
        private const val SENTENCE_ENDS = ".!?।|\n"
        private val HEADING_REGEX = Regex("(?m)^[ \\t]*#+[ \\t]*(.*)$")
        private const val MATCH_THRESHOLD = 0.6
        private const val MISMATCH = -1.0
        private const val GAP = -0.7

        private val WORD_REGEX = Regex("[\\p{L}\\p{M}\\p{N}\\u200C\\u200D]+")

        fun tokenize(text: String, language: ScriptLanguage = ScriptLanguage.DEFAULT): List<ScriptWord> =
            WORD_REGEX.findAll(text).map {
                ScriptWord(it.value, normalize(it.value, language), it.range.first, it.range.last + 1)
            }.toList()

        /** Native letters and English-letter typing both become the same Latin sound key (see [Phonetic]). */
        fun normalize(word: String, language: ScriptLanguage = ScriptLanguage.DEFAULT): String =
            Phonetic.normalize(
                Normalizer.normalize(word, Normalizer.Form.NFC)
                    .replace("\u200C", "")
                    .replace("\u200D", ""),
                language,
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
