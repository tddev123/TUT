package com.example.tut.record

import android.os.Build
import com.example.tut.TutApp
import com.example.tut.data.CompletedSet
import com.example.tut.data.ExerciseNames
import com.example.tut.data.FeedbackMode
import com.example.tut.data.Repository
import com.example.tut.data.SttEngine
import com.example.tut.data.WordAliases
import com.example.tut.data.WorkSet
import com.example.tut.parse.Action
import com.example.tut.parse.CommandParser
import com.example.tut.stt.GoogleRecognizer
import com.example.tut.stt.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.tut.audio.NoteWriter
import java.io.File

/** A sentence that is nothing but "done recording" (plus filler) can be cut from the note entirely. */
private val endOnly = Regex("""^(?:(?:ok|okay|and|so|um|uh|alright|all right) )*(?:done|stop|end|finish|finished) (?:the )?(?:voice )?(?:recording|note)$""")

sealed interface ModelStatus {
    data object Loading : ModelStatus
    data class Ready(val name: String) : ModelStatus
    data object Missing : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

data class TranscriptLine(val text: String, val at: Long)

/**
 * Drives the New Data recording page: microphone -> speech-to-text -> action words ->
 * database. Every recognised action is written to the database immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionController(
    private val app: TutApp,
    val sessionId: Long,
    private val scope: CoroutineScope,
) {
    private val repo: Repository = app.repo
    private val settings get() = app.settings.current
    private val io = Dispatchers.IO.limitedParallelism(1) // actions are applied strictly in order

    val engine = SpeechEngine()

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Loading)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    /** Start/Stop: whether action words are being acted on. Dictation continues either way. */
    private val _listening = MutableStateFlow(true)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** False after Save until Continue is pressed. */
    private val _recording = MutableStateFlow(true)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TranscriptLine>> = _transcript.asStateFlow()

    private val _bubbles = MutableStateFlow<List<CompletedSet>>(emptyList())
    val bubbles: StateFlow<List<CompletedSet>> = _bubbles.asStateFlow()

    /** Unsaved data exists: the back button needs Save first. */
    private val _unsaved = MutableStateFlow(false)
    val unsaved: StateFlow<Boolean> = _unsaved.asStateFlow()

    /** The set the next numbers will be written to, e.g. "Working set 2". */
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()

    /** The session's single exercise, once named. */
    private val _exerciseName = MutableStateFlow<String?>(null)
    val exerciseName: StateFlow<String?> = _exerciseName.asStateFlow()

    /** True while a voice note is being recorded ("add voice note" ... "done recording"). */
    private val _noteRecording = MutableStateFlow(false)
    val noteRecording: StateFlow<Boolean> = _noteRecording.asStateFlow()

    private var noteWriter: NoteWriter? = null
    /** The recording "add voice note" continues; "new recording" starts another one. */
    private var currentNotePath: String? = null

    /** True while "save data to the database?" is on screen (opened by saying "save"). */
    private val _savePrompt = MutableStateFlow(false)
    val savePrompt: StateFlow<Boolean> = _savePrompt.asStateFlow()

    /** Fires after the session is saved, whether by button, dialog or voice. */
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private var currentExerciseId: Long? = null
    private var currentSetId: Long? = null
    /** Values spoken before any set was named; applied to the next set. */
    private var pending = WorkSetValues()

    private var unmuteJob: Job? = null
    private val speaker = Speaker(app) { busy ->
        if (busy) {
            unmuteJob?.cancel()
            engine.muted = true
        } else {
            unmuteJob?.cancel()
            unmuteJob = scope.launch { delay(350); engine.muted = false }
        }
    }

    /** Since when the timer has been running (null = stopped by Save), and the time counted before that. */
    @Volatile private var runningSince: Long? = System.currentTimeMillis()
    private val _earlierMs = MutableStateFlow<Long?>(null)

