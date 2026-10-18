package com.example.tut.record

import android.os.Build
import com.example.tut.data.WordAliases
import com.example.tut.TutApp
import com.example.tut.data.Repository
import com.example.tut.data.SttEngine
import com.example.tut.data.Skill
import com.example.tut.parse.SkillParser
import com.example.tut.stt.GoogleRecognizer
import com.example.tut.stt.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hours logged on this visit to the Skills recording page (a bubble each). */
data class LoggedHours(val logId: Long, val skillName: String, val hours: Double)

/**
 * Drives the Skills recording page: microphone -> speech-to-text -> "<skill> <time>" ->
 * Skills database. Each log is written as soon as it is heard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillController(private val app: TutApp, private val scope: CoroutineScope) {
    private val repo: Repository = app.repo
    private val settings get() = app.settings.current
    private val io = Dispatchers.IO.limitedParallelism(1)

    val engine = SpeechEngine()

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Loading)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private val _listening = MutableStateFlow(true)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TranscriptLine>> = _transcript.asStateFlow()

    private val _logged = MutableStateFlow<List<LoggedHours>>(emptyList())
    val logged: StateFlow<List<LoggedHours>> = _logged.asStateFlow()

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    private var unmuteJob: Job? = null
    private val speaker = Speaker(app) { busy ->
        unmuteJob?.cancel()
        if (busy) engine.muted = true
        else unmuteJob = scope.launch { delay(350); engine.muted = false }
    }

    init {
        scope.launch { engine.finals.collect { handleText(it.text) } }
        scope.launch { engine.problem.collect { it?.let { _modelStatus.value = ModelStatus.Failed(it) } } }
        scope.launch { _skills.value = withContext(io) { repo.skills() } }
    }

    fun begin() {
        scope.launch {
            _modelStatus.value = ModelStatus.Loading
            if (settings.sttEngine == SttEngine.GOOGLE && Build.VERSION.SDK_INT >= 33) {
                _modelStatus.value = ModelStatus.Ready(GoogleRecognizer.name(app))
                if (!engine.running.value && !engine.startGoogle(app, settings.micSensitivity, settings.livePartials)) {
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
            if (!engine.running.value) {
                val s = settings
                if (!engine.start(stt, s.micSensitivity, s.livePartials, s.endPauseMs,
                        s.noiseFilter.takeIf { s.noiseFilterOn }, s.noiseAdaptMs, s.rapidInput,
                    )) {
                    _modelStatus.value = ModelStatus.Failed("microphone unavailable")
                }
            }
        }
    }

    /** Start / Stop buttons: whether skill phrases are acted on. */
    fun setListening(on: Boolean) {
        _listening.value = on
        if (settings.feedback.sound) speaker.beep(if (on) settings.startSound else settings.stopSound)
    }

    /** Takes a log back out (tapping its bubble), e.g. when a time was misheard. */
    fun remove(item: LoggedHours) {
        _logged.value = _logged.value - item
        scope.launch(io) { repo.deleteSkillLog(item.logId) }
    }

    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        engine.stop()
        speaker.shutdown()
    }

    suspend fun handleText(heard: String) {
        val now = System.currentTimeMillis()
        val text = WordAliases.apply(heard, settings.wordAliases)
        _transcript.value = (_transcript.value + TranscriptLine(text, now)).takeLast(40)
        if (!_listening.value) return
        val skills = withContext(io) { repo.skills() }
        _skills.value = skills
        val result = SkillParser.parse(text, skills)
        if (result.logs.isEmpty()) {
            if (result.unmatchedTime) {
                beep()
                say(if (skills.isEmpty()) "add a skill first" else "which skill?")
            }
            return
        }
        for (log in result.logs) {
            val id = withContext(io) { repo.addSkillLog(log.skill.id, log.hours, now) }
            _logged.value = _logged.value + LoggedHours(id, log.skill.name, log.hours)
        }
        if (settings.feedback.sound) speaker.beep(settings.doneSound)
        say(result.logs.joinToString(", ") { "${it.skill.name} ${SkillParser.speakHours(it.hours)}" })
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
}
