package com.telugu.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFollowerTest {

    private val frame = 1f / 60
    private val frameNanos = (frame * 1e9).toLong()

    /** Run [seconds] of frames starting at [t], returning the positions seen and the end time. */
    private fun run(f: ScrollFollower, t: Long, seconds: Float, listening: Boolean = true): Pair<List<Float>, Long> {
        var now = t
        val out = mutableListOf<Float>()
        repeat((seconds / frame).toInt()) {
            now += frameNanos
            out += f.step(now, frame, listening, 100)
        }
        return out to now
    }

    @Test
    fun movesContinuouslyBetweenBursts() {
        val f = ScrollFollower()
        var t = 1_000_000_000L
        // Speaker says ~2.5 words/sec, recognizer reports in bursts every 0.8s.
        for (burst in 1..5) {
            f.confirm(burst * 2, t)
            val (positions, end) = run(f, t, 0.8f)
            // Never stands still for long within a burst window, and never goes backwards.
            positions.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
            assertTrue(positions.last() > positions.first())
            t = end
        }
    }

    @Test
    fun neverRunsFarAheadOfWhatWasHeard() {
        val f = ScrollFollower()
        f.confirm(10, 1_000_000_000L)
        val (positions, _) = run(f, 1_000_000_000L, 3f)
        assertTrue(positions.max() <= 10 + 1.5f + 1e-3f)
    }

    @Test
    fun stopsWhenNotListening() {
        val f = ScrollFollower()
        f.confirm(4, 1_000_000_000L)
        val (positions, _) = run(f, 1_000_000_000L, 3f, listening = false)
        assertEquals(4f, positions.last(), 0.05f)
    }

    @Test
    fun jumpToResyncs() {
        val f = ScrollFollower()
        f.confirm(20, 1_000_000_000L)
        run(f, 1_000_000_000L, 2f)
        f.jumpTo(3)
        assertEquals(3f, f.display, 0f)
    }

    @Test
    fun burstsDoNotCauseLurches() {
        val f = ScrollFollower()
        var t = 1_000_000_000L
        val speeds = mutableListOf<Float>()
        var last = 0f
        // Recognizer reports 3 words at once every 1.2s (bursty), speaker ~2.5 words/s.
        for (burst in 1..6) {
            f.confirm(burst * 3, t)
            repeat((1.2f / frame).toInt()) {
                t += frameNanos
                val p = f.step(t, frame, true, 100)
                speeds += (p - last) / frame
                last = p
            }
        }
        // Speed changes frame to frame stay small: no sudden jumps when a burst lands.
        val maxAccel = speeds.zipWithNext().maxOf { (a, b) -> kotlin.math.abs(b - a) }
        assertTrue("max speed change per frame was $maxAccel", maxAccel < 0.5f)
    }

    @Test
    fun retakeEasesBack() {
        val f = ScrollFollower()
        f.confirm(20, 1_000_000_000L)
        val (_, t) = run(f, 1_000_000_000L, 3f)
        f.confirm(10, t)
        val (positions, _) = run(f, t, 1.5f, listening = false)
        positions.zipWithNext().forEach { (a, b) -> assertTrue(b <= a + 1e-4f) } // moves back smoothly
        assertTrue(positions.first() > 10.5f) // not an instant jump
        assertEquals(10f, positions.last(), 0.1f)
    }
}
