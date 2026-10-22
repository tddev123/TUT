package com.example.tut.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class WeightUnit(val spoken: String, val short: String) {
    LBS("pounds", "lbs"), KG("kilograms", "kg")
}

/** Whether a set's effort is measured in reps or time under tension. */
enum class EffortMode { REPS, TUT }

/** Colours the set bubbles on the New data page can use. */
enum class BubbleColor(val label: String, val argb: Long) {
    GREEN("Green", 0xFF3DDC84), GOLD("Gold", 0xFFE6B422), BLUE("Blue", 0xFF4A9EFF),
    PURPLE("Purple", 0xFFA77BFF), ORANGE("Orange", 0xFFFFA23A), RED("Red", 0xFFFF5A5A),
    PINK("Pink", 0xFFFF6FB5), TEAL("Teal", 0xFF2EC4C4), GRAY("Gray", 0xFF8C9696),
}

/** What the phone does when it hears an action word. */
enum class FeedbackMode(val label: String) {
    VOICE("Voice"), SOUND("Sound"), SOUND_AND_VOICE("Sound + voice"), OFF("Nothing");

    val voice get() = this == VOICE || this == SOUND_AND_VOICE
    val sound get() = this == SOUND || this == SOUND_AND_VOICE
}

/** Sounds played when an action word is heard (android.media.ToneGenerator tones). */
enum class FeedbackSound(val label: String, val tone: Int, val durationMs: Int) {
    BEEP("Beep", android.media.ToneGenerator.TONE_PROP_BEEP, 150),
    DOUBLE_BEEP("Double beep", android.media.ToneGenerator.TONE_PROP_BEEP2, 300),
    CHIRP("Chirp", android.media.ToneGenerator.TONE_PROP_ACK, 250),
    PIP("Pip", android.media.ToneGenerator.TONE_CDMA_PIP, 150),
    CONFIRM("Confirm", android.media.ToneGenerator.TONE_SUP_CONFIRM, 350),
    CLICK("Click", android.media.ToneGenerator.TONE_CDMA_KEYPAD_VOLUME_KEY_LITE, 80),
    RISING("Rising", android.media.ToneGenerator.TONE_PROP_PROMPT, 300),
    LOW_BUZZ("Low buzz", android.media.ToneGenerator.TONE_PROP_NACK, 300),
}

/** What turns speech into text. */
enum class SttEngine(val label: String) {
    WHISPER("Whisper (model file below)"),
    GOOGLE("Phone's recognizer (Google on-device)"),
}

