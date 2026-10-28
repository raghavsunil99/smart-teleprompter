package com.telugu.prompter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** [language]: a ScriptLanguage code chosen for this script, or null to work it out (see [languageFor]). */
data class Script(val id: String, val title: String, val body: String, val language: String? = null) {
    /** The chosen language; else the language of the letters it's written in; else the default. */
    fun languageFor(default: ScriptLanguage): ScriptLanguage =
        ScriptLanguage.fromCode(language) ?: ScriptLanguage.detect(body) ?: default
}

data class PrompterSettings(
    val fontSizeSp: Float = 34f,
    val mirror: Boolean = false,         // flip left-right, for beam-splitter glass
    val flipVertical: Boolean = false,   // flip upside-down, for some teleprompter rigs
    val orientation: Int = ORIENTATION_AUTO,
    val eyeLine: Float = 0.3f,      // fraction of screen height where the current line sits
    val manualSpeed: Float = 40f,   // pixels per second in manual mode
    val muteBeeps: Boolean = true,
    val highlightStyle: Int = HIGHLIGHT_WORD, // gliding word box, whole reading line, or none
    val brightnessMode: Int = BRIGHTNESS_PHONE,
    val brightnessLevel: Float = 0.7f,       // used with BRIGHTNESS_CUSTOM
    val countdownSeconds: Int = 3,           // 3-2-1 before scrolling starts (0 = off)
    val useVolumeKeys: Boolean = true,       // shutter remotes send volume keys
    val continuousListening: Boolean = true, // Android 13+ segmented sessions
    val defaultLanguage: String = ScriptLanguage.DEFAULT.code, // for scripts typed in English letters
) {
    companion object {
        const val ORIENTATION_AUTO = 0
        const val ORIENTATION_LANDSCAPE = 1
        const val ORIENTATION_PORTRAIT = 2
        const val HIGHLIGHT_WORD = 0
        const val HIGHLIGHT_LINE = 1
        const val HIGHLIGHT_OFF = 2
        const val BRIGHTNESS_PHONE = 0   // leave the phone's brightness alone
        const val BRIGHTNESS_MAX = 1
        const val BRIGHTNESS_CUSTOM = 2
    }
}

/** Scripts and settings, kept in SharedPreferences on the phone. */
class ScriptStore(context: Context) {
    private val prefs = context.getSharedPreferences("prompter", Context.MODE_PRIVATE)

