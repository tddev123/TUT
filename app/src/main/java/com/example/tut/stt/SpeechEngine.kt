package com.example.tut.stt

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Continuous offline dictation. Whisper is not a streaming model, so the microphone is
 * split into utterances with a simple energy-based voice activity detector:
 *  - while someone is talking, the utterance so far is re-transcribed now and then
 *    for the live (partial) text;
 *  - when they pause, the whole utterance is transcribed once more and emitted as final
 *    text. Only final text is used for action words.
 */
/** A finished piece of speech and where it started (in samples since the microphone started). */
data class Utterance(val text: String, val startSample: Long)

class SpeechEngine {
    private val _level = MutableStateFlow(0f)
    /** Microphone loudness, roughly 0..1, for the waveform. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _finals = MutableSharedFlow<Utterance>(extraBufferCapacity = 64)
    val finals: SharedFlow<Utterance> = _finals.asSharedFlow()

    private val _threshold = MutableStateFlow(0f)
    /**
     * The loudness (same scale as [level]) a sound has to reach to count as speech right now.
     * While someone is talking it rises to the "pause" cutoff, which is relative to their voice.
     */
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** Set when the phone's recognizer gives up (see [startGoogle]); whisper never sets it. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private var google: GoogleRecognizer? = null

    /** While true (e.g. the phone itself is talking) audio is discarded. */
    @Volatile var muted = false

    /**
     * Receives every non-muted microphone frame (pcm, sample count, position of its first
     * sample since the microphone started). Used to record voice notes while dictating.
     */
    @Volatile var tap: ((ShortArray, Int, Long) -> Unit)? = null

    @Volatile private var active = false
    private var recorderThread: Thread? = null

    private class Job(val id: Int, val samples: FloatArray, val final: Boolean, val segment: Int, val startSample: Long) {
        val queuedAt = SystemClock.elapsedRealtime()
    }