data class AppSettings(
    // Data blocks
    val weightUnit: WeightUnit = WeightUnit.LBS,
    val effortMode: EffortMode = EffortMode.REPS,
    val warmupSets: Int = 3,
    val workingSets: Int = 3,
    val rirEnabled: Boolean = true,
    val rirShowNumber: Boolean = false,
    val rirHigh: Int = 5,
    val rirModerate: Int = 3,
    val rirLow: Int = 1,
    // Progress colours: up by at least the "stronger" amount is green, down by at least the
    // "weaker" amount is red, anything in between is the same (gray).
    val weightUpThreshold: Float = 2.5f,
    val weightDownThreshold: Float = 2.5f,
    val repsUpThreshold: Int = 1,
    val repsDownThreshold: Int = 1,
    val tutUpThresholdSec: Int = 5,
    val tutDownThresholdSec: Int = 5,
    // Recording page
    val feedback: FeedbackMode = FeedbackMode.VOICE,
    val feedbackSound: FeedbackSound = FeedbackSound.BEEP,
    // Start / Stop get their own sounds when the response is sound only.
    val startSound: FeedbackSound = FeedbackSound.RISING,
    val stopSound: FeedbackSound = FeedbackSound.LOW_BUZZ,
    val doneSound: FeedbackSound = FeedbackSound.CONFIRM, // "done" finishes a set and makes its bubble
    val voiceName: String? = null,   // null = phone default
    val voicePitch: Float = 1.0f,
    val speechRate: Float = 1.0f,
    val transcriptSeconds: Int = 20,
    val warmupColor: BubbleColor = BubbleColor.GREEN,
    val workingColor: BubbleColor = BubbleColor.GOLD,
    val backHoldMs: Int = 2_000,      // hold on back to leave without saving; 0 = back just leaves
    val saveHoldMs: Int = 0,          // hold on Save to save; 0 = a tap saves
    val logoHoldMs: Int = 3_000,      // hold the main-page logo to switch TUT / Skills; 0 = off
    val resumeHoldMs: Int = 1_000,    // hold "Resume recording" in a big data block; 0 = a tap
    val deleteBlockHoldMs: Int = 3_000, // hold a big data block to be offered to delete it; 0 = off
    // Skills database
    val skillCapacityHours: Float = 2.0f, // daily skill capacity given to new skills, 0..16 in tenths
    val deleteRecordingHoldMs: Int = 1_000,
    val pickHoldMs: Int = 500, // hold a choice inside an exercise (fatigue, recovery, muscle feel) to pick it; 0 = a tap
    val renameExerciseHoldMs: Int = 1_000, // hold an exercise's name in the database to rename it; 0 = a tap // hold a recording in a big data block to delete it; 0 = off
    val addSkillHoldMs: Int = 1_000,      // hold the + under the skill blocks to add one; 0 = a tap adds
    val curveUnit: CurveUnit = CurveUnit.HOURS, // time axis of the adaptation curve
    val rapidInput: Boolean = false,  // say a whole set in one breath
    val startWordCount: Int = 1,      // times "start" must be said in a row to start
    val stopWordCount: Int = 1,       // times "stop" must be said in a row to stop
    // Activation words: saying the first word counts as the second. Starts with one empty pair.
    val wordAliases: List<WordAlias> = listOf(WordAlias()),
    val wordAliasTextSp: Int = 13,    // text size in the activation-word settings
    val commandWords: Map<String, String> = emptyMap(), // built-in action word -> the word said instead
    // Custom data: add big data blocks by hand under any block, and change their date and time.
    val customData: Boolean = false,
    // Progression: time under tension goes up first; at the goal, the weight goes up one step.
    val tutGoalSec: Int = 360,
    val loadStep: Float = 1f,
    val progressionRules: List<ProgressionRule> = emptyList(), // per exercise, instead of the two above
    // Exercises (by Predictions.key) whose progress compares every working set, not just the best one.
    val allSetsExercises: Set<String> = emptySet(),
    val setSameTutSec: Int = 30, // set by set: a set's time under tension must change this much to count as better / worse
    val setSameReps: Int = 1,    // ... or its reps, this much
    // Fatigue scoreboard: worse than last time adds a level; see Fatigue.
    val fatigueSameSec: Int = 30,          // within this much time under tension counts as the same
    val fatigueBigDropSec: Int = 180,      // a drop this big adds two levels
    val fatigueBigDropWeight: Float = 2f,  // so does the weight going down this much (0 = off)
    // Speech-to-text
    val sttEngine: SttEngine = SttEngine.WHISPER,
    val modelFileName: String = DEFAULT_MODEL,
    val sttThreads: Int = 4,
    val fastMode: Boolean = true,
    val livePartials: Boolean = true,
    val micSensitivity: Int = 5, // 1..SpeechEngine.MAX_SENSITIVITY
    val endPauseMs: Int = 700,   // pause that finishes an utterance
    val readbackDelayMs: Int = 0, // wait after "done" before reading the finished set back
    val noiseFilter: Int = 5,    // 1..10, how readily background noise counts as a pause
    val noiseFilterOn: Boolean = true,
    val noiseAdaptMs: Int = 1_000, // how long the background-noise level takes to follow a change
) {
    /** The time-under-tension goal and weight step for [exercise]: its own rule, else the defaults. */
    fun progressionFor(exercise: String): ProgressionRule =
        progressionRules.firstOrNull { it.name.isNotBlank() && it.name.trim().equals(exercise.trim(), ignoreCase = true) }
            ?: ProgressionRule("", tutGoalSec, loadStep)

    /** Whether [exercise]'s progress compares set 1 with set 1, set 2 with set 2... instead of best set with best set. */
    fun comparesAllSets(exercise: String): Boolean = Predictions.key(exercise) in allSetsExercises

    /** Everything that decides stronger / same / weaker; when it changes, colours are recalculated. */
    val thresholds: List<Any>
        get() = listOf(
            weightUpThreshold, weightDownThreshold, repsUpThreshold, repsDownThreshold, tutUpThresholdSec, tutDownThresholdSec,
            allSetsExercises, setSameTutSec, setSameReps,
        )

    companion object {
        const val DEFAULT_MODEL = "ggml-small.en-q8_0.bin"
        const val MAX_SETS = 20
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tut_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            weightUnit = enumOr(prefs.getString("weightUnit", null), d.weightUnit),
            effortMode = enumOr(prefs.getString("effortMode", null), d.effortMode),
            warmupSets = prefs.getInt("warmupSets", d.warmupSets),
            workingSets = prefs.getInt("workingSets", d.workingSets),
            rirEnabled = prefs.getBoolean("rirEnabled", d.rirEnabled),
            rirShowNumber = prefs.getBoolean("rirShowNumber", d.rirShowNumber),
            rirHigh = prefs.getInt("rirHigh", d.rirHigh),
            rirModerate = prefs.getInt("rirModerate", d.rirModerate),
            rirLow = prefs.getInt("rirLow", d.rirLow),
            weightUpThreshold = prefs.getFloat("weightUpThreshold", d.weightUpThreshold),
            weightDownThreshold = prefs.getFloat("weightDownThreshold", d.weightDownThreshold),
            repsUpThreshold = prefs.getInt("repsUpThreshold", d.repsUpThreshold),
            repsDownThreshold = prefs.getInt("repsDownThreshold", d.repsDownThreshold),
            tutUpThresholdSec = prefs.getInt("tutUpThresholdSec", d.tutUpThresholdSec),
            tutDownThresholdSec = prefs.getInt("tutDownThresholdSec", d.tutDownThresholdSec),
            // Older versions only had an on/off "speak back" switch.
            feedback = enumOr(
                prefs.getString("feedback", null),
                if (prefs.getBoolean("speakBack", true)) d.feedback else FeedbackMode.OFF,
            ),
            feedbackSound = enumOr(prefs.getString("feedbackSound", null), d.feedbackSound),
            startSound = enumOr(prefs.getString("startSound", null), d.startSound),
            stopSound = enumOr(prefs.getString("stopSound", null), d.stopSound),
            doneSound = enumOr(prefs.getString("doneSound", null), d.doneSound),
            voiceName = prefs.getString("voiceName", null),
            voicePitch = prefs.getFloat("voicePitch", d.voicePitch),
            speechRate = prefs.getFloat("speechRate", d.speechRate),
            transcriptSeconds = prefs.getInt("transcriptSeconds", d.transcriptSeconds),
            warmupColor = enumOr(prefs.getString("warmupColor", null), d.warmupColor),
            workingColor = enumOr(prefs.getString("workingColor", null), d.workingColor),
            backHoldMs = prefs.getInt("backHoldMs", d.backHoldMs),
            saveHoldMs = prefs.getInt("saveHoldMs", d.saveHoldMs),
            logoHoldMs = prefs.getInt("logoHoldMs", d.logoHoldMs),
            resumeHoldMs = prefs.getInt("resumeHoldMs", d.resumeHoldMs),
            deleteBlockHoldMs = prefs.getInt("deleteBlockHoldMs", d.deleteBlockHoldMs),
            skillCapacityHours = prefs.getFloat("skillCapacityHours", d.skillCapacityHours),
            addSkillHoldMs = prefs.getInt("addSkillHoldMs", d.addSkillHoldMs),
            deleteRecordingHoldMs = prefs.getInt("deleteRecordingHoldMs", d.deleteRecordingHoldMs),
            renameExerciseHoldMs = prefs.getInt("renameExerciseHoldMs", d.renameExerciseHoldMs),
            pickHoldMs = prefs.getInt("pickHoldMs", d.pickHoldMs),
            curveUnit = enumOr(prefs.getString("curveUnit", null), d.curveUnit),
            rapidInput = prefs.getBoolean("rapidInput", d.rapidInput),
            startWordCount = prefs.getInt("startWordCount", d.startWordCount),
            stopWordCount = prefs.getInt("stopWordCount", d.stopWordCount),
            wordAliases = prefs.getString("wordAliases", null)?.let(::aliasesFromJson) ?: d.wordAliases,
            wordAliasTextSp = prefs.getInt("wordAliasTextSp", d.wordAliasTextSp),
            commandWords = prefs.getString("commandWords", null)?.let(::wordsFromJson) ?: d.commandWords,
            customData = prefs.getBoolean("customData", d.customData),
            tutGoalSec = prefs.getInt("tutGoalSec", d.tutGoalSec),
            loadStep = prefs.getFloat("loadStep", d.loadStep),
            progressionRules = prefs.getString("progressionRules", null)?.let(::rulesFromJson) ?: d.progressionRules,
            allSetsExercises = prefs.getStringSet("allSetsExercises", null)?.toSet() ?: d.allSetsExercises,
            setSameTutSec = prefs.getInt("setSameTutSec", d.setSameTutSec),
            setSameReps = prefs.getInt("setSameReps", d.setSameReps),
            fatigueSameSec = prefs.getInt("fatigueSameSec", d.fatigueSameSec),
            fatigueBigDropSec = prefs.getInt("fatigueBigDropSec", d.fatigueBigDropSec),
            fatigueBigDropWeight = prefs.getFloat("fatigueBigDropWeight", d.fatigueBigDropWeight),
            sttEngine = enumOr(prefs.getString("sttEngine", null), d.sttEngine),
            modelFileName = prefs.getString("modelFileName", null) ?: d.modelFileName,
            sttThreads = prefs.getInt("sttThreads", d.sttThreads),
            fastMode = prefs.getBoolean("fastMode", d.fastMode),
            livePartials = prefs.getBoolean("livePartials", d.livePartials),
            micSensitivity = prefs.getInt("micSensitivity", d.micSensitivity),
            endPauseMs = prefs.getInt("endPauseMs", d.endPauseMs),
            readbackDelayMs = prefs.getInt("readbackDelayMs", d.readbackDelayMs),
            noiseFilter = prefs.getInt("noiseFilter", d.noiseFilter),
            noiseFilterOn = prefs.getBoolean("noiseFilterOn", d.noiseFilterOn),
            noiseAdaptMs = prefs.getInt("noiseAdaptMs", d.noiseAdaptMs),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("weightUnit", s.weightUnit.name)
            .putString("effortMode", s.effortMode.name)
            .putInt("warmupSets", s.warmupSets)
            .putInt("workingSets", s.workingSets)
            .putBoolean("rirEnabled", s.rirEnabled)
            .putBoolean("rirShowNumber", s.rirShowNumber)
            .putInt("rirHigh", s.rirHigh)
            .putInt("rirModerate", s.rirModerate)
            .putInt("rirLow", s.rirLow)
            .putFloat("weightUpThreshold", s.weightUpThreshold)
            .putFloat("weightDownThreshold", s.weightDownThreshold)
            .putInt("repsUpThreshold", s.repsUpThreshold)
            .putInt("repsDownThreshold", s.repsDownThreshold)
            .putInt("tutUpThresholdSec", s.tutUpThresholdSec)
            .putInt("tutDownThresholdSec", s.tutDownThresholdSec)
            .putString("feedback", s.feedback.name)
            .putString("feedbackSound", s.feedbackSound.name)
            .putString("startSound", s.startSound.name)
            .putString("stopSound", s.stopSound.name)
            .putString("doneSound", s.doneSound.name)
            .putString("voiceName", s.voiceName)
            .putFloat("voicePitch", s.voicePitch)
            .putFloat("speechRate", s.speechRate)
            .putInt("transcriptSeconds", s.transcriptSeconds)
            .putString("warmupColor", s.warmupColor.name)
            .putString("workingColor", s.workingColor.name)
            .putInt("backHoldMs", s.backHoldMs)
            .putInt("saveHoldMs", s.saveHoldMs)
            .putInt("logoHoldMs", s.logoHoldMs)
            .putInt("resumeHoldMs", s.resumeHoldMs)
            .putInt("deleteBlockHoldMs", s.deleteBlockHoldMs)
            .putFloat("skillCapacityHours", s.skillCapacityHours)
            .putInt("addSkillHoldMs", s.addSkillHoldMs)
            .putInt("deleteRecordingHoldMs", s.deleteRecordingHoldMs)
            .putInt("renameExerciseHoldMs", s.renameExerciseHoldMs)
            .putInt("pickHoldMs", s.pickHoldMs)
            .putString("curveUnit", s.curveUnit.name)
            .putBoolean("rapidInput", s.rapidInput)
            .putInt("startWordCount", s.startWordCount)
            .putInt("stopWordCount", s.stopWordCount)
            .putString("wordAliases", aliasesToJson(s.wordAliases))
            .putInt("wordAliasTextSp", s.wordAliasTextSp)
            .putString("commandWords", wordsToJson(s.commandWords))
            .putBoolean("customData", s.customData)
            .putInt("tutGoalSec", s.tutGoalSec)
            .putFloat("loadStep", s.loadStep)
            .putString("progressionRules", rulesToJson(s.progressionRules))
            .putStringSet("allSetsExercises", s.allSetsExercises)
            .putInt("setSameTutSec", s.setSameTutSec)
            .putInt("setSameReps", s.setSameReps)
            .putInt("fatigueSameSec", s.fatigueSameSec)
            .putInt("fatigueBigDropSec", s.fatigueBigDropSec)
            .putFloat("fatigueBigDropWeight", s.fatigueBigDropWeight)
            .putString("sttEngine", s.sttEngine.name)
            .putString("modelFileName", s.modelFileName)
            .putInt("sttThreads", s.sttThreads)
            .putBoolean("fastMode", s.fastMode)
            .putBoolean("livePartials", s.livePartials)
            .putInt("micSensitivity", s.micSensitivity)
            .putInt("endPauseMs", s.endPauseMs)
            .putInt("readbackDelayMs", s.readbackDelayMs)
            .putInt("noiseFilter", s.noiseFilter)
            .putBoolean("noiseFilterOn", s.noiseFilterOn)
            .putInt("noiseAdaptMs", s.noiseAdaptMs)
            .commit()
        _settings.value = s
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}

private fun aliasesToJson(list: List<WordAlias>): String = JSONArray().apply {
    list.forEach { put(JSONObject().put("from", it.from).put("to", it.to)) }
}.toString()

private fun aliasesFromJson(json: String): List<WordAlias>? = runCatching {
    val arr = JSONArray(json)
    List(arr.length()) { i -> arr.getJSONObject(i).let { WordAlias(it.optString("from"), it.optString("to")) } }
        .ifEmpty { listOf(WordAlias()) }
}.getOrNull()

/**
 * How one exercise progresses: time under tension climbs until it reaches [tutGoalSec], then
 * the weight goes up by [loadStep].
 *
 * The trade between weight and time: "if I can do a weight for [tradeFromSec], I can do
 * [tradeWeight] more for [tradeToSec]" (e.g. 6:00, then +1 lb for 2:00). Null: learned from the
 * workouts in the database.
 */
data class ProgressionRule(
    val name: String = "",
    val tutGoalSec: Int = 360,
    val loadStep: Float = 1f,
    val tradeFromSec: Int? = null,
    val tradeWeight: Float? = null,
    val tradeToSec: Int? = null,
) {
    val hasTrade: Boolean get() = tradeFromSec != null && tradeWeight != null && tradeToSec != null &&
        tradeWeight > 0 && tradeFromSec > tradeToSec

    /** Seconds of time under tension one unit of weight (1 lb or 1 kg) is worth, from the trade. */
    val secondsPerWeight: Double? get() = if (hasTrade) (tradeFromSec!! - tradeToSec!!) / tradeWeight!!.toDouble() else null
}

private fun rulesToJson(list: List<ProgressionRule>): String = JSONArray().apply {
    list.forEach {
        put(
            JSONObject().put("name", it.name).put("tutGoalSec", it.tutGoalSec).put("loadStep", it.loadStep.toDouble())
                .put("tradeFromSec", it.tradeFromSec ?: JSONObject.NULL)
                .put("tradeWeight", it.tradeWeight?.toDouble() ?: JSONObject.NULL)
                .put("tradeToSec", it.tradeToSec ?: JSONObject.NULL)
        )
    }
}.toString()

private fun rulesFromJson(json: String): List<ProgressionRule>? = runCatching {
    val arr = JSONArray(json)
    List(arr.length()) { i ->
        arr.getJSONObject(i).let {
            ProgressionRule(
                it.optString("name"), it.optInt("tutGoalSec", 360), it.optDouble("loadStep", 1.0).toFloat(),
                tradeFromSec = if (it.isNull("tradeFromSec")) null else it.optInt("tradeFromSec"),
                tradeWeight = if (it.isNull("tradeWeight")) null else it.optDouble("tradeWeight").toFloat(),
                tradeToSec = if (it.isNull("tradeToSec")) null else it.optInt("tradeToSec"),
            )
        }
    }
}.getOrNull()

private fun wordsToJson(words: Map<String, String>): String = JSONObject().apply {
    words.forEach { (k, v) -> put(k, v) }
}.toString()

private fun wordsFromJson(json: String): Map<String, String>? = runCatching {
    val obj = JSONObject(json)
    obj.keys().asSequence().associateWith { obj.optString(it) }
}.getOrNull()
