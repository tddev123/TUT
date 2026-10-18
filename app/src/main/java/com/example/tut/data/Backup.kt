package com.example.tut.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.tut.DbMode
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Saving all data to one file, and putting it back. The file is a zip laid out like the app's
 * own storage: databases/tut.db and databases/skills.db (every big data block, exercise, set,
 * skill, log and locked prediction), notes/ and notes_skills/ (voice recordings),
 * shared_prefs/tut_settings.xml (settings), and manifest.json.
 *
 * Adding a file's data: [stage] unpacks and checks the file, [preview] says what it would add
 * and replace, and [merge] adds it to what's here. What was there before is kept in
 * before_restore/ for [stageUndo]. Putting that back replaces everything, which can't be done
 * under an open database: [confirm] marks it ready, and the next app start ([applyPending])
 * swaps it in before anything opens.
 */
object Backup {
    private const val FORMAT = 1
    private const val PREFS = "tut_settings"
    private const val PENDING = "restore_pending"
    private const val BEFORE = "before_restore"
    private const val READY = "ready"
    private const val TAG = "TutBackup"

    /** What a backup holds, for showing before a restore. */
    data class Summary(val createdAt: Long?, val blocks: Int, val skillLogs: Int, val recordings: Int, val settings: Boolean)

    /** Writes everything to [out]. [snapshot] copies one database consistently into the given file. */
    fun write(context: Context, snapshot: (DbMode, File) -> Unit, out: OutputStream): Summary {
        val tmp = File(context.cacheDir, "backup").apply { deleteRecursively(); mkdirs() }
        try {
            DbMode.entries.forEach { snapshot(it, File(tmp, it.fileName)) }
            ZipOutputStream(out.buffered()).use { zip ->
                fun put(name: String, file: File) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val manifest = JSONObject()
                    .put("format", FORMAT).put("app", "TUT").put("createdAt", System.currentTimeMillis())
                    .put("filesDir", context.filesDir.absolutePath)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray())
                zip.closeEntry()
                DbMode.entries.forEach { put("databases/${it.fileName}", File(tmp, it.fileName)) }
                DbMode.entries.forEach { mode ->
                    File(context.filesDir, mode.notesFolder).listFiles()?.filter { it.isFile }?.forEach { put("${mode.notesFolder}/${it.name}", it) }
                }
                prefsFile(context).takeIf { it.isFile }?.let { put("shared_prefs/$PREFS.xml", it) }
            }
            return summarize(tmp, DbMode.entries.sumOf { File(context.filesDir, it.notesFolder).listFiles()?.count { f -> f.isFile } ?: 0 }, null, prefsFile(context).isFile)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Unpacks a backup into the waiting area and checks it. Throws with a message saying what's
     * wrong if it isn't a TUT backup or comes from a newer version of the app.
     */
    fun stage(context: Context, input: InputStream, currentDbVersion: Int): Summary {
        val dir = pendingDir(context).apply { deleteRecursively(); mkdirs() }
        try {
            val root = dir.canonicalPath + File.separator
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (!allowed.matches(e.name)) continue
                    val target = File(dir, e.name)
                    if (!target.canonicalPath.startsWith(root)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            val manifest = File(dir, "manifest.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
                ?: error("This isn't a TUT backup file.")
            if (manifest.optInt("format") > FORMAT) error("This backup was made by a newer version of the app. Update the app first.")
            val dbs = File(dir, "databases")
            if (!File(dbs, DbMode.TUT.fileName).isFile) error("The backup has no workout database in it.")
            DbMode.entries.map { File(dbs, it.fileName) }.filter { it.isFile }.forEach { f ->
                val v = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
                if (v > currentDbVersion) error("This backup was made by a newer version of the app. Update the app first.")
            }
            val recordings = DbMode.entries.sumOf { File(dir, it.notesFolder).listFiles()?.count { f -> f.isFile } ?: 0 }
            return summarize(dbs, recordings, manifest.optLong("createdAt").takeIf { it > 0 }, File(dir, "shared_prefs/$PREFS.xml").isFile)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** What adding the staged backup would do to each database, without changing anything. */
    fun preview(context: Context, repoFor: (DbMode) -> Repository): Repository.MergeResult =
        staged(context).map { (mode, file) -> repoFor(mode).mergePreview(context, file) }.reduce(::plus)

    /**
     * Adds the staged backup's data to what's here ([Repository.mergeFrom]): nothing that's only
     * here is lost. First keeps a copy of everything as it is now, so it can be undone.
     */
    fun merge(context: Context, repoFor: (DbMode) -> Repository): Repository.MergeResult {
        try {
            val before = File(context.filesDir, BEFORE)
            val keep = File(context.filesDir, "$BEFORE.new").apply { deleteRecursively(); mkdirs() }
            DbMode.entries.forEach { mode ->
                repoFor(mode).copyTo(File(keep, "databases/${mode.fileName}").apply { parentFile?.mkdirs() })
                File(context.filesDir, mode.notesFolder).takeIf { it.isDirectory }?.copyRecursively(File(keep, mode.notesFolder), overwrite = true)
            }
            prefsFile(context).takeIf { it.isFile }?.copyTo(File(keep, "shared_prefs/$PREFS.xml"), overwrite = true)
            before.deleteRecursively()
            keep.renameTo(before)
            val dir = pendingDir(context)
            return staged(context).map { (mode, file) ->
                repoFor(mode).mergeFrom(context, file, File(dir, mode.notesFolder), File(context.filesDir, mode.notesFolder))
            }.reduce(::plus)
        } finally {
            cancel(context)
        }
    }

    private fun staged(context: Context): List<Pair<DbMode, File>> =
        DbMode.entries.map { it to File(pendingDir(context), "databases/${it.fileName}") }.filter { it.second.isFile }

    private fun plus(a: Repository.MergeResult, b: Repository.MergeResult) = Repository.MergeResult(
        Repository.MergeCount(a.blocks.added + b.blocks.added, a.blocks.replaced + b.blocks.replaced, a.blocks.kept + b.blocks.kept),
        Repository.MergeCount(a.logs.added + b.logs.added, a.logs.replaced + b.logs.replaced, a.logs.kept + b.logs.kept),
    )

    /** The staged backup goes in at the next app start. */
    fun confirm(context: Context) {
        File(pendingDir(context), READY).createNewFile()
    }

    fun cancel(context: Context) {
        pendingDir(context).deleteRecursively()
    }

    /** Whether there's data from before the last restore to go back to. */
    fun canUndo(context: Context): Boolean = File(File(context.filesDir, BEFORE), "databases/${DbMode.TUT.fileName}").isFile

    /** Stages the data from before the last restore, to be put back like a backup. */
    fun stageUndo(context: Context): Summary {
        val before = File(context.filesDir, BEFORE)
        val dir = pendingDir(context).apply { deleteRecursively() }
        before.copyRecursively(dir, overwrite = true)
        File(dir, "manifest.json").writeText(JSONObject().put("format", FORMAT).put("filesDir", context.filesDir.absolutePath).toString())
        val recordings = DbMode.entries.sumOf { File(dir, it.notesFolder).listFiles()?.count { f -> f.isFile } ?: 0 }
        return summarize(File(dir, "databases"), recordings, before.lastModified(), File(dir, "shared_prefs/$PREFS.xml").isFile)
    }

    /**
     * Run at app start before any database or settings are opened: swaps in a confirmed backup.
     * The data it replaces goes to before_restore/; if anything fails, that data is put back.
     */
    fun applyPending(context: Context) {
        val dir = pendingDir(context)
        if (!dir.exists()) return
        if (!File(dir, READY).isFile) { dir.deleteRecursively(); return } // never confirmed
        val before = File(context.filesDir, BEFORE)
        val keep = File(context.filesDir, "$BEFORE.new").apply { deleteRecursively(); mkdirs() }
        try {
            saveState(context, keep)
            val oldFilesDir = runCatching { JSONObject(File(dir, "manifest.json").readText()).optString("filesDir") }.getOrNull()
            putState(context, dir)
            if (!oldFilesDir.isNullOrBlank() && oldFilesDir != context.filesDir.absolutePath) {
                fixNotePaths(context, oldFilesDir, context.filesDir.absolutePath)
            }
            before.deleteRecursively()
            keep.renameTo(before)
        } catch (e: Exception) {
            Log.e(TAG, "restore failed, putting the old data back", e)
            runCatching { putState(context, keep) }
            keep.deleteRecursively()
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- internals

    private val allowed = Regex(
        """^(?:manifest\.json|databases/(?:${DbMode.entries.joinToString("|") { Regex.escape(it.fileName) }})|""" +
            """(?:${DbMode.entries.joinToString("|") { it.notesFolder }})/[A-Za-z0-9_][A-Za-z0-9._ -]*|shared_prefs/$PREFS\.xml)$"""
    )

    private fun pendingDir(context: Context) = File(context.filesDir, PENDING)

    private fun prefsFile(context: Context) = File(context.dataDir, "shared_prefs/$PREFS.xml")

    private fun dbFiles(context: Context, mode: DbMode): List<File> {
        val db = context.getDatabasePath(mode.fileName)
        return listOf(db, File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"))
    }

    /** Copies the app's current data into [to], laid out like a backup. Databases aren't open yet. */
    private fun saveState(context: Context, to: File) {
        DbMode.entries.forEach { mode ->
            dbFiles(context, mode).filter { it.isFile }.forEach { it.copyTo(File(to, "databases/${it.name}"), overwrite = true) }
            File(context.filesDir, mode.notesFolder).takeIf { it.isDirectory }?.copyRecursively(File(to, mode.notesFolder), overwrite = true)
        }
        prefsFile(context).takeIf { it.isFile }?.copyTo(File(to, "shared_prefs/$PREFS.xml"), overwrite = true)
    }

    /** Replaces the app's data with what's in [from]. A database it doesn't have is left as it is. */
    private fun putState(context: Context, from: File) {
        DbMode.entries.forEach { mode ->
            val db = File(from, "databases/${mode.fileName}")
            if (!db.isFile) return@forEach
            dbFiles(context, mode).forEach { it.delete() }
            val target = context.getDatabasePath(mode.fileName)
            target.parentFile?.mkdirs()
            db.copyTo(target, overwrite = true)
            // Kept alongside when the old data is put back after a failed restore.
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(from, "databases/${mode.fileName}$suffix").takeIf { it.isFile }?.copyTo(File(target.path + suffix), overwrite = true)
            }
            val notes = File(context.filesDir, mode.notesFolder)
            notes.deleteRecursively()
            notes.mkdirs()
            File(from, mode.notesFolder).takeIf { it.isDirectory }?.copyRecursively(notes, overwrite = true)
        }
        File(from, "shared_prefs/$PREFS.xml").takeIf { it.isFile }?.let { src ->
            val target = prefsFile(context)
            target.parentFile?.mkdirs()
            src.copyTo(target, overwrite = true)
        }
    }

    /** Recordings are stored by full path: point them at this phone's folder. */
    private fun fixNotePaths(context: Context, old: String, new: String) {
        DbMode.entries.forEach { mode ->
            val f = context.getDatabasePath(mode.fileName)
            if (!f.isFile) return@forEach
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                listOf("session_notes" to "path", "sessions" to "note_path", "exercises" to "note_path").forEach { (table, col) ->
                    runCatching { db.execSQL("UPDATE $table SET $col = REPLACE($col, ?, ?) WHERE $col IS NOT NULL", arrayOf(old, new)) }
                }
            }
        }
    }

    private fun summarize(dbs: File, recordings: Int, createdAt: Long?, settings: Boolean): Summary {
        fun count(file: File, vararg queries: String): Int {
            if (!file.isFile) return 0
            return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                queries.firstNotNullOfOrNull { q -> runCatching { db.rawQuery(q, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } }.getOrNull() } ?: 0
            }
        }
        // Older backups have no trash column: count every block.
        val blocks = count(File(dbs, DbMode.TUT.fileName), "SELECT COUNT(*) FROM sessions WHERE trashed_at IS NULL", "SELECT COUNT(*) FROM sessions")
        val logs = count(File(dbs, DbMode.SKILLS.fileName), "SELECT COUNT(*) FROM skill_logs")
        return Summary(createdAt, blocks, logs, recordings, settings)
    }
}