    /**
     * The recording page's timer. It runs from the moment the page opens; Save stops it at the
     * time it shows, and it carries on from there when recording continues (Continue, or Resume
     * recording in the database). Null until the stored time has loaded.
     */
    fun elapsedMs(now: Long): Long? = _earlierMs.value?.let { it + (runningSince?.let { since -> now - since } ?: 0L) }

    /** Save: freezes the timer at what it shows now. */
    private fun stopTimer() {
        val now = System.currentTimeMillis()
        val ms = elapsedMs(now) ?: return
        runningSince = null
        _earlierMs.value = ms
    }

    private fun continueTimer() {
        if (runningSince == null) runningSince = System.currentTimeMillis()
    }

    private fun storeElapsed(notify: Boolean) {
        val ms = elapsedMs(System.currentTimeMillis()) ?: return
        repo.setSessionRecordedMs(sessionId, ms, notify)
    }

    init {
        scope.launch(io) {
            val session = repo.session(sessionId)
            // Older sessions have no stored time: start from the time between their first and last sets.
            _earlierMs.value = session?.recordedMs ?: repo.exercises(sessionId).firstOrNull()?.totalTimeMs ?: 0L
            // Kept up to date while recording, so the time survives the app being closed.
            while (true) {
                delay(5_000)
                storeElapsed(notify = false)
            }
        }
        scope.launch { engine.finals.collect { handleText(it.text, it.startSample) } }
        scope.launch { engine.problem.collect { it?.let { _modelStatus.value = ModelStatus.Failed(it) } } }
        scope.launch { refreshBubbles() }
        // Resuming an unsaved session: show the exercise it already has.
        scope.launch(io) {
            repo.exercises(sessionId).firstOrNull()?.let { ex ->
                currentExerciseId = ex.id
                _exerciseName.value = ex.name.ifBlank { null }
            }
        }
    }

    /** Loads the model (first time only takes a few seconds) and starts listening. */
    fun begin() {
        scope.launch {
            _modelStatus.value = ModelStatus.Loading
            if (settings.sttEngine == SttEngine.GOOGLE && Build.VERSION.SDK_INT >= 33) {
                _modelStatus.value = ModelStatus.Ready(GoogleRecognizer.name(app))
                if (_recording.value && !engine.running.value && !engine.startGoogle(app, settings.micSensitivity, settings.livePartials)) {
                    _modelStatus.value = ModelStatus.Failed("microphone unavailable")
                }
                return@launch
            }
            val stt = try {
                app.models.get(settings)
            } catch (e: Throwable) {
                _modelStatus.value = ModelStatus.Failed(e.message ?: "could not load model"); return@launch
            }
            if (stt == null) {
                _modelStatus.value = if (app.models.availableModels().isEmpty()) ModelStatus.Missing
                else ModelStatus.Failed("could not load model")
                return@launch
            }
            _modelStatus.value = ModelStatus.Ready(stt.name)
            if (_recording.value && !engine.running.value) {
                if (!engine.start(
                        stt, settings.micSensitivity, settings.livePartials, settings.endPauseMs,
                        settings.noiseFilter.takeIf { settings.noiseFilterOn }, settings.noiseAdaptMs,
                        settings.rapidInput,
                    )
                ) {
                    _modelStatus.value = ModelStatus.Failed("microphone unavailable")
                }
            }
        }
    }

    /** Start / Stop buttons. */
    fun setListening(on: Boolean) {
        _listening.value = on
        startStopSound(on)
        if (on && !_recording.value) resume()
    }

    /** Saves the session (Save button, or "yes" to the save prompt). Stops recording; data is already stored. */
    fun confirmSave() {
        _savePrompt.value = false
        finishNote(null)
        engine.stop()
        stopTimer()
        _recording.value = false
        _unsaved.value = false
        scope.launch(io) {
            storeElapsed(notify = true)
            repo.setSessionSaved(sessionId, true)
        }
        _saved.tryEmit(Unit)
    }

