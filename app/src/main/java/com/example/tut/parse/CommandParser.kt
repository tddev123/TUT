package com.example.tut.parse

import com.example.tut.data.RecoveryFeeling
import com.example.tut.data.RirLevel
import com.example.tut.data.SetKind
import com.example.tut.data.WeightUnit
import kotlin.math.roundToInt

/** An action word (or phrase) recognised in the transcript. */
sealed interface Action {
    /** What the phone says back when the action is recognised. */
    val spoken: String

    /** A value for a set (or which set / exercise), as opposed to a command like start or done. */
    val isSetValue: Boolean
        get() = this is Exercise || this is SelectSet || this is Rest || this is Weight ||
            this is Reps || this is Tut || this is Rir || this is EndRange

    data object Start : Action { override val spoken = "start" }
    data object Stop : Action { override val spoken = "stop" }
    data object Save : Action { override val spoken = "save" }
    /** Finishes the current set ("done", "finished"); the controller builds the spoken reply. */
    data object Done : Action { override val spoken = "done" }
    /** Starts (or continues) the session's current voice recording. */
    data object AddNote : Action { override val spoken = "recording" }
    /** Starts a separate, new voice recording. */
    data object NewNote : Action { override val spoken = "new recording" }
    /** Stops and saves the voice recording. */
    data object EndNote : Action { override val spoken = "recording saved" }
    data class Exercise(val name: String) : Action { override val spoken get() = "exercise $name" }
    data class SelectSet(val kind: SetKind, val number: Int) : Action {
        override val spoken get() = "${kind.spoken} $number"
    }
    data class Rest(val seconds: Int) : Action { override val spoken get() = "${speakDuration(seconds)} rest" }
    data class Weight(val value: Double, val unit: WeightUnit?) : Action {
        override val spoken get() = "${fmtNumber(value)} ${unit?.spoken ?: ""}".trim()
    }
    data class Reps(val count: Int) : Action { override val spoken get() = if (count == 1) "1 rep" else "$count reps" }
    data class Tut(val seconds: Int) : Action {
        override val spoken get() = "${speakDuration(seconds)} time under tension"
    }
    data class Rir(val level: RirLevel?, val value: Int?) : Action {
        override val spoken get() = if (level != null) "${level.label.lowercase()} R I R" else "R I R $value"
    }
    /** Seconds the end range position was held: "end range 10 seconds", "10 second hold". Optional. */
    data class EndRange(val seconds: Int) : Action {
        override val spoken get() = "${speakDuration(seconds)} end range hold"
    }
    /**
     * Copies every value of another set of this exercise into the current set: "working set 2,
     * copy working set 1". [kind] null ("copy set 1") means the same kind as the current set.
     */
    data class CopySet(val kind: SetKind?, val number: Int) : Action {
        override val spoken get() = "copy ${kind?.spoken ?: "set"} $number"
    }
    /** Hours slept before this workout; goes on the big data block, not on a set. */
    data class Sleep(val hours: Double) : Action {
        override val spoken get() = "${fmtNumber(hours)} ${if (hours == 1.0) "hour" else "hours"} of sleep"
    }
    /** How recovered the lifter feels; goes on the big data block, and is never a prediction input. */
    data class Recovery(val feeling: RecoveryFeeling) : Action {
        override val spoken get() = "recovery ${feeling.label.lowercase()}"
    }
}

fun fmtNumber(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

fun speakDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    val parts = mutableListOf<String>()
    if (m > 0) parts += if (m == 1) "1 minute" else "$m minutes"
    if (s > 0 || m == 0) parts += if (s == 1) "1 second" else "$s seconds"
    return parts.joinToString(" ")
}

fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * Turns transcribed speech into [Action]s. Only action phrases are extracted; any words
 * spoken in between are ignored.
 */
object CommandParser {

    /** One built-in action word: [word] as the parser sees it after normalising, and what it's for. */
    data class BuiltInWord(val word: String, val use: String)

