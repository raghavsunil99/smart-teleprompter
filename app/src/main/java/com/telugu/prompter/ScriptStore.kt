package com.telugu.prompter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Script(val id: String, val title: String, val body: String)

data class PrompterSettings(
    val fontSizeSp: Float = 34f,
    val mirror: Boolean = false,         // flip left-right, for beam-splitter glass
    val flipVertical: Boolean = false,   // flip upside-down, for some teleprompter rigs
    val orientation: Int = ORIENTATION_AUTO,
    val eyeLine: Float = 0.3f,      // fraction of screen height where the current line sits
    val manualSpeed: Float = 40f,   // pixels per second in manual mode
    val muteBeeps: Boolean = true,
) {
    companion object {
        const val ORIENTATION_AUTO = 0
        const val ORIENTATION_LANDSCAPE = 1
        const val ORIENTATION_PORTRAIT = 2
    }
}

/** Scripts and settings, kept in SharedPreferences on the phone. */
class ScriptStore(context: Context) {
    private val prefs = context.getSharedPreferences("prompter", Context.MODE_PRIVATE)

    fun loadScripts(): List<Script> {
        val raw = prefs.getString(KEY_SCRIPTS, null) ?: return listOf(SAMPLE).also { saveScripts(it) }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Script(o.getString("id"), o.getString("title"), o.getString("body"))
        }.map { if (it.id == SAMPLE.id && it.title == OLD_SAMPLE_TITLE) SAMPLE else it }
    }

    fun saveScripts(scripts: List<Script>) {
        val arr = JSONArray()
        scripts.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body)) }
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
            .apply()
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

        val SAMPLE = Script(
            id = "sample",
            title = "Sample script",
            body = "Namaskaram andariki. Ee roju manam oka kotha vishayam gurinchi maatladukundaam.\n\n" +
                "Meeru maatladuthunnappudu ee app mee maatalu vintundi. Meeru chadive padanni gurthinchi, " +
                "script ni daanantata ade mundhuku jaruputhundi.\n\n" +
                "Meeru aagithe, script kuda aaguthundi. Meeru oka vaakyam vadilesina, tharuvaathi vaakyam nundi malli follow avuthundi.\n\n" +
                "Edaina padam meeda long press chesthe, akkadi nundi malli start avuthundi. Dhanyavaadaalu!",
        )

        /** Title of the sample from earlier versions (in Telugu letters); replaced by [SAMPLE]. */
        private const val OLD_SAMPLE_TITLE = "\u0C28\u0C2E\u0C42\u0C28\u0C3E \u0C38\u0C4D\u0C15\u0C4D\u0C30\u0C3F\u0C2A\u0C4D\u0C1F\u0C4D (Sample)"
    }
}
