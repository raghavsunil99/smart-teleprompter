package com.telugu.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real scripts that mix Telugu with English words. The recognizer writes those English words in
 * Telugu letters, and says joined words as two (useState = యూజ్ స్టేట్). Following must not stall.
 */
class MixedScriptTest {

    private val tenglish = """React nerchukuntunnara? 
Aithe ee Hooks asalu miss avakandi…

5 Hooks. 5 different purposes.

Okkasari anni clear cheseddam!

useState screen meeda kanipinche values ni store chesthundi. Value change ayithe component re-render avuthundi.

Simple ga cheppalante, screen meeda update avvalsina values kosam useState use chestham.

useEffect React component ni bayata unna systems tho connect chesthundi.

Examples: Timers, Event Listeners, Browser APIs.

Oka value, inko value nunchi calculate avuthunte useEffect vadakandi. Direct ga calculate cheyyachu.

useRef values ni store chesthundi, kaani re-render trigger cheyyadu.

DOM elements ni access cheyyalanna, Timer IDs ni store cheyyalanna useRef use cheyyandi.

Ref.current change chesina screen meeda em change avvadu, endukante re-render trigger avvadu.

useMemo heavy calculations result ni cache chesthundi.

Dependencies change ayinappude calculation malli execute avuthundi.

Chinna calculations ki use chesthe unnecessary complexity thappa pedda benefit undadu.

useCallback function reference ni stable ga maintain chesthundi.

Kaani oka important point entante…

React Compiler enable ayithe, ee rendu Hooks handle chese memoization optimizations ni automatic ga handle cheyyagaladu.

So, prathi sari manam manually useMemo, useCallback rayalsina avasaram undadu.

Final ga ee rule gurthupettukondi:

- useState – Screen values
- useEffect – External systems
- useRef – Without re-render memory
- useMemo – Calculation caching
- useCallback – Function reference

Ee cheat sheet save chesukondi. Next time Hooks daggara confuse avvalsina avasaram undadu!

Comment lo HOOKS ani type cheyyandi. Cheat sheet teesukondi!"""

    private val teluguLetters = """మీరు React నేర్చుకుంటున్నారా?\

అయితే ఈ Hooks అస్సలు మిస్ అవ్వకండి…

5 Hooks. 5 different purposes.

ఒక్కసారి అన్నీ క్లియర్ చేసేద్దాం!

useState స్క్రీన్ మీద కనిపించే values ని store చేస్తుంది. Value change అయితే component re-render అవుతుంది.

Simple గా చెప్పాలంటే, స్క్రీన్ మీద update అవ్వాల్సిన values కోసం useState use చేస్తాం.

useEffect React component ని బయట ఉన్న systems తో connect చేస్తుంది.

Examples: Timers, Event Listeners, Browser APIs.

ఒక value, ఇంకో value నుంచి calculate అవుతున్నప్పుడు useEffect వాడకండి. Direct గా calculate చేసుకోవచ్చు.

useRef values ని store చేస్తుంది, కానీ re-render trigger చేయదు.

DOM elements ని access చేయాలన్నా, Timer IDs ని store చేయాలన్నా useRef use చేయండి.

Ref.current change చేసినా స్క్రీన్ మీద ఏం change అవ్వదు, ఎందుకంటే re-render trigger అవ్వదు.

useMemo heavy calculations result ని cache చేస్తుంది.

Dependencies change అయినప్పుడే calculation మళ్లీ execute అవుతుంది.

చిన్న calculations కి use చేస్తే unnecessary complexity తప్ప పెద్ద benefit ఉండదు.

useCallback function reference ని stable గా maintain చేస్తుంది.

కానీ ఒక important point ఏంటంటే…

React Compiler enable అయితే, ఈ రెండు Hooks handle చేసే memoization optimizations ని automatic గా handle చేయగలదు.

So, ప్రతిసారీ మనం manually useMemo, useCallback రాయాల్సిన అవసరం ఉండదు.

Final గా ఈ rule గుర్తుపెట్టుకోండి:

- useState – Screen values

- useEffect – External systems

- useRef – Without re-render memory

- useMemo – Calculation caching

- useCallback – Function reference

ఈ cheat sheet save చేసుకోండి. Next time Hooks దగ్గర confuse అవ్వాల్సిన అవసరం ఉండదు!

Comment లో HOOKS అని type చేయండి. Cheat sheet తీసుకోండి!"""