    fun loadScripts(): List<Script> {
        val raw = prefs.getString(KEY_SCRIPTS, null) ?: return SAMPLES.also { saveScripts(it) }
        val arr = JSONArray(raw)
        val scripts = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Script(o.getString("id"), o.getString("title"), o.getString("body"), o.optString("language").ifEmpty { null })
        }
        // The single sample from earlier versions becomes the four language samples, unless it was edited.
        val i = scripts.indexOfFirst { it.id == OLD_SAMPLE_ID && (it.title == OLD_SAMPLE_TITLE || it.body == OLD_SAMPLE_BODY) }
        if (i < 0) return scripts
        return (scripts.take(i) + SAMPLES + scripts.drop(i + 1)).also { saveScripts(it) }
    }

    fun saveScripts(scripts: List<Script>) {
        val arr = JSONArray()
        scripts.forEach {
            val o = JSONObject().put("id", it.id).put("title", it.title).put("body", it.body)
            it.language?.let { lang -> o.put("language", lang) }
            arr.put(o)
        }
        prefs.edit().putString(KEY_SCRIPTS, arr.toString()).apply()
    }

    fun upsert(script: Script) {
        val list = loadScripts().toMutableList()
        val i = list.indexOfFirst { it.id == script.id }
        if (i >= 0) list[i] = script else list.add(0, script)
        saveScripts(list)
    }

    fun delete(id: String) = saveScripts(loadScripts().filterNot { it.id == id })

    fun loadSettings() = PrompterSettings(
        fontSizeSp = prefs.getFloat("fontSize", 34f),
        mirror = prefs.getBoolean("mirror", false),
        flipVertical = prefs.getBoolean("flipVertical", false),
        orientation = prefs.getInt("orientation", PrompterSettings.ORIENTATION_AUTO),
        eyeLine = prefs.getFloat("eyeLine", 0.3f),
        manualSpeed = prefs.getFloat("manualSpeed", 40f),
        muteBeeps = prefs.getBoolean("muteBeeps", true),
        // v1.1–v1.3 stored a line-highlight on/off switch; carry it over.
        highlightStyle = prefs.getInt(
            "highlightStyle",
            if (prefs.getBoolean("highlightLine", false)) PrompterSettings.HIGHLIGHT_LINE else PrompterSettings.HIGHLIGHT_WORD,
        ),
        brightnessMode = prefs.getInt("brightnessMode", PrompterSettings.BRIGHTNESS_PHONE),
        brightnessLevel = prefs.getFloat("brightnessLevel", 0.7f),
        countdownSeconds = prefs.getInt("countdownSeconds", 3),
        useVolumeKeys = prefs.getBoolean("useVolumeKeys", true),
        continuousListening = prefs.getBoolean("continuousListening", true),
        defaultLanguage = prefs.getString("defaultLanguage", null) ?: ScriptLanguage.DEFAULT.code,
    )

    fun saveSettings(s: PrompterSettings) {
        prefs.edit()
            .putFloat("fontSize", s.fontSizeSp)
            .putBoolean("mirror", s.mirror)
            .putBoolean("flipVertical", s.flipVertical)
            .putInt("orientation", s.orientation)
            .putFloat("eyeLine", s.eyeLine)
            .putFloat("manualSpeed", s.manualSpeed)
            .putBoolean("muteBeeps", s.muteBeeps)
            .putInt("highlightStyle", s.highlightStyle)
            .putInt("brightnessMode", s.brightnessMode)
            .putFloat("brightnessLevel", s.brightnessLevel)
            .putInt("countdownSeconds", s.countdownSeconds)
            .putBoolean("useVolumeKeys", s.useVolumeKeys)
            .putBoolean("continuousListening", s.continuousListening)
            .putString("defaultLanguage", s.defaultLanguage)
            .apply()
    }

    /** Where reading stopped in a script (word index), if the script hasn't been edited since. */
    fun loadPosition(script: Script): Int {
        val saved = prefs.getString("pos_${script.id}", null) ?: return 0
        val (hash, index) = saved.split(":").let { it[0] to it.getOrNull(1)?.toIntOrNull() }
        return if (hash == script.body.hashCode().toString()) index ?: 0 else 0
    }

    fun savePosition(script: Script, wordIndex: Int) {
        prefs.edit().putString("pos_${script.id}", "${script.body.hashCode()}:$wordIndex").apply()
    }

    fun loadRemoteRequirePin(): Boolean = prefs.getBoolean("remoteRequirePin", false)

    fun saveRemoteRequirePin(on: Boolean) = prefs.edit().putBoolean("remoteRequirePin", on).apply()

    /** The remote's fixed PIN, created once on first use. */
    fun loadRemotePin(): String =
        prefs.getString("remotePin", null) ?: newRemotePin().also { saveRemotePin(it) }

    fun saveRemotePin(pin: String) = prefs.edit().putString("remotePin", pin).apply()

    companion object {
        private const val KEY_SCRIPTS = "scripts"

        fun newId(): String = UUID.randomUUID().toString()

        fun newRemotePin(): String = "%04d".format(java.security.SecureRandom().nextInt(10_000))

        /** One short sample per language, written in its own letters. */
        val SAMPLES = listOf(
            Script(
                "sample-te", "Sample · Telugu",
                "నమస్కారం అందరికీ. ఈ రోజు మనం ఒక కొత్త విషయం గురించి మాట్లాడుకుందాం.\n\n" +
                    "మీరు మాట్లాడుతుంటే ఈ యాప్ మీ మాటలు వింటుంది, స్క్రిప్ట్‌ను దానంతట అదే ముందుకు జరుపుతుంది.\n\n" +
                    "ధన్యవాదాలు!",
                ScriptLanguage.TELUGU.code,
            ),
            Script(
                "sample-ml", "Sample · Malayalam",
                "എല്ലാവർക്കും നമസ്കാരം. ഇന്ന് നമ്മൾ ഒരു പുതിയ കാര്യത്തെക്കുറിച്ച് സംസാരിക്കാം.\n\n" +
                    "നിങ്ങൾ സംസാരിക്കുമ്പോൾ ഈ ആപ്പ് കേൾക്കുകയും സ്ക്രിപ്റ്റ് തനിയെ മുന്നോട്ട് നീക്കുകയും ചെയ്യും.\n\n" +
                    "നന്ദി!",
                ScriptLanguage.MALAYALAM.code,
            ),
            Script(
                "sample-ta", "Sample · Tamil",
                "அனைவருக்கும் வணக்கம். இன்று நாம் ஒரு புதிய விஷயத்தைப் பற்றி பேசலாம்.\n\n" +
                    "நீங்கள் பேசும்போது இந்த ஆப் கேட்டு, ஸ்கிரிப்டைத் தானாகவே நகர்த்தும்.\n\n" +
                    "நன்றி!",
                ScriptLanguage.TAMIL.code,
            ),
            Script(
                "sample-en", "Sample · English",
                "Hello everyone. Today let's talk about something new.\n\n" +
                    "As you speak, this app listens and moves the script along with you.\n\n" +
                    "Thank you!",
                ScriptLanguage.ENGLISH.code,
            ),
        )

        private const val OLD_SAMPLE_ID = "sample"

        /** Body of the sample from v1.1–v1.5 (typed in English letters). */
        private const val OLD_SAMPLE_BODY =
            "Namaskaram andariki. Ee roju manam oka kotha vishayam gurinchi maatladukundaam.\n\n" +
                "Meeru maatladuthunnappudu ee app mee maatalu vintundi. Meeru chadive padanni gurthinchi, " +
                "script ni daanantata ade mundhuku jaruputhundi.\n\n" +
                "Meeru aagithe, script kuda aaguthundi. Meeru oka vaakyam vadilesina, tharuvaathi vaakyam nundi malli follow avuthundi.\n\n" +
                "Edaina padam meeda long press chesthe, akkadi nundi malli start avuthundi. Dhanyavaadaalu!"

        /** Title of the v1 sample (in Telugu letters); replaced by [SAMPLES]. */
        private const val OLD_SAMPLE_TITLE = "\u0C28\u0C2E\u0C42\u0C28\u0C3E \u0C38\u0C4D\u0C15\u0C4D\u0C30\u0C3F\u0C2A\u0C4D\u0C1F\u0C4D (Sample)"
    }
}
