package com.telugu.prompter

import android.view.KeyEvent

/**
 * Bluetooth remotes, foot pedals and keyboards arrive as ordinary key presses.
 * They map onto the same commands the laptop remote sends ("toggle", "down", ...).
 */
object RemoteKeys {

    /** Command for [keyCode], or null if the key isn't one we use. */
    fun commandFor(keyCode: Int, useVolumeKeys: Boolean): String? = when (keyCode) {
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_BUTTON_A -> "toggle"

        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "down"

        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> "up"

        KeyEvent.KEYCODE_MOVE_HOME -> "top"

        // Phone-camera shutter remotes usually send volume-up.
        KeyEvent.KEYCODE_VOLUME_UP -> if (useVolumeKeys) "toggle" else null
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (useVolumeKeys) "down" else null

        else -> null
    }
}

/** Hands key presses from the Activity to the prompter screen while it is showing. */
object KeyBus {
    /** Set by the prompter screen; returns true if it used the command. */
    @Volatile var handler: ((String) -> Boolean)? = null
    @Volatile var useVolumeKeys: Boolean = true

    /** Called from Activity.dispatchKeyEvent. Returns true when the key was consumed. */
    fun dispatch(event: KeyEvent): Boolean {
        val h = handler ?: return false
        val cmd = RemoteKeys.commandFor(event.keyCode, useVolumeKeys) ?: return false
        // Act on the press, swallow the release (so volume keys don't change the volume).
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) return h(cmd)
        return true
    }
}