    /**
     * [sensitivity] (1..10): how quiet a sound can be and still start an utterance.
     * [endPauseMs]: how long a pause has to be before the utterance is finished.
     * [noiseFilter] (1..10): how far below your own voice a sound must drop to count as a
     * pause; higher ends utterances sooner when background noise is loud. Null turns it off.
     * [noiseAdaptMs]: how long the background-noise level takes to catch up with a change.
     * [rapid]: a whole set said in one breath. Allows longer phrases, gives long phrases more
     * time to transcribe instead of giving up at 5 s, and skips live text so the final
     * transcription gets the whole CPU.
     */
    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO before starting
    fun start(
        stt: SpeechToText, sensitivity: Int, livePartials: Boolean, endPauseMs: Int, noiseFilter: Int?, noiseAdaptMs: Int,
        rapid: Boolean,
    ): Boolean {
        val showPartials = livePartials && !rapid
        val maxSegmentMs = if (rapid) RAPID_MAX_SEGMENT_MS else MAX_SEGMENT_MS
        if (active) return true
        val record = openMic() ?: return false
        active = true
        _running.value = true
        val jobs = LinkedBlockingDeque<Job>()
        val busy = java.util.concurrent.atomic.AtomicBoolean(false)
        val currentSegment = java.util.concurrent.atomic.AtomicInteger(0)
        val segmentOpen = java.util.concurrent.atomic.AtomicBoolean(false)
        val micDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val nextJobId = java.util.concurrent.atomic.AtomicInteger(1)
        // Id of the live-text job being transcribed right now, or 0.
        val runningPartial = java.util.concurrent.atomic.AtomicInteger(0)

        val worker = Thread({
            while (!micDone.get() || jobs.isNotEmpty()) {
                val job = jobs.pollFirst(200, TimeUnit.MILLISECONDS) ?: continue
                busy.set(true)
                if (!job.final) runningPartial.set(job.id)
                try {
                    val began = SystemClock.elapsedRealtime()
                    val timeout = if (rapid && job.final) {
                        SpeechToText.DEFAULT_TIMEOUT_MS + job.samples.size * RAPID_TIMEOUT_MS_PER_SECOND / RATE
                    } else {
                        SpeechToText.DEFAULT_TIMEOUT_MS
                    }
                    val raw = stt.transcribe(padded(job.samples), PROMPT, job.id, timeout)
                    val text = clean(raw)
                    Log.i(
                        TAG, "%s job %d: %.1f s of audio, waited %d ms, took %d ms -> \"%s\"".format(
                            if (job.final) "final" else "partial", job.id, job.samples.size / RATE.toFloat(),
                            began - job.queuedAt, SystemClock.elapsedRealtime() - began, raw.trim(),
                        )
                    )
                    if (job.final) {
                        if (currentSegment.get() == job.segment) _partial.value = ""
                        if (text.isNotEmpty()) _finals.tryEmit(Utterance(text, job.startSample))
                    } else if (job.segment == currentSegment.get() && segmentOpen.get()) {
                        _partial.value = text
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "transcription failed", e)
                } finally {
                    runningPartial.set(0)
                    busy.set(false)
                }
            }
        }, "tut-stt-worker")

        val (ratio, absMin) = speechThresholds(sensitivity)
        val endPauseFrames = max(1, endPauseMs.coerceIn(200, 10_000) / FRAME_MS)
        // While talking, anything quieter than this share of your own voice counts as a pause,
        // so steady background noise (fan, hiss, emulator mic) can't hold an utterance open.
        // Off: only the start threshold counts.
        val pauseRatio = noiseFilter?.let { 0.05f * it.coerceIn(1, 10) } ?: 0f
        // Share of the way the noise level moves toward the current loudness each frame.
        // With the filter off the noise level never moves, so the thresholds stay fixed and
        // only microphone sensitivity decides what counts as speech.
        val adapt = if (noiseFilter == null) 0f else FRAME_MS.toFloat() / noiseAdaptMs.coerceAtLeast(FRAME_MS)

        recorderThread = Thread({
            val pcm = ShortArray(FRAME)
            val preroll = ArrayDeque<FloatArray>()
            val prerollStarts = ArrayDeque<Long>()
            val segment = ArrayList<FloatArray>()
            var segmentStart = 0L
            var position = 0L
            var inSpeech = false
            var silenceFrames = 0
            var speechFrames = 0
            var noise = 0.003f
            var voice = 0f                         // typical loudness of the current speaker
            val recentRms = ArrayDeque<Float>()   // last second of loudness, to track noise while talking
            var lastPartialAt = 0L

            fun segmentSamples(): FloatArray {
                val out = FloatArray(segment.sumOf { it.size })
                var o = 0
                for (f in segment) { f.copyInto(out, o); o += f.size }
                return out
            }

            fun finish(reason: String) {
                if (speechFrames >= MIN_SPEECH_FRAMES) {
                    // Drop most of the trailing silence; whisper doesn't need it.
                    val keep = max(0, silenceFrames - 4)
                    repeat(min(keep, segment.size)) { segment.removeAt(segment.size - 1) }
                    Log.i(
                        TAG, "utterance ended (%s): %.1f s kept, %.1f s of it speech; noise %.4f, voice %.4f".format(
                            reason, segment.size * FRAME_MS / 1000f, speechFrames * FRAME_MS / 1000f, noise, voice,
                        )
                    )
                    // The final text replaces any live text, so stop working on that right away
                    // instead of making the final wait behind it.
                    jobs.removeIf { !it.final }
                    runningPartial.get().takeIf { it != 0 }?.let { stt.cancel(it) }
                    jobs.addLast(Job(nextJobId.getAndIncrement(), segmentSamples(), true, currentSegment.get(), segmentStart))
                } else {
                    _partial.value = ""
                }
                segment.clear()
                inSpeech = false
                segmentOpen.set(false)
                silenceFrames = 0
                speechFrames = 0
            }

            record.startRecording()
            try {
                while (active) {
                    val n = record.read(pcm, 0, FRAME)
                    if (n <= 0) continue
                    val frameStart = position
                    position += n
                    val frame = FloatArray(n) { pcm[it] / 32768f }
                    var sum = 0.0
                    for (v in frame) sum += v * v
                    val rms = sqrt(sum / n).toFloat()
                    _level.value = min(1f, rms * 12f)

                    if (muted) {
                        if (inSpeech) finish("muted")
                        preroll.clear()
                        prerollStarts.clear()
                        continue
                    }
                    tap?.invoke(pcm, n, frameStart)

                    val startThreshold = max(noise * ratio, absMin)
                    val speech = rms > startThreshold
                    if (!speech) noise += (rms - noise) * adapt
                    else noise += (rms - noise) * adapt * 0.02f
                    // Even mid-sentence there are short gaps; the quietest moment of the last
                    // second is a good guess at the background, so let the floor rise to it.
                    recentRms.addLast(rms)
                    if (recentRms.size > 1000 / FRAME_MS) recentRms.removeFirst()
                    if (inSpeech && recentRms.size == 1000 / FRAME_MS) {
                        val quietest = recentRms.min()
                        if (quietest > noise) noise += (quietest - noise) * adapt
                    }
                    noise = noise.coerceIn(0.0005f, 0.05f)

                    if (!inSpeech) {
                        _threshold.value = min(1f, startThreshold * 12f)
                        preroll.addLast(frame)
                        prerollStarts.addLast(frameStart)
                        if (preroll.size > PREROLL_FRAMES) { preroll.removeFirst(); prerollStarts.removeFirst() }
                        if (speech) {
                            segmentStart = prerollStarts.first()
                            inSpeech = true
                            currentSegment.incrementAndGet()
                            segmentOpen.set(true)
                            segment.addAll(preroll)
                            preroll.clear()
                            prerollStarts.clear()
                            speechFrames = 1
                            silenceFrames = 0
                            voice = rms
                            lastPartialAt = System.currentTimeMillis()
                        }
                        continue
                    }

                    segment.add(frame)
                    val pauseThreshold = max(startThreshold, voice * pauseRatio)
                    _threshold.value = min(1f, pauseThreshold * 12f)
                    if (rms > pauseThreshold) {
                        speechFrames++
                        silenceFrames = 0
                        voice += (rms - voice) * 0.1f
                    } else {
                        silenceFrames++
                    }

                    val segMs = segment.size * FRAME_MS
                    when {
                        silenceFrames >= endPauseFrames -> finish("pause")
                        // A long end pause shouldn't eat into the length limit: count speech up to the pause.
                        segMs - silenceFrames * FRAME_MS >= maxSegmentMs -> finish("max length")
                        showPartials && segMs >= 1000 && !busy.get() && jobs.isEmpty() &&
                            System.currentTimeMillis() - lastPartialAt >= PARTIAL_EVERY_MS -> {
                            lastPartialAt = System.currentTimeMillis()
                            jobs.addLast(Job(nextJobId.getAndIncrement(), segmentSamples(), false, currentSegment.get(), segmentStart))
                        }
                    }
                }
                if (inSpeech) finish("stopped")
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
                _level.value = 0f
                _threshold.value = 0f
                micDone.set(true)
            }
        }, "tut-mic")

        worker.start()
        recorderThread!!.start()
        return true
    }

