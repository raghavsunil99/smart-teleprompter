package com.telugu.prompter

/**
 * Prompter settings as the laptop page sees them: a small set of named values it can read
 * ([toRemoteJson]) and change ([withSetting]). Plain Kotlin, so it can be tested on a computer.
 *
 * Keys and values:
 *   fontSize 18..96 · speed 10..150 · eyeLine 0.1..0.6 (fraction of screen height)
 *   flip, mirror, muteBeeps, volumeKeys, continuousListening   1/0 (or true/false)
 *   orientation  auto | landscape | portrait
 *   highlight    word | line | off
 *   brightness   phone | max | custom      brightnessLevel 0.05..1
 *   countdown    0..10 seconds
 *   defaultLanguage  te | ml | ta | en
 */
// (see the header above for the keys)
/**
 * Returns these settings with [key] set from the text [raw], or null if the key is unknown or the
 * value isn't valid. Numbers outside the allowed range are clamped, not rejected.
 */
fun PrompterSettings.withSetting(key: String, raw: String): PrompterSettings? = when (key) {
    "fontSize" -> numberOrNull(raw)?.let { copy(fontSizeSp = it.coerceIn(18f, 96f)) }
    "speed" -> numberOrNull(raw)?.let { copy(manualSpeed = it.coerceIn(10f, 150f)) }
    "eyeLine" -> numberOrNull(raw)?.let { copy(eyeLine = it.coerceIn(0.1f, 0.6f)) }
    "brightnessLevel" -> numberOrNull(raw)?.let { copy(brightnessLevel = it.coerceIn(0.05f, 1f)) }
    "countdown" -> numberOrNull(raw)?.let { copy(countdownSeconds = it.toInt().coerceIn(0, 10)) }
    "flip" -> boolOrNull(raw)?.let { copy(flipVertical = it) }
    "mirror" -> boolOrNull(raw)?.let { copy(mirror = it) }
    "muteBeeps" -> boolOrNull(raw)?.let { copy(muteBeeps = it) }
    "volumeKeys" -> boolOrNull(raw)?.let { copy(useVolumeKeys = it) }
    "continuousListening" -> boolOrNull(raw)?.let { copy(continuousListening = it) }
    "orientation" -> when (raw) {
        "auto" -> copy(orientation = PrompterSettings.ORIENTATION_AUTO)
        "landscape" -> copy(orientation = PrompterSettings.ORIENTATION_LANDSCAPE)
        "portrait" -> copy(orientation = PrompterSettings.ORIENTATION_PORTRAIT)
        else -> null
    }
    "highlight" -> when (raw) {
        "word" -> copy(highlightStyle = PrompterSettings.HIGHLIGHT_WORD)
        "line" -> copy(highlightStyle = PrompterSettings.HIGHLIGHT_LINE)
        "off" -> copy(highlightStyle = PrompterSettings.HIGHLIGHT_OFF)
        else -> null
    }
    "brightness" -> when (raw) {
        "phone" -> copy(brightnessMode = PrompterSettings.BRIGHTNESS_PHONE)
        "max" -> copy(brightnessMode = PrompterSettings.BRIGHTNESS_MAX)
        "custom" -> copy(brightnessMode = PrompterSettings.BRIGHTNESS_CUSTOM)
        else -> null
    }
    "defaultLanguage" -> ScriptLanguage.fromCode(raw)?.let { copy(defaultLanguage = it.code) }
    else -> null
}

private fun numberOrNull(raw: String): Float? = raw.toFloatOrNull()?.takeIf { it.isFinite() }

private fun boolOrNull(raw: String): Boolean? = when (raw.lowercase()) {
    "1", "true", "on" -> true
    "0", "false", "off" -> false
    else -> null
}

/** The settings the laptop page shows, as JSON (same keys and values [withSetting] accepts). */
fun PrompterSettings.toRemoteJson(): String = Json.obj(
    "fontSize" to fontSizeSp,
    "speed" to manualSpeed,
    "eyeLine" to eyeLine,
    "flip" to flipVertical,
    "mirror" to mirror,
    "muteBeeps" to muteBeeps,
    "volumeKeys" to useVolumeKeys,
    "continuousListening" to continuousListening,
    "orientation" to when (orientation) {
        PrompterSettings.ORIENTATION_LANDSCAPE -> "landscape"
        PrompterSettings.ORIENTATION_PORTRAIT -> "portrait"
        else -> "auto"
    },
    "highlight" to when (highlightStyle) {
        PrompterSettings.HIGHLIGHT_LINE -> "line"
        PrompterSettings.HIGHLIGHT_OFF -> "off"
        else -> "word"
    },
    "brightness" to when (brightnessMode) {
        PrompterSettings.BRIGHTNESS_MAX -> "max"
        PrompterSettings.BRIGHTNESS_CUSTOM -> "custom"
        else -> "phone"
    },
    "brightnessLevel" to brightnessLevel,
    "countdown" to countdownSeconds,
    "defaultLanguage" to defaultLanguage,
)
