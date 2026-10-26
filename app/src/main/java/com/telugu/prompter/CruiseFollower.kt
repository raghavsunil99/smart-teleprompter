package com.telugu.prompter

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * "Cruise control" for voice-follow scrolling, in pixels.
 *
 * Speech recognition reports words in bursts. Instead of chasing each new position (which makes
 * the speed surge and sag), this scrolls at the speaker's measured pace — like Steady mode tuned
 * to their voice — and gently corrects drift over a couple of seconds. When they stop talking
 * it glides to a stop; when they start again it eases back up to speed.
 *
 * Positions are scroll offsets in pixels ([confirm] takes the y of the next word to be said).
 */
class CruiseFollower(
    /** How far beyond the last heard word prediction may run (about one line). */
    var maxLeadPx: Float = 150f,
) {
    /** Smoothed scroll position in pixels (fractional, for sub-pixel drawing). */
    var y: Float = 0f
        private set

    /** Current scroll speed in px/s. */
    var velocity: Float = 0f
        private set

    private var targetY = 0f
    private var lastConfirmNanos = 0L
    private var rewinding = false

    // Recent (time, y) confirmations, for the pace estimate.
    private val times = ArrayDeque<Long>()
    private val ys = ArrayDeque<Float>()
    private var pace = 0f // px/s

    fun jumpTo(px: Float) {
        y = px
        targetY = px
        velocity = 0f
        rewinding = false
        lastConfirmNanos = 0L
        times.clear(); ys.clear()
        pace = 0f
    }

    /** The recognizer placed the speaker at [px] (y of the next word to be said). */
    fun confirm(px: Float, nowNanos: Long) {
        if (px < targetY - 1f) {
            // Retake: glide back to the restarted sentence, then re-learn the pace.
            targetY = px
            rewinding = true
            velocity = 0f
            lastConfirmNanos = nowNanos
            times.clear(); ys.clear()
            return
        }
        if (px <= targetY) return
        targetY = px
        lastConfirmNanos = nowNanos
        times.addLast(nowNanos); ys.addLast(px)
        while (times.size > 2 && nowNanos - times.first() > PACE_WINDOW_NANOS) { times.removeFirst(); ys.removeFirst() }
        if (times.size >= 2) {
            val span = (times.last() - times.first()) / 1e9f
            if (span >= 0.6f) {
                val measured = (ys.last() - ys.first()) / span
                pace = if (pace == 0f) measured else pace + (measured - pace) * 0.4f
            }
        }
    }

    /** Advance one frame. [listening] = the speaker is being followed (mic on, prompter running). */
    fun step(nowNanos: Long, dtSeconds: Float, listening: Boolean): Float {
        if (dtSeconds <= 0f) return y
        val since = if (lastConfirmNanos == 0L) Float.MAX_VALUE else (nowNanos - lastConfirmNanos) / 1e9f

        if (rewinding) {
            y += (targetY - y) * (1f - exp(-dtSeconds / REWIND_SECONDS))
            if (abs(targetY - y) < 0.5f) { y = targetY; rewinding = false }
            velocity = 0f
            return y
        }

        val talking = listening && since < PAUSE_SECONDS
        // Where the speaker probably is now: the last heard word, plus their pace since then.
        // After a pause the prediction freezes (rather than vanishing, which would jolt).
        val lead = if (listening && lastConfirmNanos != 0L) min(pace * min(since, PAUSE_SECONDS), maxLeadPx) else 0f
        val expected = targetY + lead
        val error = expected - y

        val wanted = when {
            // Far behind (skipped a sentence, re-sync): catch up briskly but smoothly.
            error > maxLeadPx * 2 -> error / CATCH_UP_SECONDS
            // Reading: cruise at the speaker's pace, nudging drift away over a couple of seconds
            // (and more firmly when running ahead, so it never overtakes the speaker by much).
            talking -> max(0f, pace + error / if (error >= 0f) CORRECTION_SECONDS else AHEAD_CORRECTION_SECONDS)
            // Speaker paused: glide to a stop at the predicted spot.
            listening -> max(0f, error / SETTLE_SECONDS)
            // Stopped: ease onto the last heard word, back a little if needed, so it sits in the band.
            else -> (targetY - y) / SETTLE_SECONDS
        }
        // Smooth speed changes: no surges when a burst of words lands.
        velocity += (wanted - velocity) * (1f - exp(-dtSeconds / ACCEL_SECONDS))
        var next = y + velocity * dtSeconds
        // Safety net only; normally the speed has already eased off well before this.
        val hardCap = targetY + maxLeadPx * 1.5f
        if (next > hardCap) {
            next = max(y, hardCap)
            velocity = min(velocity, (next - y) / dtSeconds)
        }
        y = next
        return y
    }

    companion object {
        private const val PACE_WINDOW_NANOS = 4_000_000_000L
        private const val PAUSE_SECONDS = 1.2f
        private const val CORRECTION_SECONDS = 2.0f
        private const val AHEAD_CORRECTION_SECONDS = 0.6f
        private const val CATCH_UP_SECONDS = 0.7f
        private const val SETTLE_SECONDS = 0.6f
        private const val ACCEL_SECONDS = 0.45f
        private const val REWIND_SECONDS = 0.3f
    }
}
