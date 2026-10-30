package com.telugu.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Feeds the same bursty recognition into the v1.3 scroll ([ScrollFollower]) and the v1.4
 * cruise control ([CruiseFollower]) and measures how jerky each scroll is.
 */
class SmoothnessTest {

    private data class Run(val rmsJerk: Double, val maxJerk: Double, val meanTrackingPx: Double)

    private class Sim(seed: Int) {
        val rnd = Random(seed)
        // Word y positions in px: words of different widths, wrapped into lines (as wordY does).
        val wordY: FloatArray = run {
            val ys = FloatArray(400)
            var y = 0f
            for (i in ys.indices) { ys[i] = y; y += 18f + rnd.nextFloat() * 30f }
            ys
        }
        // Speaker: ~2.5 words/s, with a 1.5 s pause every ~8 s.
        val spokenAt = LongArray(300).also { t ->
            var now = 1_000_000_000L
            for (i in t.indices) {
                now += (300_000_000L + rnd.nextLong(200_000_000L))
                if (i % 20 == 19) now += 1_500_000_000L
                t[i] = now
            }
        }
        // Recognizer: reports everything said up to ~0.3 s ago, in bursts every 0.5–1.4 s.
        val reports = mutableListOf<Pair<Long, Int>>().also { r ->
            var now = 1_000_000_000L
            while (now < spokenAt.last()) {
                now += 500_000_000L + rnd.nextLong(900_000_000L)
                val heard = spokenAt.count { it <= now - 300_000_000L }
                if (heard > 0) r += now to heard
            }
        }
        fun trueY(now: Long): Float = wordY[spokenAt.count { it <= now }.coerceAtMost(wordY.size - 1)]
    }

    private fun measure(sim: Sim, frame: (now: Long, dt: Float, confirmTo: Int?) -> Float): Run {
        val dt = 1f / 60
        val step = (dt * 1e9).toLong()
        var now = 1_000_000_000L
        var ri = 0
        var lastY = Float.NaN
        var lastV = Float.NaN
        val jerks = mutableListOf<Double>()
        var trackSum = 0.0
        var n = 0
        while (now < sim.spokenAt.last()) {
            now += step
            var confirm: Int? = null
            while (ri < sim.reports.size && sim.reports[ri].first <= now) { confirm = sim.reports[ri].second; ri++ }
            val y = frame(now, dt, confirm)
            if (!lastY.isNaN()) {
                val v = (y - lastY) / dt
                if (!lastV.isNaN()) jerks += abs(v - lastV).toDouble()
                lastV = v
            }
            lastY = y
            trackSum += abs(y - sim.trueY(now)); n++
        }
        return Run(sqrt(jerks.sumOf { it * it } / jerks.size), jerks.max(), trackSum / n)
    }

    private fun oldScroll(sim: Sim): Run {
        val f = ScrollFollower()
        return measure(sim) { now, dt, confirm ->
            confirm?.let { f.confirm(it, now) }
            val p = f.step(now, dt, true, sim.wordY.size - 1)
            val i = p.toInt().coerceIn(0, sim.wordY.size - 2)
            sim.wordY[i] + (sim.wordY[i + 1] - sim.wordY[i]) * (p - i)
        }
    }

    private fun cruise(sim: Sim): Run {
        val f = CruiseFollower(maxLeadPx = 150f)
        return measure(sim) { now, dt, confirm ->
            confirm?.let { f.confirm(sim.wordY[it], now) }
            f.step(now, dt, true)
        }
    }

    @Test
    fun cruiseIsMuchSmootherThanV13AndStillTracks() {
        val old = (1..10).map { oldScroll(Sim(it)) }
        val new = (1..10).map { cruise(Sim(it)) }
        val oldJerk = old.map { it.rmsJerk }.average()
        val newJerk = new.map { it.rmsJerk }.average()
        val oldMax = old.map { it.maxJerk }.average()
        val newMax = new.map { it.maxJerk }.average()
        val oldTrack = old.map { it.meanTrackingPx }.average()
        val newTrack = new.map { it.meanTrackingPx }.average()
        val report = "v1.3: rms speed change %.2f px/s per frame (max %.1f), tracking %.1f px | v1.4: rms %.2f (max %.1f), tracking %.1f px"
            .format(oldJerk, oldMax, oldTrack, newJerk, newMax, newTrack)
        println(report)
        assertTrue("not smoother enough: $report", newJerk < oldJerk * 0.5)
        assertTrue("worse peak jolts: $report", newMax < oldMax)
        // Tracking: on average within about one line of where the speaker is.
        assertTrue("tracks poorly: $report", newTrack < 60.0)
    }

