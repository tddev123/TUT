package com.example.tut.data

/** One workout of a stronger streak, with the hours of rest before it (null if none came before). */
data class StreakWorkout(val summary: WorkoutSummary, val restBeforeHours: Double?)

/**
 * A run of stronger workouts in a row (gold). [workouts] starts with the workout the streak grew
 * from, then each stronger one, oldest first.
 */
data class StreakBulletin(val workouts: List<StreakWorkout>) {
    val stronger: List<StreakWorkout> get() = workouts.drop(1)
    val length: Int get() = workouts.size - 1
}

/** How one value behaved across the stronger workouts. */
enum class Pattern(val note: String) {
    SAME("same every time"),
    STEADY("about the same"),
    UP("up every time"),
    DOWN("down every time"),
}

object Streaks {
    /** Longest streak looked back over; more would not fit on a phone anyway. */
    private const val MAX = 30

    /** The streak [ex] is on, or null unless it is gold (stronger at least twice in a row). Blocking. */
    fun forExercise(repo: Repository, ex: Exercise, s: AppSettings): StreakBulletin? {
        if (ex.color?.isGold != true) return null
        val chain = mutableListOf(ex)
        var cur = ex
        while (Progress.improved(cur.color) && chain.size <= MAX) {
            cur = repo.previousSameExercise(cur) ?: break
            chain.add(cur)
        }
        if (chain.size < 3) return null
        return StreakBulletin(chain.reversed().map { w ->
            StreakWorkout(
                Bulletins.summarize(repo.workoutAt(w), repo.sets(w.id), s),
                repo.previousSameExercise(w)?.let { (repo.workoutAt(w) - repo.workoutAt(it)) / 3_600_000.0 },
            )
        })
    }

    /**
     * What [values] (one per stronger workout) have in common: identical, within [tolerance]
     * of each other, or moving the same way every time. Null if any is missing or they vary.
     */
    fun pattern(values: List<Double?>, tolerance: Double): Pattern? {
        if (values.size < 2 || values.any { it == null }) return null
        val v = values.map { Math.round(it!! * 10) / 10.0 }
        return when {
            v.distinct().size == 1 -> Pattern.SAME
            v.max() - v.min() <= tolerance + 1e-9 -> Pattern.STEADY
            v.zipWithNext().all { (a, b) -> b > a } -> Pattern.UP
            v.zipWithNext().all { (a, b) -> b < a } -> Pattern.DOWN
            else -> null
        }
    }
}