    /**
     * Like [start], but the phone's own recognizer (Google's on-device one) does the
     * transcribing, while you talk. Our microphone still feeds it, so [level], [muted] and
     * [tap] work the same. Returns false if the microphone can't be opened; later problems
     * (e.g. the offline English pack is missing) arrive through [problem].
     *
     * The recognizer doesn't say where a phrase began, so [Utterance.startSample] is the
     * start of the latest burst of sound after a gap, measured here.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(33)
    fun startGoogle(context: Context, sensitivity: Int, livePartials: Boolean): Boolean {
        if (active) return true
        val record = openMic() ?: return false
        active = true
        _running.value = true
        _problem.value = null
        val phraseStart = AtomicLong(-1L)
        val g = GoogleRecognizer(
            context.applicationContext, livePartials,
            onPartial = { _partial.value = it },
            onFinal = { _finals.tryEmit(Utterance(it, phraseStart.get())) },
            onProblem = { _problem.value = it },
        )
        google = g
        g.start()

        val (ratio, absMin) = speechThresholds(sensitivity)
        recorderThread = Thread({
            val pcm = ShortArray(FRAME)
            val silence = ShortArray(FRAME)
            var position = 0L
            var noise = 0.003f
            var quietFrames = PHRASE_GAP_FRAMES
            record.startRecording()
            try {
                while (active) {
                    val n = record.read(pcm, 0, FRAME)
                    if (n <= 0) continue
                    val frameStart = position
                    position += n
                    var sum = 0.0
                    for (i in 0 until n) { val v = pcm[i] / 32768f; sum += v * v }
                    val rms = sqrt(sum / n).toFloat()
                    _level.value = min(1f, rms * 12f)

                    if (muted) {
                        // Silence lets the recognizer finish the phrase it's on.
                        g.write(silence, n)
                        quietFrames = PHRASE_GAP_FRAMES
                        continue
                    }
                    tap?.invoke(pcm, n, frameStart)
                    g.write(pcm, n)

                    val threshold = max(noise * ratio, absMin)
                    _threshold.value = min(1f, threshold * 12f)
                    if (rms > threshold) {
                        if (quietFrames >= PHRASE_GAP_FRAMES) phraseStart.set(max(0L, frameStart - PREROLL_FRAMES * FRAME))
                        quietFrames = 0
                    } else {
                        quietFrames++
                        noise = (noise + (rms - noise) * 0.05f).coerceIn(0.0005f, 0.05f)
                    }
                }
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
                _level.value = 0f
                _threshold.value = 0f
            }
        }, "tut-mic")
        recorderThread!!.start()
        return true
    }

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO before starting
    private fun openMic(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME * 2 * 8)
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord failed", e); return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); return null
        }
        return record
    }

    fun stop() {
        active = false
        _running.value = false
        recorderThread?.join(1000)
        recorderThread = null
        google?.stop()
        google = null
        _partial.value = ""
    }

    companion object {
        const val MAX_SENSITIVITY = 15

        /**
         * Sensitivity (1..[MAX_SENSITIVITY]) as (how far above the noise floor counts as speech,
         * the quietest sound that can ever count as speech). Above 10 both keep shrinking in
         * smaller steps, and the ratio stays above 1 so steady background noise never counts.
         */
        fun speechThresholds(sensitivity: Int): Pair<Float, Float> {
            val sens = sensitivity.coerceIn(1, MAX_SENSITIVITY)
            if (sens <= 10) return (3.5f - 0.2f * sens) to 0.0012f * (11 - sens)
            val extra = sens - 10
            return (1.5f - 0.06f * extra) to 0.0012f * 0.7f.pow(extra)
        }