    /** How Google's Telugu recognizer writes each word (an estimate). */
    private val heardAs = mapOf(
        "nerchukuntunnara" to "నేర్చుకుంటున్నారా", "Aithe" to "అయితే", "ayithe" to "అయితే", "ee" to "ఈ", "Ee" to "ఈ", "asalu" to "అసలు", "miss" to "మిస్", "avakandi" to "అవ్వకండి", "Okkasari" to "ఒక్కసారి", "anni" to "అన్నీ", "clear" to "క్లియర్", "cheseddam" to "చేసేద్దాం", "screen" to "స్క్రీన్", "meeda" to "మీద", "kanipinche" to "కనిపించే", "ni" to "ని", "chesthundi" to "చేస్తుంది", "avuthundi" to "అవుతుంది", "ga" to "గా", "cheppalante" to "చెప్పాలంటే", "avvalsina" to "అవ్వాల్సిన", "kosam" to "కోసం", "chestham" to "చేస్తాం", "bayata" to "బయట", "unna" to "ఉన్న", "tho" to "తో", "Oka" to "ఒక", "oka" to "ఒక", "inko" to "ఇంకో", "nunchi" to "నుంచి", "avuthunte" to "అవుతుంటే", "vadakandi" to "వాడకండి", "cheyyachu" to "చేయొచ్చు", "kaani" to "కానీ", "Kaani" to "కానీ", "cheyyadu" to "చేయదు", "cheyyalanna" to "చేయాలన్నా", "cheyyandi" to "చేయండి", "chesina" to "చేసినా", "em" to "ఏం", "avvadu" to "అవ్వదు", "endukante" to "ఎందుకంటే", "ayinappude" to "అయినప్పుడే", "malli" to "మళ్లీ", "Chinna" to "చిన్న", "ki" to "కి", "chesthe" to "చేస్తే", "thappa" to "తప్ప", "pedda" to "పెద్ద", "undadu" to "ఉండదు", "entante" to "ఏంటంటే", "rendu" to "రెండు", "chese" to "చేసే", "cheyyagaladu" to "చేయగలదు", "prathi" to "ప్రతి", "sari" to "సారి", "manam" to "మనం", "rayalsina" to "రాయాల్సిన", "avasaram" to "అవసరం", "gurthupettukondi" to "గుర్తుపెట్టుకోండి", "chesukondi" to "చేసుకోండి", "daggara" to "దగ్గర", "lo" to "లో", "ani" to "అని", "teesukondi" to "తీసుకోండి", 
        "React" to "రియాక్ట్", "Hooks" to "హుక్స్", "HOOKS" to "హుక్స్", "5" to "ఫైవ్", "different" to "డిఫరెంట్", "purposes" to "పర్పసెస్",
        "useState" to "యూజ్ స్టేట్", "values" to "వాల్యూస్", "store" to "స్టోర్", "Value" to "వాల్యూ", "value" to "వాల్యూ", "change" to "చేంజ్",
        "component" to "కాంపోనెంట్", "re" to "రీ", "render" to "రెండర్", "Simple" to "సింపుల్", "update" to "అప్డేట్", "use" to "యూజ్",
        "useEffect" to "యూజ్ ఎఫెక్ట్", "systems" to "సిస్టమ్స్", "connect" to "కనెక్ట్", "Examples" to "ఎగ్జాంపుల్స్", "Timers" to "టైమర్స్",
        "Event" to "ఈవెంట్", "Listeners" to "లిజనర్స్", "Browser" to "బ్రౌజర్", "APIs" to "ఏపీఐస్", "calculate" to "క్యాలిక్యులేట్", "Direct" to "డైరెక్ట్",
        "useRef" to "యూజ్ రెఫ్", "trigger" to "ట్రిగ్గర్", "DOM" to "డామ్", "elements" to "ఎలిమెంట్స్", "access" to "యాక్సెస్", "Timer" to "టైమర్",
        "IDs" to "ఐడీస్", "Ref" to "రెఫ్", "current" to "కరెంట్", "useMemo" to "యూజ్ మెమో", "heavy" to "హెవీ", "calculations" to "క్యాలిక్యులేషన్స్",
        "result" to "రిజల్ట్", "cache" to "క్యాష్", "Dependencies" to "డిపెండెన్సీస్", "calculation" to "క్యాలిక్యులేషన్", "execute" to "ఎగ్జిక్యూట్",
        "unnecessary" to "అన్నెసెసరీ", "complexity" to "కాంప్లెక్సిటీ", "benefit" to "బెనిఫిట్", "useCallback" to "యూజ్ కాల్‌బ్యాక్", "function" to "ఫంక్షన్",
        "reference" to "రిఫరెన్స్", "stable" to "స్టేబుల్", "maintain" to "మెయింటైన్", "important" to "ఇంపార్టెంట్", "point" to "పాయింట్", "Compiler" to "కంపైలర్",
        "enable" to "ఎనేబుల్", "handle" to "హ్యాండిల్", "memoization" to "మెమోయిజేషన్", "optimizations" to "ఆప్టిమైజేషన్స్", "automatic" to "ఆటోమేటిక్",
        "So" to "సో", "manually" to "మాన్యువల్లీ", "Final" to "ఫైనల్", "rule" to "రూల్", "Screen" to "స్క్రీన్", "External" to "ఎక్స్‌టర్నల్", "Without" to "విత్అవుట్",
        "memory" to "మెమరీ", "Calculation" to "క్యాలిక్యులేషన్", "caching" to "క్యాషింగ్", "Function" to "ఫంక్షన్", "cheat" to "చీట్", "Cheat" to "చీట్", "sheet" to "షీట్",
        "save" to "సేవ్", "Next" to "నెక్స్ట్", "time" to "టైమ్", "confuse" to "కన్ఫ్యూజ్", "Comment" to "కామెంట్", "type" to "టైప్",
    )

