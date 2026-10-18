package com.example.tut.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private class TutDbHelper(context: Context, fileName: String) : SQLiteOpenHelper(context, fileName, null, 12) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                color TEXT,
                color_manual INTEGER NOT NULL DEFAULT 0,
                saved INTEGER NOT NULL DEFAULT 0,
                note_path TEXT,
                recorded_ms INTEGER,
                excluded INTEGER NOT NULL DEFAULT 0,
                trashed_at INTEGER,
                sleep_hours REAL,
                recovery TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE exercises(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                position INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                first_activity_at INTEGER,
                last_activity_at INTEGER,
                color TEXT,
                note_path TEXT,
                limit_marked INTEGER NOT NULL DEFAULT 0,
                fatigue_manual INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE sets(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exercise_id INTEGER NOT NULL REFERENCES exercises(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                number INTEGER NOT NULL,
                rest_sec INTEGER,
                weight REAL,
                unit TEXT,
                reps INTEGER,
                tut_sec INTEGER,
                rir_level TEXT,
                rir_value INTEGER,
                completed_at INTEGER,
                UNIQUE(exercise_id, kind, number))"""
        )
        db.execSQL("CREATE INDEX idx_ex_session ON exercises(session_id)")
        db.execSQL("CREATE INDEX idx_ex_name ON exercises(name)")
        createNotesTable(db)
        createSkillTables(db)
        createPredictionsTable(db)
    }

    /** Voice recordings of a session (big data block); a session can have several. */
    private fun createNotesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE session_notes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                path TEXT NOT NULL,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_notes_session ON session_notes(session_id)")
    }

    /** Locked-in workout predictions, kept to compare with the workout once it's done. */
    private fun createPredictionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE predictions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                planned_for INTEGER NOT NULL,
                baseline_exercise_id INTEGER NOT NULL,
                baseline_at INTEGER NOT NULL,
                baseline_strength REAL NOT NULL,
                plan TEXT NOT NULL,
                predicted_pct REAL NOT NULL,
                curve TEXT NOT NULL,
                trained_on INTEGER NOT NULL,
                typical_miss REAL,
                verdict INTEGER,
                source TEXT NOT NULL)"""
        )
    }

    /** Skills database: static skill blocks and the hours logged into them. */
    private fun createSkillTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE skills(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                position INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                capacity REAL)"""
        )
        db.execSQL(
            """CREATE TABLE skill_logs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                skill_id INTEGER NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
                hours REAL NOT NULL,
                logged_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_logs_skill ON skill_logs(skill_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 allowed one recording per session in sessions.note_path: move it to the new table.
            createNotesTable(db)
            db.execSQL(
                "INSERT INTO session_notes(session_id, path, created_at) " +
                    "SELECT id, note_path, started_at FROM sessions WHERE note_path IS NOT NULL"
            )
            db.execSQL("UPDATE sessions SET note_path = NULL")
        }
        if (oldVersion < 3) createSkillTables(db)
        // v3 skills had no capacity column: NULL means "use the default from settings".
        else if (oldVersion < 4) db.execSQL("ALTER TABLE skills ADD COLUMN capacity REAL")
        // v5: how long the recording page's timer ran for each session; NULL for older sessions.
        if (oldVersion < 5) db.execSQL("ALTER TABLE sessions ADD COLUMN recorded_ms INTEGER")
        if (oldVersion < 6) createPredictionsTable(db)
        // v7: workouts marked as about the most progress possible from one workout to the next.
        if (oldVersion < 7) db.execSQL("ALTER TABLE exercises ADD COLUMN limit_marked INTEGER NOT NULL DEFAULT 0")
        // v8: fatigue level set by hand; NULL = counted automatically.
        if (oldVersion < 8) db.execSQL("ALTER TABLE exercises ADD COLUMN fatigue_manual INTEGER")
        // v9: big data blocks left out of the prediction model.
        if (oldVersion < 9) db.execSQL("ALTER TABLE sessions ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0")
        // v10: big data blocks in the trash: when they were put there; NULL = not in the trash.
        if (oldVersion < 10) db.execSQL("ALTER TABLE sessions ADD COLUMN trashed_at INTEGER")
        // v11: hours slept before the workout, said by voice; NULL = not given.
        if (oldVersion < 11) db.execSQL("ALTER TABLE sessions ADD COLUMN sleep_hours REAL")
        // v12: recovery feeling, said by voice; NULL = not given. Never used by the prediction model.
        if (oldVersion < 12) db.execSQL("ALTER TABLE sessions ADD COLUMN recovery TEXT")
    }
}

