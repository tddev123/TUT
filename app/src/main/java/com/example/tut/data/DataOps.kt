package com.example.tut.data

import kotlin.math.abs
import kotlin.math.roundToInt

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
        val scale = setBySetScale(ex!!.name)
        var guard = 0
        while (ex != null && guard++ < 10_000) {
            updateExerciseColor(ex, scale)
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

    /** Sets how recovered a workout felt and recolours it (set by set, the rating can change its colour). */
    fun setRecovery(exerciseId: Long, rating: RecoveryRating?) {
        repo.setExerciseRecovery(exerciseId, rating)
        refreshColors(exerciseId)
    }

    /** Recolours every exercise from its first time on (e.g. so existing streaks turn gold). */
    fun recolorAll() {
        repo.firstOfEachExercise().forEach { refreshColors(it.id) }
    }

    /** The scale to compare [name] set by set with; null when it compares best sets ([Progress.compare]). */
    private fun setBySetScale(name: String): ProgressScale? {
        val s = settings.current
        if (!s.comparesAllSets(name)) return null
        val same = repo.allWorkouts().filter { Predictions.key(it.name) == Predictions.key(name) }
        return Predictions.scaleFor(name, same, s)
    }

    /**
     * Set by set, the colour follows the recovery score after the rating: under 50% weaker (red),
     * over 50% stronger (green), 50% the same (gray). Rounded as shown, so "50%" is always gray.
     */
    private fun setBySetColor(prev: Exercise, ex: Exercise, scale: ProgressScale): BlockColor? {
        val c = scale.compareSets(repo.sets(prev.id), repo.sets(ex.id), ex.recoveryRating) ?: return null
        val pct = c.pct.roundToInt()
        return if (pct < 50) BlockColor.RED else if (pct > 50) BlockColor.GREEN else BlockColor.GRAY
    }

    private fun updateExerciseColor(ex: Exercise, scale: ProgressScale?) {
        val prev = repo.previousSameExercise(ex)
        var color = prev?.let {
            if (scale != null) setBySetColor(it, ex, scale) else Progress.compare(repo.sets(it.id), repo.sets(ex.id), settings.current)
        }
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