    /** Every built-in action word, in the order shown in settings. */
    val builtInWords = listOf(
        BuiltInWord("start", "Start taking in data"),
        BuiltInWord("stop", "Stop taking in data"),
        BuiltInWord("save", "Save the session (asks yes / no)"),
        BuiltInWord("yes", "Answer yes to the save question"),
        BuiltInWord("no", "Answer no to the save question"),
        BuiltInWord("done", "Finish the current set"),
        BuiltInWord("finished", "Finish the current set, same as done"),
        BuiltInWord("copy", "Copy another set into the current one: \"working set 2, copy working set 1\""),
        BuiltInWord("exercise", "Name the exercise: \"exercise bench press\""),
        BuiltInWord("warm up", "Pick a warm-up set: \"warm up set 1\""),
        BuiltInWord("working", "Pick a working set: \"working set 2\""),
        BuiltInWord("set", "Goes with warm up / working: \"working set 2\""),
        BuiltInWord("weight", "\"weight 100\""),
        BuiltInWord("pounds", "\"100 pounds\""),
        BuiltInWord("kg", "\"40 kg\""),
        BuiltInWord("reps", "\"12 reps\""),
        BuiltInWord("tut", "Time under tension: \"tut 45\", \"1 minute tut\""),
        BuiltInWord("rest", "\"2 minutes rest\""),
        BuiltInWord("minutes", "\"2 minutes\""),
        BuiltInWord("seconds", "\"90 seconds\""),
        BuiltInWord("end range", "Seconds the end range was held (optional): \"end range 10 seconds\""),
        BuiltInWord("hold", "Same as end range: \"10 second hold\""),
        BuiltInWord("rir", "Reps in reserve: \"rir 2\", \"high rir\""),
        BuiltInWord("high", "\"high rir\""),
        BuiltInWord("moderate", "\"moderate rir\""),
        BuiltInWord("low", "\"low rir\""),
        BuiltInWord("recording", "Voice notes: \"add recording\", \"new recording\", \"done recording\""),
        BuiltInWord("add", "\"add recording\": continue the voice note"),
        BuiltInWord("new", "\"new recording\": start a separate voice note"),
        BuiltInWord("sleep", "Hours slept, for the whole block: \"8 hours of sleep\""),
        BuiltInWord("recovery", "For the whole block: \"recovery feeling fresh\" (very fresh, fresh, can't tell, kind of sore, sore)"),
    )

    fun parse(text: String, words: Map<String, String> = emptyMap()): List<Action> =
        parseWithRuns(text, words = words).first.map { it.first }

    /**
     * Like [parse], but each action also comes with how many times its word has been said in a
     * row, counting on from [run] (the run left over from earlier speech). Only "start" and "stop"
     * are counted; any other word in between, action word or not, starts the count over. Also
     * returns the run to carry into the next piece of speech. [words] renames built-in words.
     */
    fun parseWithRuns(
        text: String, run: WordRun = WordRun(), words: Map<String, String> = emptyMap(),
    ): Pair<List<Pair<Action, Int>>, WordRun> {
        val norm = normalize(text, words)
        val matches = matches(norm)
        val at = matches.associateBy { it.first }
        var current = run
        val counts = mutableMapOf<Int, Int>()
        for (m in Regex("\\S+").findAll(norm)) {
            val action = at[m.range.first]?.third
            current = if (action == Action.Start || action == Action.Stop) {
                val n = if (current.action == action) current.count + 1 else 1
                counts[m.range.first] = n
                WordRun(action, n)
            } else WordRun()
        }
        return matches.map { it.third to (counts[it.first] ?: 1) } to current
    }

    /** The last "start" / "stop" said and how many times in a row. */
    data class WordRun(val action: Action? = null, val count: Int = 0)

    private fun matches(norm: String): List<Triple<Int, Int, Action>> {
        if (norm.isBlank()) return emptyList()
        val found = mutableListOf<Triple<Int, Int, Action>>()
        for ((regex, build) in rules) {
            for (m in regex.findAll(norm)) {
                val action = build(m) ?: continue
                found += Triple(m.range.first, m.range.last, action)
            }
        }
        // Keep matches in spoken order; when two overlap, the earlier (then longer) one wins.
        found.sortWith(compareBy<Triple<Int, Int, Action>> { it.first }.thenByDescending { it.second })
        val result = mutableListOf<Triple<Int, Int, Action>>()
        var end = -1
        for (f in found) {
            if (f.first > end) {
                result += f
                end = f.second
            }
        }
        return result
    }

    // ---------------------------------------------------------------- yes / no

    private val yesWords = Regex("""\b(?:yes|yeah|yep|yup|yah|ya|yes please|confirm|confirmed|sure|okay|ok|save|save it|do it)\b""")
    private val noWords = Regex("""\b(?:no|nope|nah|abort|cancel|never mind|nevermind|not now|dont save|dont)\b""")

