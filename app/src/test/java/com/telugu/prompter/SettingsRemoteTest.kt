package com.telugu.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRemoteTest {
    private val base = PrompterSettings()

    @Test
    fun setsEachKindOfSetting() {
        assertEquals(0.5f, base.withSetting("eyeLine", "0.5")!!.eyeLine, 0.0001f)
        assertEquals(40f, base.withSetting("fontSize", "40")!!.fontSizeSp, 0.0001f)
        assertEquals(90f, base.withSetting("speed", "90")!!.manualSpeed, 0.0001f)
        assertTrue(base.withSetting("flip", "1")!!.flipVertical)
        assertFalse(base.withSetting("muteBeeps", "false")!!.muteBeeps)
        assertEquals(PrompterSettings.ORIENTATION_LANDSCAPE, base.withSetting("orientation", "landscape")!!.orientation)
        assertEquals(PrompterSettings.HIGHLIGHT_LINE, base.withSetting("highlight", "line")!!.highlightStyle)
        assertEquals(PrompterSettings.BRIGHTNESS_CUSTOM, base.withSetting("brightness", "custom")!!.brightnessMode)
        assertEquals(5, base.withSetting("countdown", "5")!!.countdownSeconds)
        assertEquals("ml", base.withSetting("defaultLanguage", "ml")!!.defaultLanguage)
    }

    @Test
    fun clampsNumbersIntoTheirRange() {
        assertEquals(0.6f, base.withSetting("eyeLine", "0.95")!!.eyeLine, 0.0001f)
        assertEquals(0.1f, base.withSetting("eyeLine", "-3")!!.eyeLine, 0.0001f)
        assertEquals(96f, base.withSetting("fontSize", "500")!!.fontSizeSp, 0.0001f)
        assertEquals(10f, base.withSetting("speed", "1")!!.manualSpeed, 0.0001f)
        assertEquals(0.05f, base.withSetting("brightnessLevel", "0")!!.brightnessLevel, 0.0001f)
        assertEquals(10, base.withSetting("countdown", "99")!!.countdownSeconds)
    }

    @Test
    fun rejectsUnknownKeysAndBadValues() {
        assertNull(base.withSetting("nope", "1"))
        assertNull(base.withSetting("fontSize", "big"))
        assertNull(base.withSetting("fontSize", "NaN"))
        assertNull(base.withSetting("flip", "maybe"))
        assertNull(base.withSetting("orientation", "sideways"))
        assertNull(base.withSetting("highlight", ""))
        assertNull(base.withSetting("defaultLanguage", "xx"))
    }

    @Test
    fun jsonShowsWhatTheLaptopCanSet() {
        val s = base.copy(
            eyeLine = 0.5f, flipVertical = true, orientation = PrompterSettings.ORIENTATION_PORTRAIT,
            highlightStyle = PrompterSettings.HIGHLIGHT_OFF, brightnessMode = PrompterSettings.BRIGHTNESS_MAX,
        )
        val json = s.toRemoteJson()
        assertTrue(json, "\"eyeLine\":0.5" in json)
        assertTrue(json, "\"flip\":true" in json)
        assertTrue(json, "\"orientation\":\"portrait\"" in json)
        assertTrue(json, "\"highlight\":\"off\"" in json)
        assertTrue(json, "\"brightness\":\"max\"" in json)
    }

    @Test
    fun everyKeyInTheJsonCanBeSetBack() {
        // Whatever the phone reports, the laptop can send back unchanged.
        val s = base.copy(eyeLine = 0.45f, fontSizeSp = 52f, highlightStyle = PrompterSettings.HIGHLIGHT_LINE,
            orientation = PrompterSettings.ORIENTATION_LANDSCAPE, brightnessMode = PrompterSettings.BRIGHTNESS_CUSTOM)
        val values = Regex("\"(\\w+)\":(\"[^\"]*\"|[^,}]+)").findAll(s.toRemoteJson())
            .associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        assertEquals(14, values.size)
        var round = PrompterSettings()
        for ((k, v) in values) round = round.withSetting(k, v) ?: error("cannot set $k=$v")
        assertEquals(s, round)
    }
}