    private fun spoken(word: String): List<String> = (heardAs[word] ?: word).split(" ")

    private class Result(val longestStall: Int, val biggestJump: Int, val end: Int, val total: Int)

    private fun read(script: String, spell: (String) -> List<String>): Result {
        val m = ScriptMatcher(script, ScriptLanguage.TELUGU)
        val heard = mutableListOf<String>()
        var stall = 0; var longest = 0; var biggest = 0
        for (w in m.words) {
            val before = m.position
            for (t in spell(w.text)) { heard += t; m.onHeard(heard.takeLast(12).joinToString(" ")) }
            val moved = m.position - before
            biggest = maxOf(biggest, moved)
            stall = if (moved <= 0) stall + 1 else 0
            longest = maxOf(longest, stall)
        }
        return Result(longest, biggest, m.position, m.words.size)
    }

    private fun assertSmooth(r: Result) {
        assertTrue("stalled for ${r.longestStall} words", r.longestStall <= 2)
        assertTrue("jumped ${r.biggestJump} words", r.biggestJump <= 4)
        assertEquals(r.total, r.end)
    }

    @Test
    fun tenglishScriptFollowsTeluguLettersSpeech() = assertSmooth(read(tenglish, ::spoken))

    @Test
    fun teluguLettersScriptFollowsTeluguLettersSpeech() = assertSmooth(read(teluguLetters, ::spoken))

    @Test
    fun tenglishScriptFollowsSpeechWithEnglishKeptInEnglish() {
        assertSmooth(read(tenglish) { listOf(it) })
    }

    @Test
    fun joinedWordsAreSplitButKeepTheirPlace() {
        val words = ScriptMatcher("useState re-render", ScriptLanguage.TELUGU).words
        assertEquals(listOf("use", "State", "re", "render"), words.map { it.text })
        assertEquals(listOf(0, 3, 9, 12), words.map { it.start })
    }

    @Test
    fun englishWordsMatchHowTheySound() {
        fun sim(en: String, te: String) = ScriptMatcher.bestSimilarity(
            Phonetic.soundKeys(en), Phonetic.soundKeys(te),
        )
        listOf("time" to "టైమ్", "type" to "టైప్", "save" to "సేవ్", "cheat" to "చీట్", "cache" to "క్యాష్",
            "use" to "యూజ్", "execute" to "ఎగ్జిక్యూట్", "5" to "ఫైవ్", "5" to "ఐదు", "IDs" to "ఐడీస్", "APIs" to "ఏపీఐస్",
        ).forEach { (en, te) -> assertTrue("$en vs $te: ${sim(en, te)}", sim(en, te) >= 0.6) }
    }
}