    /** Yes/No on the save prompt, tapped or spoken. */
    fun answerSavePrompt(yes: Boolean) {
        if (yes) confirmSave() else _savePrompt.value = false
    }

    /** "Continue" after a save: keeps adding to the same session. */
    fun resume() {
        continueTimer()
        _recording.value = true
        _listening.value = true
        begin()
    }

    fun editSet(set: WorkSet) {
        scope.launch(io) {
            app.ops.saveSet(set)
            markChanged()
            refreshBubbles()
        }
    }

    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        // Leaving the page, saved or not: keep the final time. The page's scope is going away.
        app.appScope.launch(io) { storeElapsed(notify = true) }
        finishNote(null)
        engine.stop()
        speaker.shutdown()
    }

    // ------------------------------------------------------------------ speech handling

    /**
     * Handles one finished piece of transcript (also used by tests). [startSample] is where
     * the speech began, used to cut a spoken "done recording" off the end of a voice note.
     */
    suspend fun handleText(heard: String, startSample: Long = -1) {
        val now = System.currentTimeMillis()
        // Activation words from settings: "poles" is taken as "pull-ups", and so on.
        val text = WordAliases.apply(heard, settings.wordAliases)
        _transcript.value = (_transcript.value + TranscriptLine(text, now)).takeLast(40)
        val (parsed, run) = CommandParser.parseWithRuns(text, wordRun, settings.commandWords)
        wordRun = run
        val actions = parsed.map { it.first }
        if (noteWriter != null) {
            // While a voice note records, everything said belongs to the note; only
            // "done recording" is acted on.
            if (actions.contains(Action.EndNote)) {
                val onlyCommand = endOnly.matches(CommandParser.normalize(text, settings.commandWords))
                finishNote(if (onlyCommand && startSample >= 0) startSample else null)
                beep()
                say(Action.EndNote.spoken)
            }
            return
        }
        if (_savePrompt.value) {
            // The save prompt is open: only "yes"/"confirm" or "no"/"abort"/"never mind" count.
            val yes = CommandParser.answer(text, settings.commandWords) ?: return
            beep()
            say(if (yes) "saved" else "cancelled")
            answerSavePrompt(yes)
            return
        }
        var actedOn = false
        var setFinished = false
        var setWords = false
        var readback: String? = null
        for ((action, inARow) in parsed) {
            if (!_listening.value && action != Action.Start) continue
            if (!repeatedEnough(action, inARow)) continue
            val spoken = withContext(io) { apply(action, now) } ?: continue
            if ((action == Action.Start || action == Action.Stop) && startStopSound(action == Action.Start)) continue
            actedOn = true
            if (action == Action.Done) setFinished = true
            // Reading back every value mutes the mic while it talks, which would cut off the
            // rest of a set said in one breath; rapid input only speaks the "done" summary,
            // the exercise name (said back as soon as it's heard) and other commands.
            // The finished set is read back after the "pause before reading back" setting.
            if (action == Action.Done && settings.readbackDelayMs > 0) readback = spoken
            else if (!(settings.rapidInput && action.isSetValue && action !is Action.Exercise)) say(spoken)
            if (noteWriter != null) break // the rest of this sentence came before the note started
            if (action.isSetValue && action !is Action.Exercise || action == Action.Done) setWords = true
        }
        // Sets are being given but nobody said which exercise they belong to. Rapid input only
        // reminds on "done", so the voice doesn't cut off the rest of a set said in one breath.
        val remind = setWords && _exerciseName.value == null && (!settings.rapidInput || setFinished)
        val delayed = readback
        if (delayed != null) {
            val wait = settings.readbackDelayMs.toLong()
            scope.launch {
                delay(wait)
                say(delayed)
                if (remind) say("exercise not selected")
            }
        } else if (remind) {
            say("exercise not selected")
        }
        // One sound per phrase. Finishing a set (its bubble appears) has its own sound,
        // which replaces the action-word sound for that phrase.
        if (setFinished) doneSound() else if (actedOn) beep()
    }

    /** "start" / "stop" said so far in a row, carried from one piece of speech to the next. */
    private var wordRun = CommandParser.WordRun()

    /**
     * Start and Stop only happen once said as many times in a row as settings ask for
     * ("start start start"), with no other word at all in between.
     */
    private fun repeatedEnough(action: Action, inARow: Int): Boolean = when (action) {
        Action.Start -> inARow >= settings.startWordCount
        Action.Stop -> inARow >= settings.stopWordCount
        else -> true
    }

    private fun say(text: String) {
        val s = settings
        if (!s.feedback.voice) return
        speaker.setVoice(s.voiceName, s.voicePitch)
        speaker.speak(text, s.speechRate)
    }

    private fun beep() {
        if (settings.feedback.sound) speaker.beep(settings.feedbackSound)
    }

    /** "done" finished a set (warm-up or working) and its bubble appeared. */
    private fun doneSound() {
        if (settings.feedback.sound) speaker.beep(settings.doneSound)
    }

    /**
     * When the response is sound only (no voice), Start and Stop, by button or by voice,
     * play their own sounds instead of the action-word sound. Returns true if it did.
     */
    private fun startStopSound(start: Boolean): Boolean {
        val s = settings
        if (s.feedback != FeedbackMode.SOUND) return false
        speaker.beep(if (start) s.startSound else s.stopSound)
        return true
    }

    // ------------------------------------------------------------------ voice notes

    private fun newNotePath(now: Long): String {
        val file = File(app.notesDir, "session_${sessionId}_$now.wav")
        repo.addSessionNote(sessionId, file.absolutePath, now)
        currentNotePath = file.absolutePath
        return file.absolutePath
    }

    private fun startNote(path: String) {
        val writer = NoteWriter(File(path))
        noteWriter = writer
        engine.tap = { pcm, n, start -> writer.write(pcm, n, start) }
        _noteRecording.value = true
        markChanged()
    }

    private fun finishNote(cutAt: Long?) {
        val writer = noteWriter ?: return
        engine.tap = null
        noteWriter = null
        writer.finish(cutAt)
        _noteRecording.value = false
        repo.notifyNotesChanged()
    }

    /** Applies one action. Returns the phrase to say back, or null if nothing happened. */
    private suspend fun apply(action: Action, now: Long): String? {
        when (action) {
            Action.Start -> { _listening.value = true; return action.spoken }
            Action.Stop -> { _listening.value = false; return action.spoken }
            Action.Save -> {
                if (!_recording.value) return null
                _savePrompt.value = true
                return "save? say yes or no"
            }
            is Action.Exercise -> return nameExercise(action.name, now)
            Action.AddNote -> {
                startNote(currentNotePath ?: newNotePath(now))
                return action.spoken
            }
            Action.NewNote -> {
                startNote(newNotePath(now))
                return action.spoken
            }
            Action.EndNote -> return null // only meaningful while a note records
            is Action.Sleep -> {
                // Belongs to the big data block as a whole, never to a set.
                repo.setSessionSleepHours(sessionId, action.hours)
                markChanged()
                return action.spoken
            }
            is Action.Recovery -> {
                // Belongs to the big data block as a whole; only for reading, never a prediction input.
                repo.setSessionRecovery(sessionId, action.feeling)
                markChanged()
                return action.spoken
            }
            Action.Done -> {
                // "done" closes the current set: it becomes its own bubble.
                val set = currentSetId?.let { repo.set(it) } ?: return null
                app.ops.saveSet(set.copy(completedAt = set.completedAt ?: now), now)
                currentSetId = null
                afterChange()
                val missing = set.missingFields(settings)
                // Rapid input doesn't read back each value as it's said, so read the whole set
                // back here to let the lifter check it was heard right.
                val values = if (settings.rapidInput) readBack(set) else ""
                val head = "${set.kind.spoken} ${set.number} done" + if (values.isEmpty()) "" else ", $values"
                return if (missing.isEmpty()) head else "$head, missing ${missing.joinToString(" and ")}"
            }
            is Action.CopySet -> {
                // Copies another set's values into the current set; what's said next edits the copy.
                val set = currentSetId?.let { repo.set(it) } ?: return "say which set to copy into first"
                val kind = action.kind ?: set.kind
                val label = "${kind.spoken} ${action.number}"
                if (kind == set.kind && action.number == set.number) return "that's the same set"
                val from = repo.sets(set.exerciseId).firstOrNull { it.kind == kind && it.number == action.number }
                if (from == null || (!from.hasData && from.endRangeSec == null && from.topRestSec == null)) return "$label is empty"
                app.ops.saveSet(set.copyValuesFrom(from), now)
                afterChange()
                return "copied $label"
            }
            is Action.SelectSet -> {
                val exId = ensureExercise(now)
                var set = repo.getOrCreateSet(exId, action.kind, action.number)
                currentSetId = set.id
                if (!pending.isEmpty()) {
                    set = pending.applyTo(set)
                    pending = WorkSetValues()
                    app.ops.saveSet(set, now)
                } else {
                    repo.touchExercise(exId, now)
                }
                afterChange()
                return action.spoken
            }
            else -> {
                val setId = currentSetId
                val set = setId?.let { repo.set(it) }
                if (set == null) {
                    pending = pending.with(action, settings.weightUnit, app.ops)
                } else {
                    app.ops.saveSet(WorkSetValues().with(action, settings.weightUnit, app.ops).applyTo(set), now)
                    afterChange()
                }
                return action.spoken
            }
        }
    }

    /** Every value the set holds, worded as when each is said back on its own. */
    private fun readBack(set: WorkSet): String = listOfNotNull(
        set.restSec?.let { Action.Rest(it).spoken },
        set.weight?.let { Action.Weight(it, set.unit ?: settings.weightUnit).spoken },
        set.reps?.let { Action.Reps(it).spoken },
        set.tutSec?.let { Action.Tut(it).spoken },
        if (set.rirLevel != null || set.rirValue != null) Action.Rir(set.rirLevel, set.rirValue).spoken else null,
        set.endRangeSec?.let { Action.EndRange(it).spoken },
    ).joinToString(", ")

    /**
     * One recording session holds exactly one exercise. Saying "exercise …" names it; saying
     * it again renames the same exercise (all its sets stay with it).
     */
    private suspend fun nameExercise(rawName: String, now: Long): String {
        val name = (WordAliases.nameFor(rawName, settings.wordAliases) ?: ExerciseNames.canonical(rawName))
            .replaceFirstChar { it.uppercase() }
        val id = ensureExercise(now)
        repo.renameExercise(id, name)
        app.ops.refreshColors(id)
        afterChange()
        return "exercise $name"
    }

    /** Renames the session's exercise by hand (tapping its name on the recording page). */
    fun renameExercise(name: String) {
        if (name.isBlank()) return
        scope.launch(io) { nameExercise(name, System.currentTimeMillis()) }
    }

    private fun ensureExercise(now: Long): Long {
        currentExerciseId?.let { return it }
        val ex = repo.exercises(sessionId).firstOrNull() ?: repo.createExercise(sessionId, "", now)
        currentExerciseId = ex.id
        return ex.id
    }

    private suspend fun afterChange() {
        markChanged()
        refreshBubbles()
        val ex = currentExerciseId?.let { repo.exercise(it) }
        _exerciseName.value = ex?.name?.ifBlank { null }
        _target.value = currentSetId?.let { repo.set(it) }?.label
    }

    private fun markChanged() {
        if (!_unsaved.value) {
            _unsaved.value = true
            repo.setSessionSaved(sessionId, false)
        }
    }

    private suspend fun refreshBubbles() {
        _bubbles.value = withContext(io) { repo.completedSets(sessionId) }
    }
}
