package com.telugu.prompter

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the recognizer's bursty word positions into a smooth, continuous reading position.
 *
 * Between bursts it keeps drifting forward at the speaker's measured pace, but never more
 * than [MAX_LEAD] words beyond what was actually heard, and it eases to a stop when the
 * speaker pauses. The position has inertia (its velocity is smoothed), so it follows the
 * average speaking pace instead of lurching with every burst.
 *
 * It only moves backwards for a retake ([confirm] with a lower position), and when listening
 * stops it eases back onto the last word actually heard, so the reading line settles in the
 * band. [jumpTo] is an instant manual re-sync.
 */
class ScrollFollower {

    /** Smoothed position in words (fractional). Drives the scroll. */
    var display: Float = 0f
        private set

    private var confirmed = 0
    private var lastConfirmNanos = 0L
    private var rate = 2.0f // words per second, learnt from the speaker
    private var velocity = 0f // words per second, smoothed
    private var rewinding = false

    fun jumpTo(position: Int) {
        confirmed = position
        display = position.toFloat()
        velocity = 0f
        rewinding = false
        lastConfirmNanos = 0L
    }

    /** Called when the matcher moved to [position] (the next word to be said). */
    fun confirm(position: Int, nowNanos: Long) {
        if (position < confirmed) {
            // Retake: glide back to the restarted sentence.
            confirmed = position
            rewinding = true
            velocity = 0f
            lastConfirmNanos = nowNanos
            return
        }
        if (position == confirmed) return
        if (lastConfirmNanos != 0L) {
            val dt = (nowNanos - lastConfirmNanos) / 1e9f
            if (dt in 0.15f..4f) {
                val observed = (position - confirmed) / dt
                rate = (0.7f * rate + 0.3f * observed).coerceIn(MIN_RATE, MAX_RATE)
            }
        }
        confirmed = position
        lastConfirmNanos = nowNanos
    }

    /** Advance one frame. [listening] = recognizer is running (so prediction is allowed). */
    fun step(nowNanos: Long, dtSeconds: Float, listening: Boolean, wordCount: Int): Float {
        val sinceConfirm = if (lastConfirmNanos == 0L) Float.MAX_VALUE else (nowNanos - lastConfirmNanos) / 1e9f
        // Predicted words beyond the last heard one: grows at the speaking rate and levels off
        // smoothly at MAX_LEAD (a hard cap would make the scroll stop abruptly). After a pause
        // of PAUSE_SECONDS it stops growing — it doesn't vanish, which would also jolt.
        val lead = if (listening && lastConfirmNanos != 0L) {
            MAX_LEAD * (1f - exp(-rate * min(sinceConfirm, PAUSE_SECONDS) / MAX_LEAD))
        } else 0f
        val target = min(confirmed + lead, wordCount.toFloat())

        // Retake, or prompter paused while ahead of the last heard word: ease back onto it.
        if (!listening && display > target + 0.01f) rewinding = true
        if (rewinding) {
            display += (target - display) * (1f - exp(-dtSeconds / REWIND_SECONDS))
            if (abs(target - display) < 0.05f) { display = target; rewinding = false }
            return display
        }

        // Velocity that would close the gap in RESPONSE_SECONDS, smoothed for inertia.
        // RESPONSE = 4 × INERTIA makes this critically damped: smooth, and no overshoot.
        val gap = max(0f, target - display)
        val wanted = gap / RESPONSE_SECONDS
        velocity += (wanted - velocity) * (1f - exp(-dtSeconds / INERTIA_SECONDS))
        // Never step backwards here: when a pause drops the predicted lead, just hold.
        val next = max(display, min(display + velocity * dtSeconds, target))
        // If we caught up with the target, the speed actually moved is what carries over.
        if (dtSeconds > 0f) velocity = min(velocity, (next - display) / dtSeconds)
        display = max(0f, next)
        return display
    }

    companion object {
        private const val MAX_LEAD = 1.5f
        private const val PAUSE_SECONDS = 1.2f
        private const val INERTIA_SECONDS = 0.18f
        private const val RESPONSE_SECONDS = 4 * INERTIA_SECONDS
        private const val REWIND_SECONDS = 0.3f
        private const val MIN_RATE = 0.8f
        private const val MAX_RATE = 4.5f
    }
}
