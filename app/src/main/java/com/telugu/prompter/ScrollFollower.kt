package com.telugu.prompter

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the recognizer's bursty word positions into a smooth, continuous reading position.
 *
 * Between bursts it keeps drifting forward at the speaker's measured pace, but never more
 * than [MAX_LEAD] words beyond what was actually heard, and it eases to a stop when the
 * speaker pauses. It never moves backwards on its own; [jumpTo] is for manual re-sync.
 */
class ScrollFollower {

    /** Smoothed position in words (fractional). Drives the scroll. */
    var display: Float = 0f
        private set

    private var confirmed = 0
    private var lastConfirmNanos = 0L
    private var rate = 2.0f // words per second, learnt from the speaker

    fun jumpTo(position: Int) {
        confirmed = position
        display = position.toFloat()
        lastConfirmNanos = 0L
    }

    /** Called when the matcher moved to [position] (the next word to be said). */
    fun confirm(position: Int, nowNanos: Long) {
        if (position <= confirmed) return
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
        val lead = if (listening && sinceConfirm < PAUSE_SECONDS) min(rate * sinceConfirm, MAX_LEAD) else 0f
        val target = min(confirmed + lead, wordCount.toFloat())
        if (target > display) {
            val k = 1f - exp(-dtSeconds / SMOOTHING_SECONDS)
            display += (target - display) * k
        }
        display = max(0f, display)
        return display
    }

    companion object {
        private const val MAX_LEAD = 1.5f
        private const val PAUSE_SECONDS = 1.2f
        private const val SMOOTHING_SECONDS = 0.35f
        private const val MIN_RATE = 0.8f
        private const val MAX_RATE = 4.5f
    }
}
