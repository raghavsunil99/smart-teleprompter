package com.telugu.prompter

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteKeysTest {

    @Test
    fun commonRemotesMapToCommands() {
        assertEquals("toggle", RemoteKeys.commandFor(KeyEvent.KEYCODE_SPACE, true))
        assertEquals("toggle", RemoteKeys.commandFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true))
        assertEquals("down", RemoteKeys.commandFor(KeyEvent.KEYCODE_PAGE_DOWN, true)) // foot pedals
        assertEquals("up", RemoteKeys.commandFor(KeyEvent.KEYCODE_PAGE_UP, true))
        assertEquals("down", RemoteKeys.commandFor(KeyEvent.KEYCODE_DPAD_DOWN, true))
        assertEquals("top", RemoteKeys.commandFor(KeyEvent.KEYCODE_MOVE_HOME, true))
    }

    @Test
    fun volumeKeysOnlyWhenEnabled() {
        assertEquals("toggle", RemoteKeys.commandFor(KeyEvent.KEYCODE_VOLUME_UP, true)) // shutter remotes
        assertEquals("down", RemoteKeys.commandFor(KeyEvent.KEYCODE_VOLUME_DOWN, true))
        assertNull(RemoteKeys.commandFor(KeyEvent.KEYCODE_VOLUME_UP, false))
        assertNull(RemoteKeys.commandFor(KeyEvent.KEYCODE_VOLUME_DOWN, false))
    }

    @Test
    fun otherKeysAreIgnored() {
        assertNull(RemoteKeys.commandFor(KeyEvent.KEYCODE_A, true))
        assertNull(RemoteKeys.commandFor(KeyEvent.KEYCODE_BACK, true))
    }
}
