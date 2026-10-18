package com.example.tut.data

/** The numbers of one workout of an exercise that the bulletin compares. */
data class WorkoutSummary(
    val at: Long,
    val topWeight: Double?,
    val workingSets: Int,
    val avgReps: Double?,
    val avgTutSec: Double?,
    val avgRir: Double?,
)

/**
 * Shown on the adaptation curve when this workout made no progress (same or weaker): the three
 * workouts in a row that led to it, as the database orders them. [progressed] is two before this
 * one, [stalled] the one right before, [current] this one; [progressedColor] / [stalledColor] are
 * how those two did against their own previous workout. Each rest is the hours since the same
 * exercise before that workout (null for the very first one).
 */
data class Bulletin(
    val progressed: WorkoutSummary,
    val stalled: WorkoutSummary,
    val current: WorkoutSummary,
    val restBeforeProgressed: Double?,
    val restBeforeStalled: Double,
    val restBeforeCurrent: Double,
    val progressedColor: BlockColor? = null,
    val stalledColor: BlockColor? = BlockColor.GREEN,
)

object Bulletins {
    fun summarize(at: Long, sets: List<WorkSet>, s: AppSettings): WorkoutSummary {
        val working = sets.filter { it.kind == SetKind.WORKING && it.hasData }
        val rir = working.mapNotNull { set ->
            set.rirValue ?: when (set.rirLevel) {
                RirLevel.HIGH -> s.rirHigh
                RirLevel.MODERATE -> s.rirModerate
                RirLevel.LOW -> s.rirLow
                null -> null
            }
        }
        return WorkoutSummary(
            at = at,
            topWeight = Progress.topWorkingSet(sets, s.weightUnit)?.let { Progress.weightIn(it, s.weightUnit) },
            workingSets = working.size,
            avgReps = working.mapNotNull { it.reps }.takeIf { it.isNotEmpty() }?.average(),
            avgTutSec = working.mapNotNull { it.tutSec }.takeIf { it.isNotEmpty() }?.average(),
            avgRir = rir.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    /**
     * The bulletin for [ex], or null unless it made no progress (same or weaker) and has two
     * earlier workouts to compare with. Always the two right before it, never further back.
     * Blocking.
     */
    fun forExercise(repo: Repository, ex: Exercise, s: AppSettings): Bulletin? {
        if (Progress.improved(ex.color) || ex.color == null) return null
        val stalled = repo.previousSameExercise(ex) ?: return null
        val progressed = repo.previousSameExercise(stalled) ?: return null
        return Bulletin(
            progressed = summarize(repo.workoutAt(progressed), repo.sets(progressed.id), s),
            stalled = summarize(repo.workoutAt(stalled), repo.sets(stalled.id), s),
            current = summarize(repo.workoutAt(ex), repo.sets(ex.id), s),
            restBeforeProgressed = repo.previousSameExercise(progressed)?.let { (repo.workoutAt(progressed) - repo.workoutAt(it)) / 3_600_000.0 },
            restBeforeStalled = (repo.workoutAt(stalled) - repo.workoutAt(progressed)) / 3_600_000.0,
            restBeforeCurrent = (repo.workoutAt(ex) - repo.workoutAt(stalled)) / 3_600_000.0,
            progressedColor = progressed.color,
            stalledColor = stalled.color,
        )
    }
}
