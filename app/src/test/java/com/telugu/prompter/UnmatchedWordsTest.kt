package com.telugu.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the recognizer writes words that don't match the script, the scroll should keep going (a little). */
class UnmatchedWordsTest {
    private val sec = 1_000_000_000L

    /** Speaker matched at y=100, then talks for [seconds] with nothing matching; returns final y. */
    private fun run(unmatchedPx: Float, seconds: Float): Float {
        val f = CruiseFollower(maxLeadPx = 150f)
        f.jumpTo(100f)
        var now = sec
        f.confirm(130f, now)
        now += 700_000_000L
        f.confirm(160f, now) // learns a pace
        val dt = 1f / 60
        var t = 0f
        while (t < seconds) {
            now += (dt * 1e9f).toLong()
            f.speechHeard(now)
            f.unmatchedPx = unmatchedPx
            f.step(now, dt, true)
            t += dt
        }
        return f.y
    }

    @Test
    fun wordCountKeepsScrollMovingThroughUnmatchedSpeech() {
        val without = run(0f, 4f)
        val with = run(300f, 4f)
        assertTrue("with=$with without=$without", with > without + 100f)
    }

    @Test
    fun guessIsCapped() {
        val huge = run(100_000f, 6f)
        // target 160 + (lead 150*1.8) + cap 2.5*150 — never runs away beyond a few lines.
        assertTrue("y=$huge", huge < 160f + 270f + 375f + 150f)
    }

    @Test
    fun noGuessWhenNotListening() {
        fun settled(unmatchedPx: Float): Float {
            val f = CruiseFollower(maxLeadPx = 150f)
            f.jumpTo(100f)
            f.confirm(160f, sec)
            f.unmatchedPx = unmatchedPx
            var now = sec
            repeat(120) { now += 16_000_000L; f.step(now, 0.016f, false) }
            return f.y
        }
        // With the mic off the guess must change nothing.
        assertEquals(settled(0f), settled(300f), 0.01f)
    }
}
