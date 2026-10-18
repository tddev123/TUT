package com.example.tut.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * The phone's own speech recognizer (Google's on-device one where the phone has it), fed with
 * our microphone audio through a pipe so the waveform, muting and voice notes keep working.
 * Unlike whisper it transcribes while you talk, so the text is ready as soon as you stop.
 *
 * Needs Android 13+ (feeding it audio). One "segmented session" runs for as long as audio
 * keeps coming, with a result per phrase; recognizers that don't support that stop after one
 * phrase and are simply started again.
 */
@RequiresApi(33)
class GoogleRecognizer(
    private val context: Context,
    private val livePartials: Boolean,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val frames = LinkedBlockingDeque<ByteArray>()
    @Volatile private var active = false
    @Volatile private var out: OutputStream? = null
    private var readEnd: ParcelFileDescriptor? = null
    private var recognizer: SpeechRecognizer? = null
    private var feeder: Thread? = null
    /** Errors in a row without hearing anything; too many and we give up. */
    private var failures = 0

    fun start() {
        active = true
        feeder = Thread(::feed, "tut-google-feed").apply { start() }
        main.post {
            recognizer = create()
            startSession()
        }
    }

    /** Queues 16 kHz mono audio for the recognizer. Called from the microphone thread. */
    fun write(pcm: ShortArray, n: Int) {
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            bytes[2 * i] = pcm[i].toInt().toByte()
            bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        frames.addLast(bytes)
        // While a session is restarting nothing is read; keep the last few seconds only.
        while (frames.size > MAX_QUEUED_FRAMES) frames.pollFirst()
    }

    fun stop() {
        active = false
        feeder?.interrupt()
        feeder = null
        main.post {
            closeSession()
            recognizer?.destroy()
            recognizer = null
            frames.clear()
        }
    }

    private fun create(): SpeechRecognizer =
        (if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)).apply { setRecognitionListener(listener) }

    private fun intent(source: ParcelFileDescriptor) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, livePartials)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SpeechEngine.RATE)
        // Keep going phrase after phrase until the audio is closed.
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(GYM_WORDS))
    }

    /** Main thread. */
    private fun startSession() {
        val r = recognizer ?: return
        if (!active) return
        val pipe = ParcelFileDescriptor.createPipe()
        readEnd = pipe[0]
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        r.startListening(intent(pipe[0]))
    }

    /** Main thread. We own both ends of the pipe, so we close them. */
    private fun closeSession() {
        val o = out
        out = null
        try { o?.close() } catch (_: IOException) {}
        try { readEnd?.close() } catch (_: IOException) {}
        readEnd = null
    }

    /** Main thread. */
    private fun restart(afterMs: Long) {
        closeSession()
        if (!active) return
        main.postDelayed({ if (active && out == null) startSession() }, afterMs)
    }

    /** Copies queued audio into the current session's pipe; waits while there is none. */
    private fun feed() {
        while (active) {
            val frame = try { frames.pollFirst(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
            val o = out
            if (o == null) {
                frames.addFirst(frame)
                try { Thread.sleep(20) } catch (_: InterruptedException) {}
                continue
            }
            try {
                o.write(frame)
            } catch (_: IOException) {
                // The session ended while we were writing; it goes to the next one instead.
                frames.addFirst(frame)
                try { Thread.sleep(20) } catch (_: InterruptedException) {}
            }
        }
    }

    /** The best guess in a result, or "" (also once stopped, so late results are dropped). */
    private fun text(b: Bundle?): String =
        if (!active) "" else b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (livePartials) onPartial(text(partialResults))
        }

        // One phrase of a segmented session.
        override fun onSegmentResults(segmentResults: Bundle) {
            failures = 0
            onPartial("")
            text(segmentResults).takeIf { it.isNotEmpty() }?.let(onFinal)
        }

        override fun onEndOfSegmentedSession() {
            restart(0)
        }

        // Recognizers without segmented sessions end here after one phrase.
        override fun onResults(results: Bundle?) {
            failures = 0
            onPartial("")
            text(results).takeIf { it.isNotEmpty() }?.let(onFinal)
            restart(0)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "recognizer error $error")
            onPartial("")
            when (error) {
                // Heard nothing usable: just listen again.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(0)
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    // The offline English pack isn't on the phone yet: ask for it.
                    try {
                        recognizer?.triggerModelDownload(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "could not ask for the language pack", e)
                    }
                    fail("downloading Google's offline English pack; reopen this page in a minute")
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> fail("this phone's recognizer has no offline English")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("no permission to use the phone's recognizer")
                else -> {
                    if (++failures >= MAX_FAILURES) return fail("the phone's recognizer keeps failing (error $error)")
                    // A busy recognizer needs replacing, not just restarting.
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                        closeSession()
                        recognizer?.destroy()
                        recognizer = create()
                    }
                    restart(300L * failures)
                }
            }
        }
    }

    private fun fail(message: String) {
        closeSession()
        onProblem(message)
    }

    companion object {
        /** What will be listening: shown under the waveform. */
        fun name(context: Context): String =
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) "Google on-device speech"
            else "Phone speech service"

        private const val TAG = "GoogleRecognizer"
        private const val LANGUAGE = "en-US"
        private const val MAX_QUEUED_FRAMES = 60 // 3 s of 50 ms frames
        private const val MAX_FAILURES = 5

        /** Words to favour, the same vocabulary whisper gets as its prompt. */
        val GYM_WORDS = listOf(
            "warm-up set", "working set", "reps", "RIR", "pounds", "kilograms", "rest", "minutes",
            "time under tension", "TUT", "done", "add voice note", "done recording", "new recording",
            "start", "stop", "save", "never mind", "exercise",
        )
    }
}
