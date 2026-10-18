package com.example.tut.data

import kotlin.math.abs

/** Data changes shared by the recording page and the data page. Blocking: call from Dispatchers.IO. */
class DataOps(private val repo: Repository, private val settings: SettingsStore) {

    /**
     * Saves a set and refreshes progress colours. A set is marked finished (completedAt) by
     * saying "done" on the recording page; that is left as the caller passes it.
     */
    fun saveSet(set: WorkSet, now: Long = System.currentTimeMillis(), touch: Boolean = true): WorkSet {
        repo.updateSet(set)
        if (touch) repo.touchExercise(set.exerciseId, now)
        refreshColors(set.exerciseId)
        return set
    }

    /**
     * Re-evaluates the progress colour of an exercise and of every later one with the same
     * name: each is compared against the one before it, and a gold streak can start or end.
     */
    fun refreshColors(exerciseId: Long) {
        var ex: Exercise? = repo.exercise(exerciseId) ?: return
        var guard = 0
        while (ex != null && guard++ < 10_000) {
            updateExerciseColor(ex)
            refreshSessionColor(ex.sessionId)
            ex = repo.nextSameExercise(ex)
        }
    }

    /**
     * Renames an exercise. It leaves one chain of same-name workouts and joins another, so both
     * are recoloured: the one after it under the old name, and itself onward under the new name.
     */
    fun renameExercise(id: Long, name: String) {
        val ex = repo.exercise(id) ?: return
        val oldNext = repo.nextSameExercise(ex)
        repo.renameExercise(id, name)
        refreshColors(id)
        oldNext?.let { refreshColors(it.id) }
    }

    /**
     * Puts a big data block in the trash. The workouts that came after its exercises are now
     * compared with an earlier one, so they're recoloured.
     */
    fun trashSession(id: Long, now: Long = System.currentTimeMillis()) {
        val after = repo.exercises(id).mapNotNull { repo.nextSameExercise(it) }
        repo.trashSession(id, now)
        after.forEach { refreshColors(it.id) }
    }

    /** Takes a big data block out of the trash and recolours it and what came after it. */
    fun restoreSession(id: Long) {
        repo.restoreSession(id)
        repo.exercises(id).forEach { refreshColors(it.id) }
    }

    /** Recolours every exercise from its first time on (e.g. so existing streaks turn gold). */
    fun recolorAll() {
        repo.firstOfEachExercise().forEach { refreshColors(it.id) }
    }

    private fun updateExerciseColor(ex: Exercise) {
        val prev = repo.previousSameExercise(ex)
        var color = prev?.let { Progress.compare(repo.sets(it.id), repo.sets(ex.id), settings.current) }
        // Stronger again right after a stronger one climbs the gold streak; same or weaker ends it.
        if (color == BlockColor.GREEN && prev != null && Progress.improved(prev.color)) color = prev.color!!.nextInStreak()
        // Weaker again right after a weaker one darkens: red -> dark red -> black (overtraining).
        if (color == BlockColor.RED && prev?.color?.isWeaker == true) color = prev.color.nextWeaker()
        if (color != ex.color) repo.setExerciseColor(ex.id, color)
    }

    /** Big block colour follows its exercises, unless the user picked one by hand. */
    fun refreshSessionColor(sessionId: Long) {
        val session = repo.session(sessionId) ?: return
        if (session.colorManual) return
        val color = Progress.aggregate(repo.exercises(sessionId).map { it.color })
        if (color != session.color) repo.setSessionColor(sessionId, color, manual = false)
    }

    fun levelFor(value: Int): RirLevel {
        val s = settings.current
        return listOf(RirLevel.HIGH to s.rirHigh, RirLevel.MODERATE to s.rirModerate, RirLevel.LOW to s.rirLow)
            .minBy { abs(it.second - value) }.first
    }

    fun valueFor(level: RirLevel): Int = when (level) {
        RirLevel.HIGH -> settings.current.rirHigh
        RirLevel.MODERATE -> settings.current.rirModerate
        RirLevel.LOW -> settings.current.rirLow
    }
}
