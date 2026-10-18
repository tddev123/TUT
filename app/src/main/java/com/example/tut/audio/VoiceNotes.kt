package com.example.tut.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Voice notes attached to data blocks: 16 kHz mono 16-bit WAV files. Recording again
 * appends to the existing note instead of replacing it.
 */
object Wav {
    const val RATE = 16000
    private const val HEADER = 44

    fun writeHeader(f: RandomAccessFile, dataBytes: Long) {
        val b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        f.seek(0); f.write(b.array())
    }

    fun durationMs(file: File): Long =
        if (!file.exists() || file.length() <= HEADER) 0 else (file.length() - HEADER) * 1000 / (RATE * 2)

    /** Peak amplitude (0..1) per [bucketMs] of audio, for drawing the waveform. */
    fun peaks(file: File, bucketMs: Int = 50): FloatArray {
        if (!file.exists() || file.length() <= HEADER) return FloatArray(0)
        val samplesPerBucket = RATE * bucketMs / 1000
        val bytes = file.readBytes()
        val n = (bytes.size - HEADER) / 2
        val out = FloatArray((n + samplesPerBucket - 1) / samplesPerBucket)
        val buf = ByteBuffer.wrap(bytes, HEADER, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until n) {
            val v = abs(buf.get(i).toInt()) / 32768f
            val bucket = i / samplesPerBucket
            out[bucket] = max(out[bucket], v)
        }
        return out
    }
}

/**
 * Writes microphone frames handed over by the speech engine into a WAV voice note, so a note
 * can be recorded while dictation keeps running. Opening an existing file appends to it.
 */
class NoteWriter(val file: File) {
    private val raf: RandomAccessFile
    private val baseBytes: Long
    private var written = 0L
    private var firstSample = -1L
    private var closed = false
    private val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw")
        baseBytes = if (raf.length() > 44) raf.length() - 44 else 0L
        if (baseBytes == 0L) Wav.writeHeader(raf, 0)
        raf.seek(44 + baseBytes)
    }

    /** [start] is the microphone position of the frame's first sample. */
    @Synchronized
    fun write(pcm: ShortArray, n: Int, start: Long) {
        if (closed) return
        if (firstSample < 0) firstSample = start
        buf.clear()
        for (i in 0 until n) buf.putShort(pcm[i])
        raf.seek(44 + baseBytes + written)
        raf.write(buf.array(), 0, n * 2)
        written += n * 2
        // Keep the header current so the note survives the app being closed mid-recording.
        Wav.writeHeader(raf, baseBytes + written)
    }

    /** Closes the note, dropping audio from microphone position [cutAt] on (the spoken "done recording"). */
    @Synchronized
    fun finish(cutAt: Long?) {
        if (closed) return
        closed = true
        if (cutAt != null && firstSample >= 0) {
            written = ((cutAt - firstSample) * 2).coerceIn(0L, written)
        }
        raf.setLength(44 + baseBytes + written)
        Wav.writeHeader(raf, baseBytes + written)
        raf.close()
    }
}

class VoiceNoteRecorder {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    @Volatile private var active = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(file: File): Boolean {
        if (active) return false
        val minBuf = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, Wav.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192))
        } catch (e: Exception) { return false }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }
        file.parentFile?.mkdirs()
        active = true
        _recording.value = true
        thread = Thread({
            RandomAccessFile(file, "rw").use { raf ->
                var dataBytes = if (raf.length() > 44) raf.length() - 44 else 0L
                if (dataBytes == 0L) Wav.writeHeader(raf, 0)
                raf.seek(44 + dataBytes)
                val pcm = ShortArray(1600)
                val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                record.startRecording()
                try {
                    while (active) {
                        val n = record.read(pcm, 0, pcm.size)
                        if (n <= 0) continue
                        bytes.clear()
                        var peak = 0
                        for (i in 0 until n) { bytes.putShort(pcm[i]); peak = max(peak, abs(pcm[i].toInt())) }
                        raf.write(bytes.array(), 0, n * 2)
                        dataBytes += n * 2
                        _level.value = peak / 32768f
                    }
                } finally {
                    try { record.stop() } catch (_: Exception) {}
                    record.release()
                    Wav.writeHeader(raf, dataBytes)
                    _level.value = 0f
                }
            }
        }, "tut-note-recorder").apply { start() }
        return true
    }

    fun stop() {
        active = false
        thread?.join(2000)
        thread = null
        _recording.value = false
    }
}

class VoiceNotePlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    var file: File? = null
        private set

    val positionMs: Int get() = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }

    fun play(f: File, fromMs: Int = -1) {
        if (file != f || player == null) {
            release()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                setOnCompletionListener { _playing.value = false }
            }
            file = f
        }
        if (fromMs >= 0) player?.seekTo(fromMs)
        player?.start()
        _playing.value = true
    }

    fun pause() {
        player?.pause()
        _playing.value = false
    }

    fun seekTo(ms: Int) {
        player?.seekTo(ms)
    }

    fun release() {
        player?.release()
        player = null
        file = null
        _playing.value = false
    }
}
