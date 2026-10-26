package com.telugu.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Reading a script whose sections repeat the same words ("Google", "AI tools", "tool", "video",
 * "create cheyyachu"), with realistic recognition noise. The position must never leap ahead into
 * another section, and must never move backwards (nobody does a retake here).
 */
class RepetitiveScriptTest {

    private val script = "Google lo unna ee secret AI tools meelo entha mandiki telusu? ChatGPT okkate use chesthu aagipokandi. " +
        "Google ki chendina ee AI tools ni kuda okasari try cheyyandi!\n\nSo, first tool Flow\n\n" +
        "Dheenitho cinematic videos create cheyyachu, scenes generate cheyyachu. Video creation kosam idhi chaala powerful tool.\n\n" +
        "Second tool **Google Vids**\n\nIdhi kuda video tool, Google Workspace lo videos easy ga create cheyyachu. " +
        "Presentation laga videos chesi share cheyyachu.\n\nThird tool NotebookLM\n\n" +
        "Idi mee documents ni chadivi summary isthundi. Podcast laga audio kuda create chesthundi. Ee video ni save chesukondi!"

    private val fillers = listOf("aa", "ante", "okay", "so", "basically", "mari", "google", "tool", "video", "create")

    /** Simulates one read-through; returns descriptions of bad moves, and the final position. */
    private fun readThrough(seed: Int, tailWords: Int): Pair<List<String>, Int> {
        val rnd = Random(seed)
        val m = ScriptMatcher(script)
        val words = m.words.map { it.text }
        var spoken = mutableListOf<String>()
        val bad = mutableListOf<String>()
        for ((i, w) in words.withIndex()) {
            // Recognition noise: 5% dropped word, ~15% wrong word, 8% extra filler.
            when {
                rnd.nextDouble() < 0.05 -> {}
                rnd.nextDouble() < 0.15 -> spoken += fillers.random(rnd)
                else -> spoken += w
            }
            if (rnd.nextDouble() < 0.08) spoken += fillers.random(rnd)
            val before = m.position
            m.onHeard(spoken.takeLast(tailWords).joinToString(" "))
            val after = m.position
            if (after < before) bad += "word $i '$w': moved back $before -> $after"
            if (after - (i + 1) > 6) bad += "word $i '$w': leapt ahead to $after (reading ${i + 1})"
            if (rnd.nextDouble() < 0.1) spoken = mutableListOf() // a pause: new utterance
        }
        return bad to m.position
    }

    @Test
    fun neverLeapsIntoAnotherSection_laptopMic() {
        val failures = (1..20).flatMap { seed -> readThrough(seed, tailWords = 12).first.map { "seed $seed: $it" } }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun neverLeapsIntoAnotherSection_phoneMic() {
        val failures = (1..20).flatMap { seed -> readThrough(seed, tailWords = 40).first.map { "seed $seed: $it" } }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun stillReachesTheLastSentence() {
        // (One seed garbles the whole last sentence, so "the very last word" is too strict.)
        val total = ScriptMatcher(script).words.size
        (1..20).forEach { seed ->
            val (_, end) = readThrough(seed, tailWords = 12)
            assertTrue("seed $seed ended at $end of $total", end >= total - 8)
        }
    }

    @Test
    fun cleanReadingFollowsEveryWord() {
        val m = ScriptMatcher(script)
        val heard = mutableListOf<String>()
        m.words.forEachIndexed { i, w ->
            heard += w.text
            m.onHeard(heard.takeLast(12).joinToString(" "))
            assertTrue("word $i: at ${m.position}", m.position in i..i + 1)
        }
        assertEquals(m.words.size, m.position)
    }
}
