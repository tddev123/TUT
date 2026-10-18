package com.example.tut.stt

/** JNI bridge to whisper.cpp (see src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    init {
        System.loadLibrary("tut_whisper")
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(ctx: Long)
    external fun nativeTranscribe(
        ctx: Long, samples: FloatArray, threads: Int, prompt: String?, audioCtx: Int, timeoutMs: Int, jobId: Int,
    ): String
    external fun nativeCancel(ctx: Long, jobId: Int)
    external fun nativeSystemInfo(): String
}

/**
 * Anything that turns 16 kHz mono float audio into text. Whisper is the only
 * implementation today; a different model or engine can be dropped in here.
 */
interface SpeechToText : AutoCloseable {
    val name: String

    /**
     * [jobId] identifies this call so it can be stopped early with [cancel]. Gives up and
     * returns "" after [timeoutMs].
     */
    fun transcribe(samples: FloatArray, prompt: String?, jobId: Int = 0, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String

    /** Asks a running [transcribe] with this [jobId] to stop and return "". Safe from any thread. */
    fun cancel(jobId: Int) {}

    companion object {
        // Unclear audio can send whisper into a repetition loop that runs for 30 s or more
        // and holds up everything queued behind it; give up and return "" instead.
        const val DEFAULT_TIMEOUT_MS = 5_000
    }
}

class WhisperSpeechToText private constructor(
    @Volatile private var ctx: Long,
    override val name: String,
    private val threads: Int,
    private val fastMode: Boolean,
) : SpeechToText {

    @Synchronized
    override fun transcribe(samples: FloatArray, prompt: String?, jobId: Int, timeoutMs: Int): String {
        if (ctx == 0L) return ""
        // Whisper encodes a 30 s window by default (1500 frames, 50 per second). For short
        // utterances, shrinking that window makes inference several times faster.
        val audioCtx = if (fastMode) ((samples.size / 16000.0) * 50 + 128).toInt().coerceIn(256, 1500) else 0
        return WhisperLib.nativeTranscribe(ctx, samples, threads, prompt, audioCtx, timeoutMs, jobId)
    }

    // Not synchronized: it has to reach a transcribe() that is holding the lock.
    override fun cancel(jobId: Int) {
        val c = ctx
        if (c != 0L) WhisperLib.nativeCancel(c, jobId)
    }

    @Synchronized
    override fun close() {
        if (ctx != 0L) WhisperLib.nativeFree(ctx)
        ctx = 0L
    }

    companion object {
        fun load(path: String, name: String, threads: Int, fastMode: Boolean): WhisperSpeechToText? {
            val ctx = WhisperLib.nativeInit(path)
            return if (ctx == 0L) null else WhisperSpeechToText(ctx, name, threads, fastMode)
        }
    }
}