        private const val TAG = "SpeechEngine"
        const val RATE = 16000
        private const val FRAME_MS = 50
        private const val FRAME = RATE * FRAME_MS / 1000
        private const val PREROLL_FRAMES = 6          // 300 ms before speech starts
        private const val MAX_SEGMENT_MS = 12_000
        private const val RAPID_MAX_SEGMENT_MS = 25_000      // whisper's window is 30 s
        private const val RAPID_TIMEOUT_MS_PER_SECOND = 750  // extra time per second of audio
        private const val PARTIAL_EVERY_MS = 1200L
        private const val MIN_SPEECH_FRAMES = 4       // ignore clicks / bumps under 200 ms
        private const val PHRASE_GAP_FRAMES = 12      // 600 ms of quiet before sound starts a new phrase

        /** Biases whisper towards the gym vocabulary and towards writing numbers as digits. */
        const val PROMPT =
            "Exercise squats. Warm-up set 1, 2 minutes rest, 45 pounds, 12 reps, moderate RIR. " +
                "Working set 2, rest 2:30, 100 kilograms, 1:20 time under tension, high RIR. Done. " +
                "Add voice note. Done recording. New recording. Start. Stop. Save. Yes. No. Never mind."

        private val hallucinations = setOf(
            "thank you", "thanks for watching", "you", "bye", "thank you for watching", "so", "okay",
        )

        fun clean(raw: String): String {
            val t = raw.replace(Regex("""[\[(][^\])]*[\])]"""), " ")
                .replace(Regex("\\s+"), " ").trim()
            val key = t.lowercase().trim('.', '!', '?', ',', ' ')
            if (key.isEmpty() || key in hallucinations) return ""
            return t
        }

        /** Whisper needs at least ~1 s of audio; pad short utterances with silence. */
        private fun padded(samples: FloatArray): FloatArray =
            if (samples.size >= RATE * 11 / 10) samples else samples.copyOf(RATE * 11 / 10)
    }
}
