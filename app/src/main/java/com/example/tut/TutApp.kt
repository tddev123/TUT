package com.example.tut

import android.app.Application
import android.content.Context
import com.example.tut.data.Backup
import com.example.tut.data.DataOps
import com.example.tut.data.Repository
import com.example.tut.data.SettingsStore
import com.example.tut.stt.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** The two separate databases; holding the logo on the main page switches between them. */
enum class DbMode(val label: String, val fileName: String, val notesFolder: String) {
    TUT("TUT", "tut.db", "notes"),
    SKILLS("Skills", "skills.db", "notes_skills"),
}

class TutApp : Application() {
    private lateinit var repos: Map<DbMode, Repository>
    private lateinit var allOps: Map<DbMode, DataOps>
    lateinit var settings: SettingsStore private set
    lateinit var models: ModelManager private set

    private val modePrefs by lazy { getSharedPreferences("tut_mode", Context.MODE_PRIVATE) }
    private val _mode = MutableStateFlow(DbMode.TUT)
    /** Which database every screen is currently reading and writing. */
    val mode: StateFlow<DbMode> = _mode.asStateFlow()

    val repo: Repository get() = repos.getValue(_mode.value)
    val ops: DataOps get() = allOps.getValue(_mode.value)

    /** For database work that must finish even after the screen that started it is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Each database keeps its voice notes in its own folder, since ids repeat between them. */
    val notesDir: File get() = File(filesDir, _mode.value.notesFolder).apply { mkdirs() }

    override fun onCreate() {
        super.onCreate()
        // A restore confirmed before the app closed goes in now, before anything is opened.
        runCatching { Backup.applyPending(this) }
        _mode.value = DbMode.entries.firstOrNull { it.name == modePrefs.getString("mode", null) } ?: DbMode.TUT
        settings = SettingsStore(this)
        models = ModelManager(this)
        repos = DbMode.entries.associateWith { Repository(this, it.fileName) }
        allOps = repos.mapValues { (_, r) -> DataOps(r, settings) }
        appScope.launch {
            repos.values.forEach { it.deleteEmptySessions() }
            repos.getValue(DbMode.TUT).canonicalizeExerciseNames()
            allOps.getValue(DbMode.TUT).recolorAll()
        }
        appScope.launch {
            // New thresholds change which workouts count as stronger / weaker: recolour (debounced
            // so stepping through values doesn't recolour on every tap).
            settings.settings.map { it.thresholds }.distinctUntilChanged().drop(1).collectLatest {
                delay(600)
                allOps.getValue(DbMode.TUT).recolorAll()
            }
        }
    }

    /** Either database, whichever one is showing. */
    fun repoFor(mode: DbMode): Repository = repos.getValue(mode)

    fun opsFor(mode: DbMode): DataOps = allOps.getValue(mode)

    fun toggleMode() {
        val next = if (_mode.value == DbMode.TUT) DbMode.SKILLS else DbMode.TUT
        modePrefs.edit().putString("mode", next.name).apply()
        _mode.value = next
    }
}