    @Test
    fun stopsSmoothlyWhenSpeakingStops() {
        val f = CruiseFollower(maxLeadPx = 150f)
        var now = 1_000_000_000L
        for (i in 1..20) { now += 400_000_000L; f.confirm(i * 30f, now); repeat(24) { f.step(now, 1f / 60, true) } }
        val speeds = mutableListOf<Float>()
        repeat(180) { now += 16_666_667L; f.step(now, 1f / 60, true); speeds += f.velocity }
        assertTrue("still moving at ${speeds.last()}", abs(speeds.last()) < 2f)
        speeds.zipWithNext().forEach { (a, b) -> assertTrue("jolt $a -> $b", abs(b - a) < 8f) }
        assertTrue(f.y <= 20 * 30f + 150f * 1.5f + 0.01f)
    }

    @Test
    fun settlesOnLastHeardWordWhenStopped() {
        val f = CruiseFollower(maxLeadPx = 150f)
        var now = 1_000_000_000L
        for (i in 1..10) { now += 400_000_000L; f.confirm(i * 30f, now); repeat(24) { f.step(now, 1f / 60, true) } }
        repeat(240) { now += 16_666_667L; f.step(now, 1f / 60, false) }
        assertEquals(300f, f.y, 2f)
    }

    @Test
    fun retakeGlidesBack() {
        val f = CruiseFollower(maxLeadPx = 150f)
        var now = 1_000_000_000L
        for (i in 1..10) { now += 400_000_000L; f.confirm(i * 30f, now); repeat(24) { f.step(now, 1f / 60, true) } }
        f.confirm(90f, now)
        val ys = mutableListOf<Float>()
        repeat(90) { now += 16_666_667L; ys += f.step(now, 1f / 60, false) } // ~1.5 s to glide back
        assertTrue(ys.first() > 120f) // not an instant jump
        ys.zipWithNext().forEach { (a, b) -> assertTrue(b <= a + 0.01f) }
        assertEquals(90f, ys.last(), 3f)
    }

    @Test
    fun jumpToIsInstant() {
        val f = CruiseFollower()
        f.confirm(500f, 1_000_000_000L)
        f.jumpTo(40f)
        assertEquals(40f, f.y, 0f)
    }

    @Test
    fun keepsMovingThroughSpeechThatDoesNotMatch() {
        val f = CruiseFollower(maxLeadPx = 150f)
        var now = 1_000_000_000L
        for (i in 1..12) { now += 400_000_000L; f.confirm(i * 30f, now); repeat(24) { f.step(now, 1f / 60, true) } }
        val atLastMatch = f.y
        // 2 s of speech the matcher can't place: still scrolling, at about the same pace.
        repeat(120) { i ->
            now += 16_666_667L
            if (i % 30 == 0) f.speechHeard(now)
            f.step(now, 1f / 60, true)
        }
        assertTrue("moved only ${f.y - atLastMatch}", f.y - atLastMatch > 60f)
        assertTrue("still moving at ${f.velocity}", f.velocity > 20f)
    }

    @Test
    fun stopsWhenTheSpeechStops() {
        val f = CruiseFollower(maxLeadPx = 150f)
        var now = 1_000_000_000L
        for (i in 1..12) { now += 400_000_000L; f.confirm(i * 30f, now); repeat(24) { f.step(now, 1f / 60, true) } }
        repeat(300) { now += 16_666_667L; f.step(now, 1f / 60, true) }
        assertTrue("still moving at ${f.velocity}", kotlin.math.abs(f.velocity) < 2f)
    }
}
