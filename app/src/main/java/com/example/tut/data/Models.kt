package com.example.tut.data

enum class SetKind(val label: String, val spoken: String) {
    WARMUP("Warm-up set", "warm up set"),
    WORKING("Working set", "working set"),
}

enum class RirLevel(val label: String) { HIGH("High"), MODERATE("Moderate"), LOW("Low") }

/**
 * How recovered the lifter feels, said by voice ("recovery feeling fresh"). Only for reading
 * back: it is never given to the prediction model.
 */
enum class RecoveryFeeling(val label: String) {
    VERY_FRESH("Very fresh"), FRESH("Fresh"), CANT_TELL("Can't tell"), KIND_OF_SORE("Kind of sore"), SORE("Sore"),
}

/**
 * How recovered one exercise felt, picked by hand inside the exercise. [pct] is where it sits on
 * the set-by-set recovery score (50% = same as last time); "can't tell" has none and changes nothing.
 * Only a hint: the clearer the sets are, the less it counts ([ProgressScale.compareSets]).
 */
enum class RecoveryRating(val label: String, val pct: Double?) {
    SUPER_FATIGUED("Super fatigued", 10.0), FATIGUED("Fatigued", 20.0), KIND_OF_FATIGUED("Kind of fatigued", 35.0),
    CANT_TELL("Can't tell", null),
    KIND_OF_RECOVERED("Kind of recovered", 60.0),
    RECOVERED("Recovered", 70.0), DEFINITELY_RECOVERED("Definitely recovered", 80.0),
}

/**
 * How the trained muscle feels right after the workout or the next day, picked by hand inside the exercise. Only for
 * reading back: it is never given to the prediction model.
 */
enum class MuscleFeel(val label: String) {
    VERY_SORE("Very sore"), NOTICEABLY_SORE("Noticeably sore"), A_LITTLE_SORE("A little bit sore"),
    CANT_FEEL_ANYTHING("Can't feel anything"),
}

/**
 * Progress colour of a block. A stronger streak climbs green -> GOLD_DARK -> GOLD_MEDIUM -> GOLD
 * (the highest) and stays GOLD while it lasts. The names are the streak steps as stored in the
 * database; on screen they show as medium, light and lighter gold.
 * A weaker streak goes the other way: red -> dark red -> black (overtraining warning).
 */
enum class BlockColor { GOLD, GOLD_MEDIUM, GOLD_DARK, GREEN, GRAY, RED, RED_DARK, BLACK;
    val isGold: Boolean get() = this == GOLD || this == GOLD_MEDIUM || this == GOLD_DARK
    val isWeaker: Boolean get() = this == RED || this == RED_DARK || this == BLACK

    /** The colour for one more weaker workout in a row after this one. */
    fun nextWeaker(): BlockColor = when (this) {
        RED -> RED_DARK
        RED_DARK, BLACK -> BLACK
        else -> RED
    }

    /** The colour for one more stronger workout in a row after this one. */
    fun nextInStreak(): BlockColor = when (this) {
        GREEN -> GOLD_DARK
        GOLD_DARK -> GOLD_MEDIUM
        GOLD_MEDIUM, GOLD -> GOLD
        else -> GREEN
    }

    /** What it means, for labels. */
    val label: String get() = when (this) {
        GOLD -> "Stronger 4+ times in a row"
        GOLD_MEDIUM -> "Stronger 3 times in a row"
        GOLD_DARK -> "Stronger 2 times in a row"
        GREEN -> "Progressed since last time"
        GRAY -> "About the same as last time"
        RED -> "Lower than last time"
        RED_DARK -> "Weaker 2 times in a row"
        BLACK -> "\u26A0 Weaker 3+ times in a row: overtraining"
    }
}

/** A "big data block": one data collection session. */
data class Session(
    val id: Long,
    val startedAt: Long,
    val color: BlockColor?,
    val colorManual: Boolean,
    val saved: Boolean,
    val notePath: String?,
    /** How long the recording page's timer ran, over every visit; null for older sessions. */
    val recordedMs: Long? = null,
    /** Left out of the prediction model. */
    val excluded: Boolean = false,
    val trashedAt: Long? = null, // in the trash since then; null = not in the trash
    /** Hours slept before the workout, said by voice ("8 hours of sleep"); null = not given. */
    val sleepHours: Double? = null,
    /** Recovery feeling, said by voice; null = not given. Kept out of the prediction model. */
    val recovery: RecoveryFeeling? = null,
)

