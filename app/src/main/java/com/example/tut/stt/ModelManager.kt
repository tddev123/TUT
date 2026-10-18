package com.example.tut.stt

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.tut.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds whisper model files and keeps the loaded model in memory so the recording page
 * opens instantly after the first load. Models live in either
 *  - the app's private files/models folder (filled by "Import model" in settings), or
 *  - Android/data/com.example.tut/files/models on shared storage (copy there over USB / adb).
 */
class ModelManager(private val context: Context) {
    private val mutex = Mutex()
    private var loaded: SpeechToText? = null
    private var loadedKey: String? = null

    val internalDir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    val externalDir: File? get() = context.getExternalFilesDir("models")?.apply { mkdirs() }

    fun availableModels(): List<File> =
        listOfNotNull(internalDir, externalDir)
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.toList().orEmpty() }
            .distinctBy { it.name }
            .sortedBy { it.name }

    fun find(name: String): File? = availableModels().firstOrNull { it.name == name }

    /** Returns the model named in settings (falling back to any available model), loading it if needed. */
    suspend fun get(s: AppSettings): SpeechToText? = mutex.withLock {
        val file = find(s.modelFileName) ?: availableModels().firstOrNull() ?: return null
        val key = "${file.absolutePath}|${s.sttThreads}|${s.fastMode}"
        if (key != loadedKey) {
            withContext(Dispatchers.IO) {
                loaded?.close()
                loaded = null
                loadedKey = null
                loaded = WhisperSpeechToText.load(file.absolutePath, file.name, s.sttThreads, s.fastMode)
                if (loaded != null) loadedKey = key
            }
        }
        loaded
    }

    /** Copies a model picked with the system file picker into private storage. */
    suspend fun import(uri: Uri): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "imported-model.bin"
        if (!name.endsWith(".bin")) name += ".bin"
        val target = File(internalDir, name)
        val tmp = File(internalDir, "$name.part")
        resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        tmp.renameTo(target)
        target
    }

    fun delete(file: File) {
        file.delete()
    }
}