    /**
     * Answer to a yes/no question: true for "yes"/"confirm", false for "no"/"abort"/"never mind",
     * null when it's neither (or both).
     */
    fun answer(text: String, words: Map<String, String> = emptyMap()): Boolean? {
        val norm = normalize(text, words)
        // "don't save" must not count as "save"
        val yes = yesWords.containsMatchIn(norm.replace(Regex("""\bdont \w+"""), " "))
        val no = noWords.containsMatchIn(norm)
        return when {
            yes && !no -> true
            no && !yes -> false
            else -> null
        }
    }

    // ---------------------------------------------------------------- normalisation

    private val units = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val teens = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val ordinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11,
        "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15,
        "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19, "twentieth" to 20,
    )

    /**
     * Built-in words the user renamed in settings ([words]: built-in word -> their word), as
     * normalised phrase -> what it becomes. The new word turns into the built-in one, and the
     * old built-in word stops working (it becomes nothing).
     */
    private fun renames(words: Map<String, String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val dropped = mutableListOf<String>()
        for ((builtIn, custom) in words) {
            val heard = normalize(custom).takeIf { it.isNotBlank() } ?: continue
            if (heard == builtIn) continue
            out[heard] = builtIn
            dropped += builtIn
        }
        for (d in dropped) if (d !in out) out[d] = ""
        return out
    }

    /** One pass, so two words can swap ("start" = stop, "stop" = start). */
    private fun rename(text: String, words: Map<String, String>): String {
        val map = renames(words)
        if (map.isEmpty() || text.isBlank()) return text
        val alternatives = map.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        return Regex("""(?<![a-z0-9])(?:$alternatives)(?![a-z0-9])""").replace(text) { map[it.value] ?: it.value }
            .replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Lower-cases, strips punctuation, unifies vocabulary and converts spoken numbers to digits.
     * [words] renames built-in action words (see [builtInWords]).
     */
    fun normalize(text: String, words: Map<String, String>): String = rename(normalize(text), words)

    /** Lower-cases, strips punctuation, unifies vocabulary and converts spoken numbers to digits. */
    fun normalize(text: String): String {
        var t = text.lowercase()
            .replace('’', '\'')
            .replace(Regex("""(\d),(\d{3})"""), "$1$2")          // 1,000 -> 1000
            // "ten pounds" often comes back as "£10"; "10lbs" / "10kg" without a space
            .replace(Regex("""£\s*(\d+(?:\.\d+)?)"""), "$1 pounds ")
            .replace(Regex("""(\d)(lbs?|kgs?|kilos?|pounds?)\b"""), "$1 $2")
            // R.I.R. and how it gets misheard: "R A R", "rar", "rer", "rur"
            .replace(Regex("""\br\s*\.?\s*[aeiuy]\s*\.?\s*rs?\b\.?"""), " rir ")
            .replace(Regex("""\b(?:rpe|r i r s|rirs)\b"""), " rir ")
            .replace(Regex("""[\[(].*?[\])]"""), " ")             // [BLANK_AUDIO], (music)
            .replace(Regex("""(?<!\d)\.|\.(?!\d)"""), " ")        // keep decimal points only
            .replace(Regex("""[^a-z0-9:.' ]"""), " ")
            .replace("'", "")
        // Ordinals written with digits ("1st") and spelled out ("first").
        t = t.replace(Regex("""\b(\d+)(?:st|nd|rd|th)\b"""), "$1")
        // "second" right after a number is the unit, not an ordinal: "30 second rest", "thirty-second rest".
        val words = t.split(Regex("\\s+"))
        t = words.mapIndexed { i, w ->
            val prev = words.getOrNull(i - 1)
            val afterNumber = prev != null && (prev.toDoubleOrNull() != null || prev in units || prev in teens || prev in tens || prev == "hundred" || prev == "half")
            if (w == "second" && afterNumber) w else ordinals[w]?.toString() ?: w
        }.joinToString(" ")
        t = fixMisheardWords(t)
        // Words commonly heard in place of a set number: "set to" = set 2, "set for" = set 4.
        t = t.replace(Regex("""\bsets? (?:to|too)\b"""), "set two")
            .replace(Regex("""\bsets? (?:for|fore)\b"""), "set four")
            .replace(Regex("""\bsets? (?:won|juan)\b"""), "set one")
            .replace(Regex("""\bsets? (?:tree|free)\b"""), "set three")
            .replace(Regex("""\bsets? ate\b"""), "set eight")
        t = wordsToNumbers(t)
        val replacements = listOf(
            """\bvoice\s*notes?\b""" to "voice note",
            """\bwarm\s*ups?\b""" to "warm up",
            """\bwarming up\b""" to "warm up",
            """\bwork in\b|\bworkin\b|\bworking\b|\bwork\b""" to "working",
            """\bsets\b""" to "set",
            """\btime under tension\b|\bt u t\b|\btut\b""" to "tut",
            """\b(?:pounds?|lbs?|lb)\b""" to "pounds",
            """\b(?:kilograms?|kilos?|kgs?|kilogrammes?)\b""" to "kg",
            """\b(?:repetitions?|reps?|rips|raps)\b""" to "reps",
            """\b(?:minutes?|mins?)\b""" to "minutes",
            """\b(?:seconds?|secs?)\b""" to "seconds",
            """\b(?:hours?|hrs?)\b""" to "hours",
            """\b(?:slept|sleeping|sleeps)\b""" to "sleep",
            """\b(?:medium|mid|moderately)\b""" to "moderate",
            """\b(?:resting|rested|breathe|break)\b""" to "rest",
            """\b(\d+)\s*s\b""" to "$1 seconds",
            """\b(\d+)\s*m\b""" to "$1 minutes",
        )
        for ((pattern, rep) in replacements) t = t.replace(Regex(pattern), rep)
        t = fixMisheardUnits(t.replace(Regex("\\s+"), " ").trim())
        return t
    }

    // ---------------------------------------------------------------- misheard words
    //
    // Near-misses the speech model produces for action words. Most only apply next to the
    // word they belong with ("sex" is only "set" right after "warm up"/"working"), so a
    // misheard word can never turn into a different action.

    private const val WARM_HEARD = """warm\s*ups?|warming up|warmed up|worm\s*ups?|warn\s*ups?"""
    private const val WORK_HEARD = """working|workin|work in|work|walking|walkin|werking|worked"""
    private const val SET_HEARD = """sets?|sex|sexes|said|sit|sat|sent|sed|seth|sad|sec|sax"""
    private const val NOTE_AFTER = """(?= (?:a |the )?(?:voice )?(?:recording|record|note)\b)"""
    private val warmHeard = Regex("""^(?:$WARM_HEARD)$""")

    /** Runs on lower-cased, punctuation-free text, before numbers and units are normalised. */
    private fun fixMisheardWords(input: String): String {
        var t = input
        // "are eye are", "our i r" for RIR
        t = t.replace(Regex("""\b(?:r|are|our)\s+(?:i|eye|a|e)\s+(?:r|are)\b"""), "rir")
        // "warm up sex 2", "walking set 1" (walking only next to set, so "walking lunges" stays)
        t = t.replace(Regex("""\b($WARM_HEARD|$WORK_HEARD)\s+(?:$SET_HEARD)\b""")) { m ->
            if (warmHeard.matches(m.groupValues[1])) "warm up set" else "working set"
        }
        // "time on tension", "time under attention", "toot"
        t = t.replace(
            Regex("""\btime (?:under|on|and|in|of|under a|in a|onder) (?:tension|tensions|attention|tenshun|tention|pension|tenson)\b"""),
            "time under tension",
        )
            .replace(Regex("""\b(?:tee you tee|t you t|tuts|tutt|toot|toots)\b"""), "tut")
            // "boys note", "voice not"
            .replace(Regex("""\b(?:voice|voices|boys|choice|vice)\s*(?:notes?|not|knot|nope|nodes?)\b"""), "voice note")
            .replace(Regex("""\b(?:exorcise|excercise|exercize|exercice|exercises)\b"""), "exercise")
            // "done", "add", "new" heard wrong, only in front of recording/note
            .replace(Regex("""\b(?:dun|dunn|donne)\b"""), "done")
            .replace(Regex("""\b(?:don|dawn)\b$NOTE_AFTER"""), "done")
            .replace(Regex("""\bad\b$NOTE_AFTER"""), "add")
            .replace(Regex("""\b(?:knew|nu|gnu)\b$NOTE_AFTER"""), "new")
            // "done according" / "stop recordin", only right after a voice-note command word
            .replace(Regex("""\b(done|stop|end|finish|finished|new|add)((?: a| the)?(?: voice)?) (?:recordin|recordings|record in|recordeng|according|a cording)\b"""), "$1$2 recording")
            .replace(Regex("""\b(?:stopp|stahp|stob)\b"""), "stop")
            // "recover feeling soar", "recovery feeling kind of sour", only right after "recovery"
            .replace(Regex("""\b(?:recover|recoveries|recovering)\b(?= (?:feeling|feelings|feel|feels|is|was|very|fresh|cant|kind|kinda|sore|soar|sour)\b)"""), "recovery")
            .replace(Regex("""\b(recovery(?: feeling| feelings| feel| feels)?(?: is| was| like)?(?: kind of| kinda)?) (?:soar|sour|soared|sorry|sor|soor)\b"""), "$1 sore")
            .replace(Regex("""\b(recovery(?: feeling| feelings| feel| feels)?(?: is| was| like)?(?: very)?) (?:flesh|fresch|french|frech|frash)\b"""), "$1 fresh")
            .replace(Regex("""\b(?:wrest|rests|rust|rist)\b"""), "rest")
            // "and range", "in range", "end ranged" for end range
            .replace(Regex("""\b(?:end|and|in|an) (?:range|ranged|ranges|rains|rage)\b"""), "end range")
            // "coffee working set 1", only right before a set
            .replace(Regex("""\b(?:coffee|copie|copies|copied|kopi|copy of)\b(?= (?:the |from )?(?:warm up set|working set|set)\b)"""), "copy")
            // "rest to minutes" = 2 minutes
            .replace(Regex("""\b(?:to|too) (?=(?:minutes?|mins?)\b)"""), "two ")
        return t
    }

    /** Runs on fully normalised text (digits, canonical units), so each fix can check its neighbours. */
    private fun fixMisheardUnits(input: String): String {
        val num = """(\d+(?:\.\d+)?)"""
        var t = input
            .replace(Regex("""\b$num (?:ponds?|pounce|bounds?|pouns|pounders)\b"""), "$1 pounds")
            .replace(Regex("""\b$num (?:k g|kay gee|kg grams?|keelos?|kilows?)\b"""), "$1 kg")
            .replace(Regex("""\b$num (?:minets|minits|minuts|minuets|minnits|minites)\b"""), "$1 minutes")
            .replace(Regex("""\b$num (?:secant|secants|seconts|sekonds|seconz)\b"""), "$1 seconds")
            // "12 wraps", "ribs 8"
            .replace(Regex("""\b$num (?:wraps|ribs|rebs|wreps|repps|rapps)\b"""), "$1 reps")
            .replace(Regex("""\b(?:wraps|ribs|rebs|wreps|repps|rapps) (\d+)\b"""), "reps $1")
            // "wait 100" = weight 100, but "wait 2 minutes" is left alone
            .replace(
                Regex("""\b(?:wait|weigh|weighed|weights|wade)\b(?=(?: is| of| was)? \d+(?:\.\d+)?(?![\d:.])(?! (?:minutes|seconds|rest|reps|rir|tut|warm|working|set)\b))"""),
                "weight",
            )
        // RIR levels, only right next to "rir": "hi rir", "rir modern", "lo rir"
        val levels = listOf(
            "high" to """hi|hey|hy|hai|hie|hire|height""",
            "low" to """lo|lowe|law|lou|loe|slow|blow""",
            "moderate" to """modern|moderated|moderates|modrate|moderat|madrid|modest|median""",
        )
        for ((level, heard) in levels) {
            t = t.replace(Regex("""\b(?:$heard)(?= rir\b)"""), level)
                .replace(Regex("""\b(rir(?: is| of| was)?) (?:$heard)\b"""), "$1 $level")
        }
        // Single-word commands that are only fixed when said on their own at the end
        // ("safe." = save), since "safe"/"star" can be ordinary words mid-sentence.
        t = t.replace(Regex("""\bsafe$"""), "save")
            .replace(Regex("""\b(?:don|dawn)$"""), "done")
            .replace(Regex("""\b(?:star|stark|stuart)$"""), "start")
        return t
    }

    /**
     * Converts number words to digits, e.g. "two hundred and twenty five" -> 225,
     * "two point five" -> 2.5, "two and a half" -> 2.5, "a minute" -> 1 minute.
     * Consecutive simple numbers stay separate: "set one two minutes" -> "set 1 2 minutes".
     */
    fun wordsToNumbers(input: String): String {
        // "a minute" / "half a minute" / "a minute and a half"
        val joined = input
            .replace(Regex("""\bhalf (?:a|an) minute\b"""), "thirty seconds")
            .replace(Regex("""\ba minute and a half\b"""), "one point five minutes")
            .replace(Regex("""\ba minute\b"""), "one minute")
            .replace(Regex("""\ba hundred\b"""), "one hundred")
        val tokens = joined.split(' ').filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val start = i
            var hundreds = 0
            var cur = 0
            var last = "" // "unit", "teen", "tens", "hundred"
            var used = false
            loop@ while (i < tokens.size) {
                val w = tokens[i]
                when {
                    units.containsKey(w) && (last == "" || last == "tens" || last == "hundred") &&
                        !(w == "oh" && last == "") -> {
                        cur += units[w]!!; last = "unit"; used = true
                    }
                    teens.containsKey(w) && (last == "" || last == "hundred") -> {
                        cur += teens[w]!!; last = "teen"; used = true
                    }
                    tens.containsKey(w) && (last == "" || last == "hundred") -> {
                        cur += tens[w]!!; last = "tens"; used = true
                    }
                    w == "hundred" && (last == "unit" || last == "teen") && hundreds == 0 -> {
                        hundreds = cur * 100; cur = 0; last = "hundred"
                    }
                    w == "and" && last == "hundred" && i + 1 < tokens.size &&
                        (units.containsKey(tokens[i + 1]) || teens.containsKey(tokens[i + 1]) || tens.containsKey(tokens[i + 1])) -> Unit
                    else -> break@loop
                }
                i++
            }
            if (!used && last != "hundred") {
                // Not a number word: digits may still be followed by "point five" / "and a half".
                val w = tokens[i]
                i++
                val num = w.toDoubleOrNull()
                if (num != null) {
                    val (v, next) = fraction(tokens, i, num)
                    // Keep "2.10" as written: it may be a time (2:10), not 2.1.
                    out += if (next == i) w else fmt(v)
                    i = next
                } else {
                    out += w
                }
                continue
            }
            val (v, next) = fraction(tokens, i, (hundreds + cur).toDouble())
            i = next
            if (i == start) i++ // safety
            out += fmt(v)
        }
        return out.joinToString(" ")
    }

    private fun fraction(tokens: List<String>, from: Int, value: Double): Pair<Double, Int> {
        var i = from
        if (i + 2 < tokens.size && tokens[i] == "and" && tokens[i + 1] == "a" && tokens[i + 2] == "half") {
            return (value + 0.5) to (i + 3)
        }
        if (i + 2 < tokens.size && tokens[i] == "and" && tokens[i + 1] == "a" && tokens[i + 2] == "quarter") {
            return (value + 0.25) to (i + 3)
        }
        if (i + 2 < tokens.size && tokens[i] == "and" && tokens[i + 1] == "three" && tokens[i + 2] == "quarters") {
            return (value + 0.75) to (i + 3)
        }
        if (i + 1 < tokens.size && tokens[i] == "point") {
            // "point twenty five" = .25, "point fifteen" = .15
            val next = tokens[i + 1]
            teens[next]?.let { return "${value.toLong()}.$it".toDouble() to (i + 2) }
            tens[next]?.let { t ->
                val unit = tokens.getOrNull(i + 2)?.let { units[it] }?.takeIf { it > 0 }
                return "${value.toLong()}.${t + (unit ?: 0)}".toDouble() to (i + if (unit != null) 3 else 2)
            }
            val digits = StringBuilder()
            i++
            while (i < tokens.size) {
                val d = units[tokens[i]] ?: tokens[i].takeIf { it.length == 1 && it[0].isDigit() }?.toInt() ?: break
                digits.append(d); i++
            }
            if (digits.isNotEmpty()) return "${value.toLong()}.$digits".toDouble() to i
            return value to from
        }
        return value to from
    }

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ---------------------------------------------------------------- rules

    private const val NUM = """(\d+(?:\.\d+)?)"""
    /** A spoken duration: 2:10 | 2.10 | 2 minutes 10 seconds | 2 minutes 10 | 2.5 minutes | 90 seconds */
    private const val DUR =
        """(?:(\d+)[:.](\d{2})(?!\d)|(\d+(?:\.\d+)?) minutes(?: (?:and )?(\d+)\b(?!\.\d)(?! (?:pounds|kg|reps|rir|tut))(?: seconds)?)?|(\d+) seconds)"""

    private fun durationSeconds(m: MatchResult, offset: Int): Int? {
        val g = m.groupValues
        return when {
            g[offset + 1].isNotEmpty() -> g[offset + 1].toInt() * 60 + g[offset + 2].toInt()
            g[offset + 3].isNotEmpty() -> (g[offset + 3].toDouble() * 60).roundToInt() + (g[offset + 4].toIntOrNull() ?: 0)
            g[offset + 5].isNotEmpty() -> g[offset + 5].toInt()
            else -> null
        }
    }

    /**
     * A bare number after "rest" or "tut". "2.10" is read as 2:10. For rest, a whole number
     * under 10 is minutes ("rest 2"); anything else is seconds.
     */
    fun bareDuration(v: String, smallIsMinutes: Boolean): Int? {
        val num = v.toDoubleOrNull() ?: return null
        val dot = v.indexOf('.')
        if (dot >= 0) {
            val whole = v.substring(0, dot).toInt()
            val frac = v.substring(dot + 1)
            return if (frac.length == 2) whole * 60 + frac.toInt() else (num * 60).roundToInt()
        }
        val n = num.toInt()
        return if (smallIsMinutes && n < 10) n * 60 else n
    }

    private fun rirLevel(w: String): RirLevel? = when (w) {
        "high" -> RirLevel.HIGH
        "moderate" -> RirLevel.MODERATE
        "low" -> RirLevel.LOW
        else -> null
    }

    private fun unit(w: String): WeightUnit? = when (w) {
        "pounds" -> WeightUnit.LBS
        "kg" -> WeightUnit.KG
        else -> null
    }

    private val rules: List<Pair<Regex, (MatchResult) -> Action?>> = listOf(
        Regex("""\bstart\b""") to { _ -> Action.Start },
        Regex("""\bstop\b""") to { _ -> Action.Stop },
        Regex("""\bsave\b""") to { _ -> Action.Save },
        Regex("""\b(?:done|finished)\b""") to { _ -> Action.Done },

        // Voice notes. These are longer than "done"/"stop", so they win where they overlap.
        Regex("""\b(?:done|stop|end|finish|finished) (?:the )?(?:voice )?(?:recording|note)\b""") to { _ -> Action.EndNote },
        Regex("""\bnew (?:voice )?(?:recording|note)\b""") to { _ -> Action.NewNote },
        Regex("""\badd (?:a |the )?(?:voice )?(?:recording|note)\b""") to { _ -> Action.AddNote },
        Regex("""\b(?:record|start) (?:a )?voice note\b""") to { _ -> Action.AddNote },

        // Exercise name: up to four words, ending before the next action phrase.
        Regex("""\bexercise (?:is |name |named |called )?((?:(?!(?:warm up|working|rest|set|start|stop|save|done|add|new|weight|tut|rir|reps|and)\b)[a-z]+ ?){1,4})""") to { m ->
            m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { Action.Exercise(it) }
        },

        Regex("""\b(warm up|working) set (?:number )?(\d{1,2})\b""") to { m ->
            val kind = if (m.groupValues[1] == "warm up") SetKind.WARMUP else SetKind.WORKING
            m.groupValues[2].toInt().takeIf { it in 1..20 }?.let { Action.SelectSet(kind, it) }
        },
        Regex("""\b(\d{1,2}) (warm up|working) set\b""") to { m ->
            val kind = if (m.groupValues[2] == "warm up") SetKind.WARMUP else SetKind.WORKING
            m.groupValues[1].toInt().takeIf { it in 1..20 }?.let { Action.SelectSet(kind, it) }
        },

        // Rest: "2 minutes rest", "rest time 2:10", "rest 90 seconds", "rest time 90"
        Regex("""\b$DUR (?:of )?rest\b""") to { m -> durationSeconds(m, 0)?.let { Action.Rest(it) } },
        Regex("""\brest(?: time)?(?: is| of| was)? $DUR""") to { m -> durationSeconds(m, 0)?.let { Action.Rest(it) } },
        Regex("""\brest(?: time)?(?: is| of| was)? $NUM\b(?! pounds| kg| reps| rir)""") to { m ->
            bareDuration(m.groupValues[1], smallIsMinutes = true)?.let { Action.Rest(it) }
        },

        // Time under tension: "1:20 tut", "tut 1 minute 20 seconds", "tut 45"
        Regex("""\b$DUR (?:of )?tut\b""") to { m -> durationSeconds(m, 0)?.let { Action.Tut(it) } },
        Regex("""\btut(?: is| of| was)? $DUR""") to { m -> durationSeconds(m, 0)?.let { Action.Tut(it) } },
        Regex("""\btut(?: is| of| was)? $NUM\b(?! pounds| kg| reps| rir)""") to { m ->
            bareDuration(m.groupValues[1], smallIsMinutes = false)?.let { Action.Tut(it) }
        },

        // End range hold: "end range 10 seconds", "end range hold 15", "10 second hold"
        Regex("""\b$DUR (?:of )?(?:end range(?: hold)?|hold)\b""") to { m -> durationSeconds(m, 0)?.let { Action.EndRange(it) } },
        Regex("""\b(?:end range(?: hold)?|hold)(?: is| of| was| for)? $DUR""") to { m -> durationSeconds(m, 0)?.let { Action.EndRange(it) } },
        Regex("""\b(?:end range(?: hold)?|hold)(?: is| of| was| for)? $NUM\b(?! pounds| kg| reps| rir)""") to { m ->
            bareDuration(m.groupValues[1], smallIsMinutes = false)?.let { Action.EndRange(it) }
        },

        // Copy another set into the current one: "copy working set 1", "same as set 1".
        // Starts before the set it names, so it wins over picking that set.
        Regex("""\b(?:copy|same as)(?: from)?(?: the)? (?:(warm up|working) )?set (?:number )?(\d{1,2})\b""") to { m ->
            val kind = when (m.groupValues[1]) {
                "warm up" -> SetKind.WARMUP
                "working" -> SetKind.WORKING
                else -> null
            }
            m.groupValues[2].toInt().takeIf { it in 1..20 }?.let { Action.CopySet(kind, it) }
        },

        // Weight: "9 pounds", "weight 100", "weight is 40 kg"
        Regex("""\bweight(?: is| of| was)? $NUM(?: (pounds|kg))?""") to { m ->
            Action.Weight(m.groupValues[1].toDouble(), unit(m.groupValues[2]))
        },
        Regex("""\b$NUM (pounds|kg)\b""") to { m -> Action.Weight(m.groupValues[1].toDouble(), unit(m.groupValues[2])) },

        // Reps: "30 reps", "reps 12"
        Regex("""\b(\d+) reps\b""") to { m -> Action.Reps(m.groupValues[1].toInt()) },
        Regex("""\breps(?: is| of| was)? (\d+)\b""") to { m -> Action.Reps(m.groupValues[1].toInt()) },

        // RIR: "moderate rir", "rir high", "rir 4", "4 rir"
        Regex("""\b(high|moderate|low) rir\b""") to { m -> Action.Rir(rirLevel(m.groupValues[1]), null) },
        Regex("""\brir(?: is| of| was)? (high|moderate|low)\b""") to { m -> Action.Rir(rirLevel(m.groupValues[1]), null) },
        Regex("""\brir(?: is| of| was)? (\d{1,2})\b""") to { m -> Action.Rir(null, m.groupValues[1].toInt()) },
        Regex("""\b(\d{1,2}) rir\b""") to { m -> Action.Rir(null, m.groupValues[1].toInt()) },

        // Sleep, for the whole big data block: "8 hours of sleep", "slept 6 hours", "sleep 7.5"
        Regex("""\b$NUM hours (?:of )?sleep\b""") to { m -> sleep(m.groupValues[1]) },
        Regex("""\bsleep(?: for| was| is| of)? $NUM\b(?! pounds| kg| reps| rir| minutes| seconds)(?: hours)?""") to { m -> sleep(m.groupValues[1]) },

        // Recovery feeling, for the whole big data block: "recovery feeling fresh", "recovery kind of sore"
        Regex("""\brecovery(?: feeling| feelings| feel| feels)?(?: is| was| like)? (very fresh|fresh|cant tell|cannot tell|can not tell|kind of sore|kinda sore|sore)\b""") to { m ->
            Action.Recovery(
                when (m.groupValues[1]) {
                    "very fresh" -> RecoveryFeeling.VERY_FRESH
                    "fresh" -> RecoveryFeeling.FRESH
                    "kind of sore", "kinda sore" -> RecoveryFeeling.KIND_OF_SORE
                    "sore" -> RecoveryFeeling.SORE
                    else -> RecoveryFeeling.CANT_TELL
                }
            )
        },
    )

    private fun sleep(v: String): Action? = v.toDouble().takeIf { it > 0 && it <= 24 }?.let { Action.Sleep(it) }
}
