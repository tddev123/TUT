package com.example.tut

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tut.parse.CommandParser
import com.example.tut.stt.SpeechEngine
import com.example.tut.stt.WhisperLib
import com.example.tut.stt.WhisperSpeechToText
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end check of the native library + model + parser. Needs a model and a 16 kHz mono
 * 16-bit WAV pushed to the app's external files dir:
 *   adb push ggml-small.en-q8_0.bin /sdcard/Android/data/com.example.tut/files/models/
 *   adb push sample.wav /sdcard/Android/data/com.example.tut/files/test.wav
 * Skipped when they are missing.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOnDeviceTest {

    @Test
    fun transcribeAndParse() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.getExternalFilesDir(null)!!
        val model = File(dir, "models").listFiles { f -> f.name.endsWith(".bin") }?.firstOrNull()
        val wav = File(dir, "test.wav")
        assumeTrue("model and test.wav required", model != null && wav.exists())

        Log.i(TAG, WhisperLib.nativeSystemInfo())
        val t0 = System.currentTimeMillis()
        val stt = WhisperSpeechToText.load(model!!.absolutePath, model.name, 4, fastMode = true)!!
        Log.i(TAG, "model load ms=${System.currentTimeMillis() - t0}")

        val bytes = wav.readBytes()
        val start = String(bytes, 0, 128, Charsets.ISO_8859_1).indexOf("data") + 8
        val n = (bytes.size - start) / 2
        val shorts = ByteBuffer.wrap(bytes, start, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(n) { shorts.get(it) / 32768f }

        val t1 = System.currentTimeMillis()
        val text = SpeechEngine.clean(stt.transcribe(samples, SpeechEngine.PROMPT))
        val ms = System.currentTimeMillis() - t1
        val actions = CommandParser.parse(text)
        Log.i(TAG, "audio=${n / 16000.0}s infer ms=$ms text='$text'")
        Log.i(TAG, "actions=$actions")
        stt.close()
        assert(actions.isNotEmpty()) { "no actions parsed from '$text'" }
    }

    companion object { const val TAG = "TutTest" }
}
