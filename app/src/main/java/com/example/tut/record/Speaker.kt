package com.example.tut.record

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.tut.data.FeedbackSound
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** An installed text-to-speech voice the user can pick. */
data class VoiceOption(val name: String, val label: String)

/**
 * Reads recognised action words back through the speaker using the phone's offline
 * text-to-speech voice, or plays a short sound instead. [onBusyChanged] lets the
 * microphone ignore the phone's own voice.
 */
class Speaker(context: Context, private val onBusyChanged: (Boolean) -> Unit = {}) {
    @Volatile private var ready = false
    private val pending = AtomicInteger(0)
    private var counter = 0
    private var voiceName: String? = null
    private var pitch = 1f

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    /** English voices installed on the phone that work offline; filled once TTS is ready. */
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private val tones: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    } catch (_: RuntimeException) {
        null // no audio output available
    }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            ready = true
            _voices.value = listVoices()
            applyVoice()
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onBusyChanged(true)
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
    }

    private fun listVoices(): List<VoiceOption> {
        val voices = try {
            tts.voices.orEmpty()
                .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                .sortedWith(compareBy({ it.locale.displayCountry }, { it.name }))
        } catch (_: Exception) {
            emptyList()
        }
        // Names look like "en-us-x-iol-local"; show "United States · iol".
        return voices.map { v ->
            val code = Regex("""-x-([a-z0-9]+)""").find(v.name)?.groupValues?.get(1) ?: v.name
            val country = v.locale.displayCountry.ifEmpty { v.locale.displayLanguage }
            VoiceOption(v.name, "$country · $code")
        }
    }

    /** [name] null = the phone's default voice. [pitch] 1.0 is normal. */
    fun setVoice(name: String?, pitch: Float) {
        voiceName = name
        this.pitch = pitch
        if (ready) applyVoice()
    }

    private fun applyVoice() {
        tts.setPitch(pitch)
        val voice = voiceName?.let { name -> tts.voices.orEmpty().firstOrNull { it.name == name } } ?: tts.defaultVoice
        if (voice != null && voice != tts.voice) tts.voice = voice
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            onBusyChanged(false)
        }
    }

    fun speak(text: String, rate: Float) {
        if (!ready || text.isBlank()) return
        pending.incrementAndGet()
        onBusyChanged(true)
        tts.setSpeechRate(rate)
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, "tut-${counter++}") != TextToSpeech.SUCCESS) finished()
    }

    /**
     * Plays a short sound. The microphone is not muted for it: a beep is too short to cut
     * off much, and whisper ignores it, while muting would drop words said at the same time.
     */
    fun beep(sound: FeedbackSound) {
        tones?.startTone(sound.tone, sound.durationMs)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        tones?.release()
        onBusyChanged(false)
    }
}
