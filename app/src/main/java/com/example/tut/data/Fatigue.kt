package com.example.tut.data

import kotlin.math.abs

/**
 * The fatigue scoreboard: how much fatigue has built up by each workout of an exercise.
 *
 * Going through an exercise's workouts in order, each one is compared with the one before it:
 * - worse by more than [AppSettings.fatigueSameSec]: one level more, or two when it dropped by
 *   [AppSettings.fatigueBigDropSec] or more of time under tension, or the weight went down by
 *   [AppSettings.fatigueBigDropWeight] or more;
 * - about the same (within [AppSettings.fatigueSameSec]): one level less when it's the same or a
 *   little better (8.5 lb × 2:00, then × 2:10), the level stays when it's a little worse (× 1:40);
 * - better by more than that: back to 0, the fatigue has cleared.
 * A level set by hand on a workout replaces the automatic one, and the workouts after it count on
 * from there. The first workout of an exercise starts at 0.
 *
 * Time under tension is measured on the exercise's ladder ([ProgressScale.Tut]): at the same
 * weight that's simply the time; a weight change counts as its learned worth in time. Exercises
 * measured by reps use their stronger / same / weaker colour instead.
 */
object Fatigue {
    /** A workout's fatigue level, and whether it was set by hand. */
    data class Level(val level: Int, val manual: Boolean)

    /** Levels by exercise id for every workout in [workouts] (all of one exercise). */
    fun levels(workouts: List<Workout>, scale: ProgressScale, s: AppSettings): Map<Long, Level> {
        val out = mutableMapOf<Long, Level>()
        var level = 0
        var prev: WorkSet? = null
        for (w in workouts.sortedWith(compareBy({ it.at }, { it.exerciseId }))) {
            val best = scale.bestSet(w.sets)
            val p = prev
            val auto = if (p == null || best == null) level else level + step(p, best, scale, s)
            level = w.fatigueManual ?: auto.coerceAtLeast(0)
            out[w.exerciseId] = Level(level, w.fatigueManual != null)
            if (best != null) prev = best
        }
        return out
    }

    /** Levels for every workout of every exercise, by exercise id. */
    fun all(workouts: List<Workout>, s: AppSettings): Map<Long, Level> =
        workouts.filter { it.name.isNotBlank() }.groupBy { it.key }.values.flatMap { list ->
            levels(list, Predictions.scaleFor(list.first().name, list, s), s).entries
        }.associate { it.key to it.value }

    /**
     * What one workout does to the level, compared with the one before: +1 or +2 worse, 0 or -1
     * about the same, or a large negative number for better (which brings the level back to 0).
     */
    private fun step(before: WorkSet, after: WorkSet, scale: ProgressScale, s: AppSettings): Int {
        val weightDrop = weight(before, s) - weight(after, s)
        val bigWeightDrop = s.fatigueBigDropWeight > 0 && weightDrop >= s.fatigueBigDropWeight - 1e-9
        return when (scale) {
            is ProgressScale.Tut -> {
                val delta = scale.score(after) - scale.score(before) // seconds of time under tension
                when {
                    abs(delta) <= s.fatigueSameSec -> if (delta >= 0) -1 else 0
                    delta > 0 -> CLEARED
                    -delta >= s.fatigueBigDropSec || bigWeightDrop -> 2
                    else -> 1
                }
            }
            is ProgressScale.Reps -> when (Progress.compare(listOf(before), listOf(after), s)) {
                BlockColor.RED -> if (bigWeightDrop) 2 else 1
                BlockColor.GREEN -> CLEARED
                else -> if (scale.score(after) >= scale.score(before)) -1 else 0
            }
        }
    }

    private const val CLEARED = -1_000

    private fun weight(set: WorkSet, s: AppSettings) = if (set.weight == null) 0.0 else Progress.weightIn(set, s.weightUnit)
}