/**
 * All database access. Every write goes straight to SQLite, so data spoken during
 * a recording survives the app being closed. Calls are blocking: use from Dispatchers.IO.
 */
class Repository(context: Context, fileName: String) {
    private val db = TutDbHelper(context, fileName).writableDatabase
    private val _version = MutableStateFlow(0L)

    /** The database's schema version, for checking a backup isn't from a newer app. */
    val schemaVersion: Int get() = db.version

    /** A consistent copy of the whole database in [to], for saving all data to a file. */
    fun copyTo(to: File) {
        to.delete()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            db.execSQL("VACUUM INTO '" + to.absolutePath.replace("'", "''") + "'")
        } else {
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            File(db.path).copyTo(to, overwrite = true)
        }
    }

    /** Bumped after every write so screens know to reload. */
    val version: StateFlow<Long> = _version.asStateFlow()
    private fun changed() { _version.value = _version.value + 1 }

    // ---- sessions (big data blocks) ----

    fun createSession(startedAt: Long): Long {
        val id = db.insert("sessions", null, ContentValues().apply { put("started_at", startedAt) })
        changed()
        return id
    }

    fun session(id: Long): Session? =
        db.query("sessions", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toSession() else null
        }

    /** Stores the recording page's timer. [notify] off for the regular saves while it runs. */
    fun setSessionRecordedMs(id: Long, ms: Long, notify: Boolean = true) {
        db.update("sessions", ContentValues().apply { put("recorded_ms", ms) }, "id=?", arrayOf(id.toString()))
        if (notify) changed()
    }

    /** Hours slept before the workout ("8 hours of sleep"). */
    fun setSessionSleepHours(id: Long, hours: Double?) {
        db.update("sessions", ContentValues().apply { if (hours == null) putNull("sleep_hours") else put("sleep_hours", hours) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Recovery feeling ("recovery feeling fresh"). Only for reading; not a prediction input. */
    fun setSessionRecovery(id: Long, recovery: RecoveryFeeling?) {
        db.update("sessions", ContentValues().apply { if (recovery == null) putNull("recovery") else put("recovery", recovery.name) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Leaves a big data block out of the prediction model (or puts it back). */
    fun setSessionExcluded(id: Long, excluded: Boolean) {
        db.update("sessions", ContentValues().apply { put("excluded", if (excluded) 1 else 0) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Moves a big data block to another date and time (custom data mode). */
    fun setSessionStartedAt(id: Long, at: Long) {
        db.update("sessions", ContentValues().apply { put("started_at", at) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setSessionSaved(id: Long, saved: Boolean) {
        db.update("sessions", ContentValues().apply { put("saved", if (saved) 1 else 0) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setSessionColor(id: Long, color: BlockColor?, manual: Boolean) {
        db.update("sessions", ContentValues().apply {
            put("color", color?.name)
            put("color_manual", if (manual) 1 else 0)
        }, "id=?", arrayOf(id.toString()))
        changed()
    }

    // ---- session recordings ----

    fun sessionNotes(sessionId: Long): List<SessionNote> =
        db.query("session_notes", null, "session_id=?", arrayOf(sessionId.toString()), null, null, "created_at, id")
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(SessionNote(c.long("id")!!, c.long("session_id")!!, c.str("path")!!, c.long("created_at")!!))
                }
            }

    fun addSessionNote(sessionId: Long, path: String, now: Long): Long {
        val id = db.insert("session_notes", null, ContentValues().apply {
            put("session_id", sessionId)
            put("path", path)
            put("created_at", now)
        })
        changed()
        return id
    }

    /** Deletes a big data block's recording and its audio file. */
    fun deleteSessionNote(note: SessionNote) {
        db.delete("session_notes", "id=?", arrayOf(note.id.toString()))
        File(note.path).delete()
        changed()
    }

    /** Lets screens reload after audio was appended to an existing recording. */
    fun notifyNotesChanged() = changed()

    /** Removes a session that never received any data (e.g. opened and immediately left). */
    fun deleteSessionIfEmpty(id: Long) {
        val s = session(id) ?: return
        val hasExercises = db.rawQuery("SELECT 1 FROM exercises WHERE session_id=? LIMIT 1", arrayOf(id.toString()))
            .use { it.moveToFirst() }
        if (!hasExercises && s.notePath == null && sessionNotes(id).isEmpty()) {
            db.delete("sessions", "id=?", arrayOf(id.toString()))
            changed()
        }
    }

    /** Clears sessions left empty when the app was closed mid-recording. Call at startup only. */
    fun deleteEmptySessions() {
        val n = db.delete(
            "sessions",
            "note_path IS NULL AND NOT EXISTS (SELECT 1 FROM exercises e WHERE e.session_id = sessions.id) " +
                "AND NOT EXISTS (SELECT 1 FROM session_notes n WHERE n.session_id = sessions.id)",
            null,
        )
        if (n > 0) changed()
    }

    /** Puts a big data block in the trash: hidden everywhere, but kept until deleted from there. */
    fun trashSession(id: Long, now: Long) {
        db.update("sessions", ContentValues().apply { put("trashed_at", now) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Takes a big data block back out of the trash. */
    fun restoreSession(id: Long) {
        db.update("sessions", ContentValues().apply { putNull("trashed_at") }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Deletes a big data block for good, with its recordings. */
    fun deleteSession(id: Long) {
        val notes = mutableListOf<String?>()
        session(id)?.let { notes += it.notePath }
        exercises(id).forEach { notes += it.notePath }
        sessionNotes(id).forEach { notes += it.path }
        db.delete("sessions", "id=?", arrayOf(id.toString()))
        notes.filterNotNull().forEach { File(it).delete() }
        changed()
    }

    fun allSessions(): List<SessionWithExercises> {
        val sessions = db.query("sessions", null, "trashed_at IS NULL", null, null, null, "started_at DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.toSession()) }
        }
        return sessions.map { sessionWithExercises(it) }
    }

    /** The big data blocks in the trash, most recently trashed first. */
    fun trashedSessions(): List<SessionWithExercises> {
        val sessions = db.query("sessions", null, "trashed_at IS NOT NULL", null, null, null, "trashed_at DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.toSession()) }
        }
        return sessions.map { sessionWithExercises(it) }
    }

    fun sessionFull(id: Long): SessionWithExercises? = session(id)?.let { sessionWithExercises(it) }

    private fun sessionWithExercises(s: Session) =
        SessionWithExercises(s, exercises(s.id).map { ExerciseWithSets(it, sets(it.id)) }, sessionNotes(s.id))

    // ---- exercises (small data blocks) ----

    fun exercises(sessionId: Long): List<Exercise> =
        db.query("exercises", null, "session_id=?", arrayOf(sessionId.toString()), null, null, "position")
            .use { c -> buildList { while (c.moveToNext()) add(c.toExercise()) } }

    fun exercise(id: Long): Exercise? =
        db.query("exercises", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toExercise() else null
        }

    fun createExercise(sessionId: Long, name: String, now: Long): Exercise {
        val position = exercises(sessionId).size
        val id = db.insert("exercises", null, ContentValues().apply {
            put("session_id", sessionId)
            put("name", ExerciseNames.canonical(name))
            put("position", position)
            put("created_at", now)
        })
        changed()
        return exercise(id)!!
    }

    fun renameExercise(id: Long, name: String) {
        db.update("exercises", ContentValues().apply { put("name", ExerciseNames.canonical(name)) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Renames exercises saved under an alias (e.g. "Bench") to the full name ("Bench press"). */
    fun canonicalizeExerciseNames() {
        ExerciseNames.all.forEach { (alias, full) ->
            db.update("exercises", ContentValues().apply { put("name", full) }, "LOWER(TRIM(name)) = ?", arrayOf(alias))
        }
        changed()
    }

    fun touchExercise(id: Long, now: Long) {
        db.execSQL(
            "UPDATE exercises SET first_activity_at = COALESCE(first_activity_at, ?), last_activity_at = ? WHERE id = ?",
            arrayOf<Any>(now, now, id)
        )
        changed()
    }

    /** Marks a workout as about the most progress possible from one workout to the next. */
    fun setExerciseLimitMarked(id: Long, marked: Boolean) {
        db.update("exercises", ContentValues().apply { put("limit_marked", if (marked) 1 else 0) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Sets a workout's fatigue level by hand; null goes back to counting it automatically. */
    fun setExerciseFatigue(id: Long, level: Int?) {
        db.update("exercises", ContentValues().apply {
            if (level == null) putNull("fatigue_manual") else put("fatigue_manual", level)
        }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setExerciseColor(id: Long, color: BlockColor?) {
        db.update("exercises", ContentValues().apply { put("color", color?.name) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setExerciseNote(id: Long, path: String) {
        db.update("exercises", ContentValues().apply { put("note_path", path) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /*
     * Same-name exercises are ordered the way the database shows them: by their big data block's
     * date and time, then by when the exercise was created, then by id. "Previous" and "next"
     * are always relative to the exercise itself, never to today.
     */
    private val sameNameOrdered =
        """SELECT e.* FROM exercises e JOIN sessions s ON s.id = e.session_id
           WHERE LOWER(TRIM(e.name)) = ? AND e.id != ? AND s.trashed_at IS NULL"""

    /** When [ex] was done, as the database places it: its big data block's date and time. */
    fun workoutAt(ex: Exercise): Long = session(ex.sessionId)?.startedAt ?: ex.createdAt

    private fun orderKey(ex: Exercise): Array<String> {
        val at = workoutAt(ex).toString()
        return arrayOf(at, at, ex.createdAt.toString(), ex.createdAt.toString(), ex.id.toString())
    }

    /** The same exercise done just before [ex], across the whole database. */
    fun previousSameExercise(ex: Exercise): Exercise? {
        if (ex.name.isBlank()) return null
        return db.rawQuery(
            """$sameNameOrdered AND (s.started_at < ? OR (s.started_at = ? AND
                 (e.created_at < ? OR (e.created_at = ? AND e.id < ?))))
               ORDER BY s.started_at DESC, e.created_at DESC, e.id DESC LIMIT 1""",
            arrayOf(normalizeName(ex.name), ex.id.toString(), *orderKey(ex)),
        ).use { c -> if (c.moveToFirst()) c.toExercise() else null }
    }

    /** The earliest exercise of every distinct (non-blank) exercise name. */
    fun firstOfEachExercise(): List<Exercise> =
        db.rawQuery(
            """SELECT e.* FROM exercises e JOIN sessions s ON s.id = e.session_id
               WHERE TRIM(e.name) != '' AND s.trashed_at IS NULL AND NOT EXISTS (
                 SELECT 1 FROM exercises o JOIN sessions os ON os.id = o.session_id
                 WHERE LOWER(TRIM(o.name)) = LOWER(TRIM(e.name)) AND os.trashed_at IS NULL AND (os.started_at < s.started_at OR
                   (os.started_at = s.started_at AND (o.created_at < e.created_at OR
                     (o.created_at = e.created_at AND o.id < e.id)))))""",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toExercise()) } }

    /** The same exercise done just after [ex] (its progress colour is measured against [ex]). */
    fun nextSameExercise(ex: Exercise): Exercise? {
        if (ex.name.isBlank()) return null
        return db.rawQuery(
            """$sameNameOrdered AND (s.started_at > ? OR (s.started_at = ? AND
                 (e.created_at > ? OR (e.created_at = ? AND e.id > ?))))
               ORDER BY s.started_at, e.created_at, e.id LIMIT 1""",
            arrayOf(normalizeName(ex.name), ex.id.toString(), *orderKey(ex)),
        ).use { c -> if (c.moveToFirst()) c.toExercise() else null }
    }

    // ---- sets ----

    fun sets(exerciseId: Long): List<WorkSet> =
        db.query("sets", null, "exercise_id=?", arrayOf(exerciseId.toString()), null, null, "kind, number")
            .use { c -> buildList { while (c.moveToNext()) add(c.toSet()) } }

    fun set(id: Long): WorkSet? =
        db.query("sets", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toSet() else null
        }

    fun getOrCreateSet(exerciseId: Long, kind: SetKind, number: Int): WorkSet {
        db.query(
            "sets", null, "exercise_id=? AND kind=? AND number=?",
            arrayOf(exerciseId.toString(), kind.name, number.toString()), null, null, null
        ).use { c -> if (c.moveToFirst()) return c.toSet() }
        val id = db.insert("sets", null, ContentValues().apply {
            put("exercise_id", exerciseId)
            put("kind", kind.name)
            put("number", number)
        })
        changed()
        return set(id)!!
    }

    fun updateSet(s: WorkSet) {
        db.update("sets", ContentValues().apply {
            put("rest_sec", s.restSec)
            put("weight", s.weight)
            put("unit", s.unit?.name)
            put("reps", s.reps)
            put("tut_sec", s.tutSec)
            put("rir_level", s.rirLevel?.name)
            put("rir_value", s.rirValue)
            put("completed_at", s.completedAt)
        }, "id=?", arrayOf(s.id.toString()))
        changed()
    }

    /** Completed sets of a session in completion order: the bubbles on the recording page. */
    fun completedSets(sessionId: Long): List<CompletedSet> =
        db.rawQuery(
            """SELECT s.*, e.name AS ex_name, e.position AS ex_pos FROM sets s
               JOIN exercises e ON e.id = s.exercise_id
               WHERE e.session_id = ? AND s.completed_at IS NOT NULL
               ORDER BY s.completed_at""",
            arrayOf(sessionId.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(c.getColumnIndexOrThrow("ex_name"))
                        .ifBlank { "Exercise ${c.getInt(c.getColumnIndexOrThrow("ex_pos")) + 1}" }
                    add(CompletedSet(c.toSet(), name))
                }
            }
        }

    // ---- skills (Skills database) ----

    fun skills(): List<Skill> =
        db.query("skills", null, null, null, null, null, "position, id").use { c ->
            buildList {
                while (c.moveToNext()) add(Skill(c.long("id")!!, c.str("name")!!, c.int("position")!!, c.long("created_at")!!, c.dbl("capacity")))
            }
        }

    fun createSkill(name: String, capacity: Double, now: Long): Long {
        val position = db.rawQuery("SELECT COALESCE(MAX(position) + 1, 0) FROM skills", null).use { it.moveToFirst(); it.getInt(0) }
        val id = db.insert("skills", null, ContentValues().apply {
            put("name", name)
            put("position", position)
            put("created_at", now)
            put("capacity", capacity)
        })
        changed()
        return id
    }

    fun renameSkill(id: Long, name: String) {
        db.update("skills", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setSkillCapacity(id: Long, capacity: Double) {
        db.update("skills", ContentValues().apply { put("capacity", capacity) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun deleteSkill(id: Long) {
        db.delete("skills", "id=?", arrayOf(id.toString()))
        changed()
    }

    fun skillLogs(skillId: Long): List<SkillLog> =
        db.query("skill_logs", null, "skill_id=?", arrayOf(skillId.toString()), null, null, "logged_at, id")
            .use { c -> buildList { while (c.moveToNext()) add(c.toSkillLog()) } }

    fun addSkillLog(skillId: Long, hours: Double, now: Long): Long {
        val id = db.insert("skill_logs", null, ContentValues().apply {
            put("skill_id", skillId)
            put("hours", hours)
            put("logged_at", now)
        })
        changed()
        return id
    }

    fun deleteSkillLog(id: Long) {
        db.delete("skill_logs", "id=?", arrayOf(id.toString()))
        changed()
    }

    private fun Cursor.toSkillLog() = SkillLog(long("id")!!, long("skill_id")!!, dbl("hours")!!, long("logged_at")!!)

    // ---- cursor mapping ----

    // ---- adding data from a backup file ----

    /** How a backup's data lines up with what's here: [added] new, [replaced] matched, [kept] only here. */
    data class MergeCount(val added: Int, val replaced: Int, val kept: Int)

    /** Big data blocks (matched by start time) and skill logs (matched by skill name and time). */
    data class MergeResult(val blocks: MergeCount, val logs: MergeCount)

    /** Opens a backup's database, bringing it up to this app's version first. */
    private fun <T> openOther(context: Context, other: File, block: (SQLiteDatabase) -> T): T {
        val helper = TutDbHelper(context, other.absolutePath)
        try {
            return block(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }

    private fun longs(d: SQLiteDatabase, sql: String): List<Long> =
        d.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    private fun strings(d: SQLiteDatabase, sql: String): List<String> =
        d.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private val logKey = "SELECT LOWER(TRIM(s.name)) || '|' || l.logged_at FROM skill_logs l JOIN skills s ON s.id = l.skill_id"

    private fun count(here: Collection<Any>, there: Collection<Any>): MergeCount {
        val h = here.toSet()
        val t = there.toSet()
        return MergeCount(added = t.count { it !in h }, replaced = t.count { it in h }, kept = h.count { it !in t })
    }

    private fun preview(b: SQLiteDatabase) = MergeResult(
        count(longs(db, "SELECT started_at FROM sessions"), longs(b, "SELECT started_at FROM sessions")),
        count(strings(db, logKey), strings(b, logKey)),
    )

    /** What [mergeFrom] would do, without changing anything here. */
    fun mergePreview(context: Context, other: File): MergeResult = openOther(context, other) { preview(it) }

    /**
     * Adds everything in the backup database [other] to this one. A big data block with the same
     * start time as one here replaces it (its exercises and sets become the backup's; voice
     * recordings are only ever added). Everything that's only here stays as it is. Voice
     * recordings are copied from [notesFrom] (the backup's) into [notesTo].
     */
    fun mergeFrom(context: Context, other: File, notesFrom: File, notesTo: File): MergeResult = openOther(context, other) { b ->
        val result = preview(b)
        /** The backup's recording copied here; its path here, or null if the backup doesn't have the file. */
        fun note(path: String?): String? {
            val name = path?.let { File(it).name }?.takeIf { it.isNotBlank() } ?: return null
            val from = File(notesFrom, name)
            val to = File(notesTo, name)
            if (!to.isFile) {
                if (!from.isFile) return null
                notesTo.mkdirs()
                from.copyTo(to)
            }
            return to.absolutePath
        }

        val sessionsHere = db.rawQuery("SELECT started_at, id FROM sessions ORDER BY id", null).use { c ->
            buildMap { while (c.moveToNext()) putIfAbsent(c.getLong(0), c.getLong(1)) }
        }
        val exerciseIds = mutableMapOf<Long, Long>() // backup exercise id -> id here
        db.beginTransaction()
        try {
            b.query("sessions", null, null, null, null, null, "id").use { s ->
                while (s.moveToNext()) {
                    val backupId = s.long("id")!!
                    val cv = s.values("id", "note_path")
                    note(s.str("note_path"))?.let { cv.put("note_path", it) } ?: cv.putNull("note_path")
                    val match = sessionsHere[s.long("started_at")!!]
                    // Exercises here before they're replaced, to point locked predictions at the new ones.
                    val before = match?.let { id ->
                        db.rawQuery("SELECT id, LOWER(TRIM(name)) FROM exercises WHERE session_id = ?", arrayOf(id.toString())).use { c ->
                            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
                        }
                    }.orEmpty()
                    val sessionId = if (match != null) {
                        db.update("sessions", cv, "id=?", arrayOf(match.toString()))
                        db.delete("exercises", "session_id=?", arrayOf(match.toString())) // its sets go with them
                        match
                    } else {
                        db.insert("sessions", null, cv)
                    }
                    val added = mutableListOf<Pair<Long, String>>()
                    b.query("exercises", null, "session_id=?", arrayOf(backupId.toString()), null, null, "position").use { e ->
                        while (e.moveToNext()) {
                            val backupEx = e.long("id")!!
                            val ev = e.values("id", "session_id", "note_path")
                            ev.put("session_id", sessionId)
                            note(e.str("note_path"))?.let { ev.put("note_path", it) } ?: ev.putNull("note_path")
                            val exId = db.insert("exercises", null, ev)
                            exerciseIds[backupEx] = exId
                            added += exId to e.str("name").orEmpty().trim().lowercase()
                            b.query("sets", null, "exercise_id=?", arrayOf(backupEx.toString()), null, null, null).use { st ->
                                while (st.moveToNext()) {
                                    val sv = st.values("id", "exercise_id")
                                    sv.put("exercise_id", exId)
                                    db.insert("sets", null, sv)
                                }
                            }
                        }
                    }
                    for ((oldId, name) in before) {
                        val newId = added.firstOrNull { it.second == name }?.first ?: continue
                        db.update(
                            "predictions", ContentValues().apply { put("baseline_exercise_id", newId) },
                            "baseline_exercise_id=?", arrayOf(oldId.toString()),
                        )
                    }
                    b.query("session_notes", null, "session_id=?", arrayOf(backupId.toString()), null, null, "id").use { n ->
                        while (n.moveToNext()) {
                            val path = note(n.str("path")) ?: continue
                            val exists = db.rawQuery(
                                "SELECT 1 FROM session_notes WHERE session_id = ? AND path = ?", arrayOf(sessionId.toString(), path),
                            ).use { it.moveToFirst() }
                            if (!exists) {
                                db.insert("session_notes", null, ContentValues().apply {
                                    put("session_id", sessionId); put("path", path); put("created_at", n.long("created_at")!!)
                                })
                            }
                        }
                    }
                }
            }
            // Locked predictions: matched by exercise name and when they were locked in.
            b.query("predictions", null, null, null, null, null, "id").use { p ->
                while (p.moveToNext()) {
                    val base = exerciseIds[p.long("baseline_exercise_id")!!] ?: continue
                    val pv = p.values("id")
                    pv.put("baseline_exercise_id", base)
                    val match = db.rawQuery(
                        "SELECT id FROM predictions WHERE name = ? AND created_at = ?",
                        arrayOf(p.str("name").orEmpty(), p.long("created_at").toString()),
                    ).use { if (it.moveToFirst()) it.getLong(0) else null }
                    if (match != null) db.update("predictions", pv, "id=?", arrayOf(match.toString())) else db.insert("predictions", null, pv)
                }
            }
            // Skills by name; their logs by skill and time.
            b.query("skills", null, null, null, null, null, "position").use { sk ->
                while (sk.moveToNext()) {
                    val name = sk.str("name").orEmpty()
                    val here = db.rawQuery("SELECT id FROM skills WHERE LOWER(TRIM(name)) = ?", arrayOf(name.trim().lowercase()))
                        .use { if (it.moveToFirst()) it.getLong(0) else null }
                    val skillId = if (here != null) {
                        sk.dbl("capacity")?.let { cap ->
                            db.update("skills", ContentValues().apply { put("capacity", cap) }, "id=?", arrayOf(here.toString()))
                        }
                        here
                    } else {
                        val next = db.rawQuery("SELECT COALESCE(MAX(position) + 1, 0) FROM skills", null).use { it.moveToFirst(); it.getInt(0) }
                        db.insert("skills", null, sk.values("id").apply { put("position", next) })
                    }
                    b.query("skill_logs", null, "skill_id=?", arrayOf(sk.long("id").toString()), null, null, "id").use { l ->
                        while (l.moveToNext()) {
                            val at = l.long("logged_at")!!
                            val log = db.rawQuery(
                                "SELECT id FROM skill_logs WHERE skill_id = ? AND logged_at = ?", arrayOf(skillId.toString(), at.toString()),
                            ).use { if (it.moveToFirst()) it.getLong(0) else null }
                            val lv = ContentValues().apply { put("skill_id", skillId); put("hours", l.dbl("hours")!!); put("logged_at", at) }
                            if (log != null) db.update("skill_logs", lv, "id=?", arrayOf(log.toString())) else db.insert("skill_logs", null, lv)
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
        result
    }

    /** Every column of the current row except [skip], with its value as stored. */
    private fun Cursor.values(vararg skip: String): ContentValues = ContentValues().also { cv ->
        for (i in 0 until columnCount) {
            val n = getColumnName(i)
            if (n in skip) continue
            when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> cv.putNull(n)
                Cursor.FIELD_TYPE_INTEGER -> cv.put(n, getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> cv.put(n, getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> cv.put(n, getBlob(i))
                else -> cv.put(n, getString(i))
            }
        }
    }

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(col: String): Int? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.dbl(col: String): Double? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }

    private fun Cursor.toSession() = Session(
        id = long("id")!!,
        startedAt = long("started_at")!!,
        color = str("color")?.let { BlockColor.valueOf(it) },
        colorManual = int("color_manual") == 1,
        saved = int("saved") == 1,
        notePath = str("note_path"),
        recordedMs = long("recorded_ms"),
        excluded = int("excluded") == 1,
        trashedAt = long("trashed_at"),
        sleepHours = dbl("sleep_hours"),
        recovery = str("recovery")?.let { v -> RecoveryFeeling.entries.firstOrNull { it.name == v } },
    )

    private fun Cursor.toExercise() = Exercise(
        id = long("id")!!,
        sessionId = long("session_id")!!,
        name = str("name")!!,
        position = int("position")!!,
        createdAt = long("created_at")!!,
        firstActivityAt = long("first_activity_at"),
        lastActivityAt = long("last_activity_at"),
        color = str("color")?.let { BlockColor.valueOf(it) },
        notePath = str("note_path"),
        limitMarked = int("limit_marked") == 1,
        fatigueManual = int("fatigue_manual"),
    )

    private fun Cursor.toSet() = WorkSet(
        id = long("id")!!,
        exerciseId = long("exercise_id")!!,
        kind = SetKind.valueOf(str("kind")!!),
        number = int("number")!!,
        restSec = int("rest_sec"),
        weight = dbl("weight"),
        unit = str("unit")?.let { WeightUnit.valueOf(it) },
        reps = int("reps"),
        tutSec = int("tut_sec"),
        rirLevel = str("rir_level")?.let { RirLevel.valueOf(it) },
        rirValue = int("rir_value"),
        completedAt = long("completed_at"),
    )

    // ---- predictions ----

    /**
     * Every named exercise with its sets and when it was done, for the prediction model. Blocks
     * excluded from predictions are left out, as if they had never been done.
     */
    fun allWorkouts(): List<Workout> {
        val sets = db.query("sets", null, null, null, null, null, "exercise_id, kind, number").use { c ->
            buildList { while (c.moveToNext()) add(c.toSet()) }
        }.groupBy { it.exerciseId }
        return db.rawQuery(
            "SELECT e.id, e.name, s.started_at, e.limit_marked, e.fatigue_manual FROM exercises e JOIN sessions s ON s.id = e.session_id " +
                "WHERE TRIM(e.name) != '' AND s.excluded = 0 AND s.trashed_at IS NULL",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(
                        Workout(
                            id, c.getString(1), c.getLong(2), sets[id].orEmpty(), limitMarked = c.getInt(3) == 1,
                            fatigueManual = if (c.isNull(4)) null else c.getInt(4),
                        )
                    )
                }
            }
        }
    }

    fun predictions(): List<LockedPrediction> =
        db.query("predictions", null, null, null, null, null, "created_at DESC, id DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.toPrediction()) }
        }

    fun addPrediction(p: LockedPrediction): Long {
        val id = db.insert("predictions", null, ContentValues().apply {
            put("name", p.name)
            put("created_at", p.createdAt)
            put("planned_for", p.plannedFor)
            put("baseline_exercise_id", p.baselineExerciseId)
            put("baseline_at", p.baselineAt)
            put("baseline_strength", p.baselineStrength)
            put("plan", planJson(p.plan))
            put("predicted_pct", p.predictedPct)
            put("curve", JSONArray().apply { p.curve.forEach { put(JSONArray().put(it.hours).put(it.pct)) } }.toString())
            put("trained_on", p.trainedOn)
            p.typicalMiss?.let { put("typical_miss", it) }
            put("source", p.source)
        })
        changed()
        return id
    }

    fun setPredictionVerdict(id: Long, accurate: Boolean?) {
        db.update("predictions", ContentValues().apply {
            if (accurate == null) putNull("verdict") else put("verdict", if (accurate) 1 else 0)
        }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun deletePrediction(id: Long) {
        db.delete("predictions", "id=?", arrayOf(id.toString()))
        changed()
    }

    private fun planJson(p: PlannedWorkout) = JSONObject()
        .put("weight", p.weight).put("unit", p.unit.name)
        .put("workingSets", p.workingSets).put("warmupSets", p.warmupSets)
        .put("restSec", p.restSec ?: JSONObject.NULL).put("rir", p.rir ?: JSONObject.NULL)
        .put("timed", p.timed)
        .put("tutSec", p.tutSec ?: JSONObject.NULL)
        .toString()

    private fun Cursor.toPrediction(): LockedPrediction {
        val plan = JSONObject(str("plan")!!)
        val curve = JSONArray(str("curve")!!)
        return LockedPrediction(
            id = long("id")!!,
            name = str("name")!!,
            createdAt = long("created_at")!!,
            plannedFor = long("planned_for")!!,
            baselineExerciseId = long("baseline_exercise_id")!!,
            baselineAt = long("baseline_at")!!,
            baselineStrength = dbl("baseline_strength")!!,
            plan = PlannedWorkout(
                weight = plan.getDouble("weight"),
                unit = runCatching { WeightUnit.valueOf(plan.getString("unit")) }.getOrDefault(WeightUnit.LBS),
                workingSets = plan.getInt("workingSets"),
                warmupSets = plan.getInt("warmupSets"),
                restSec = if (plan.isNull("restSec")) null else plan.getInt("restSec"),
                rir = if (plan.isNull("rir")) null else plan.getInt("rir"),
                timed = plan.optBoolean("timed"),
                tutSec = if (plan.isNull("tutSec")) null else plan.optInt("tutSec"),
            ),
            predictedPct = dbl("predicted_pct")!!,
            curve = List(curve.length()) { i -> curve.getJSONArray(i).let { CurvePoint(it.getDouble(0), it.getDouble(1)) } },
            trainedOn = int("trained_on")!!,
            typicalMiss = dbl("typical_miss"),
            verdict = int("verdict")?.let { it == 1 },
            source = str("source")!!,
        )
    }

    companion object {
        fun normalizeName(name: String) = name.trim().lowercase()
    }
}
