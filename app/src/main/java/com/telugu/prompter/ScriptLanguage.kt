package com.telugu.prompter

/** Languages the prompter can listen in. [locale] is what the speech recognizers are asked for. */
enum class ScriptLanguage(val code: String, val locale: String, val label: String) {
    TELUGU("te", "te-IN", "Telugu"),
    MALAYALAM("ml", "ml-IN", "Malayalam"),
    TAMIL("ta", "ta-IN", "Tamil"),
    ENGLISH("en", "en-IN", "English");

    companion object {
        val DEFAULT = TELUGU

        fun fromCode(code: String?): ScriptLanguage? = entries.find { it.code == code }

        /**
         * The language of the letters a script is written in, or null when it's in English
         * letters (which could be any of the four, so the user's choice decides).
         */
        fun detect(text: String): ScriptLanguage? {
            var telugu = 0; var malayalam = 0; var tamil = 0; var latin = 0
            for (c in text) {
                when (c) {
                    in 'ఀ'..'౿' -> telugu++
                    in 'ഀ'..'ൿ' -> malayalam++
                    in '஀'..'௿' -> tamil++
                    in 'a'..'z', in 'A'..'Z' -> latin++
                }
            }
            val indic = maxOf(telugu, malayalam, tamil)
            // A few English words in a native-letter script are normal; mostly-English text isn't native.
            if (indic == 0 || indic < latin / 2) return null
            return when (indic) {
                telugu -> TELUGU
                malayalam -> MALAYALAM
                else -> TAMIL
            }
        }
    }
}