/** A "small data block": one exercise inside a session. */
data class Exercise(
    val id: Long,
    val sessionId: Long,
    val name: String,
    val position: Int,
    val createdAt: Long,
    val firstActivityAt: Long?,
    val lastActivityAt: Long?,
    val color: BlockColor?,
    val notePath: String?,
    /** Marked as about the most progress possible from one workout to the next. */
    val limitMarked: Boolean = false,
    /** Fatigue level set by hand; null = counted automatically ([Fatigue]). */
    val fatigueManual: Int? = null,
    /** How recovered it felt, picked by hand; null = not picked. */
    val recoveryRating: RecoveryRating? = null,
    /** How the muscle felt immediately after the workout; null = not picked. Kept out of the prediction model. */
    val muscleFeel: MuscleFeel? = null,
    /** How the muscle felt the next day; null = not picked. Kept out of the prediction model. */
    val muscleFeelNextDay: MuscleFeel? = null,
) {
    val displayName: String get() = name.ifBlank { "Exercise ${position + 1}" }
    val totalTimeMs: Long
        get() = if (firstActivityAt != null && lastActivityAt != null) lastActivityAt - firstActivityAt else 0L
}

/** One warm-up or working set ("small data block sub data"). */
data class WorkSet(
    val id: Long,
    val exerciseId: Long,
    val kind: SetKind,
    val number: Int,
    val restSec: Int? = null,
    val weight: Double? = null,
    val unit: WeightUnit? = null,
    val reps: Int? = null,
    val tutSec: Int? = null,
    val rirLevel: RirLevel? = null,
    val rirValue: Int? = null,
    val completedAt: Long? = null,
    /** Seconds the end range position was held; optional, null = not given. Not a prediction input. */
    val endRangeSec: Int? = null,
    /** Seconds rested at the top; optional, null = not given. Not a prediction input. */
    val topRestSec: Int? = null,
) {
    val label: String get() = "${kind.label} $number"

    val hasData: Boolean get() = restSec != null || weight != null || reps != null || tutSec != null || rirLevel != null || rirValue != null

    /** Every value of [other] (except which set it is and when it was finished) copied into this set. */
    fun copyValuesFrom(other: WorkSet): WorkSet = copy(
        restSec = other.restSec, weight = other.weight, unit = other.unit, reps = other.reps, tutSec = other.tutSec,
        rirLevel = other.rirLevel, rirValue = other.rirValue, endRangeSec = other.endRangeSec,
        topRestSec = other.topRestSec,
    )

    /** This set back to blank: no values and not finished. */
    fun cleared(): WorkSet = WorkSet(id, exerciseId, kind, number)

    /** Values not yet given for this set (reps or time under tension both count). */
    fun missingFields(s: AppSettings): List<String> = buildList {
        if (restSec == null) add("rest time")
        if (weight == null) add("weight")
        if (reps == null && tutSec == null) add(if (s.effortMode == EffortMode.REPS) "reps" else "time under tension")
        if (s.rirEnabled && rirLevel == null && rirValue == null) add("R I R")
    }
}

data class ExerciseWithSets(val exercise: Exercise, val sets: List<WorkSet>)

data class SessionWithExercises(
    val session: Session,
    val exercises: List<ExerciseWithSets>,
    val notes: List<SessionNote> = emptyList(),
)

/** One voice recording saved in a big data block. */
data class SessionNote(val id: Long, val sessionId: Long, val path: String, val createdAt: Long)

/** A completed set shown as a bubble on the recording page. */
data class CompletedSet(val set: WorkSet, val exerciseName: String)

/**
 * A "big skill block" in the Skills database, e.g. Music or Tennis. [capacity] is its daily
 * skill capacity in hours; null (skills made before it existed) uses the settings default.
 */
data class Skill(val id: Long, val name: String, val position: Int, val createdAt: Long, val capacity: Double? = null) {
    fun capacityOr(default: Float): Double = capacity ?: default.toDouble()
}

/** Hours put into a skill, logged by voice ("music 2 hours"). */
data class SkillLog(val id: Long, val skillId: Long, val hours: Double, val loggedAt: Long)
