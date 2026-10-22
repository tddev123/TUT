package com.example.tut.data

import com.example.tut.parse.formatDuration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** One recorded workout of an exercise, as the prediction model sees it. */
data class Workout(
    val exerciseId: Long,
    val name: String,
    val at: Long,
    val sets: List<WorkSet>,
    /** Marked as about the most progress possible from one workout to the next. */
    val limitMarked: Boolean = false,
    /** Fatigue level set by hand; null = counted automatically ([Fatigue]). */
    val fatigueManual: Int? = null,
    /** How recovered it felt, picked by hand; null = not picked. */
    val recovery: RecoveryRating? = null,
) {
    val key: String get() = Predictions.key(name)
}

/** What is planned for a workout, apart from the weight (which doesn't change the prediction). */
data class Plan(
    val gapHours: Double,
    val workingSets: Int,
    val warmupSets: Int,
    val restSec: Double?,
    val rir: Double?,
)

/** A point on a prediction curve: [pct] progress (below 0: went backwards) after [hours] of rest. */
data class CurvePoint(val hours: Double, val pct: Double)

/** The workout typed in (or suggested) and locked in. */
data class PlannedWorkout(
    val weight: Double,
    val unit: WeightUnit,
    val workingSets: Int,
    val warmupSets: Int,
    val restSec: Int?,
    val rir: Int?,
    /** Measured on the time-under-tension ladder rather than by reps. */
    val timed: Boolean,
    /** Time under tension planned for the best set; null = none picked. Like the weight, it doesn't change the prediction. */
    val tutSec: Int? = null,
)

/** A locked-in prediction, kept until it is checked against the real workout. */
data class LockedPrediction(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val plannedFor: Long,
    val baselineExerciseId: Long,
    val baselineAt: Long,
    /** Last time's score on the exercise's [ProgressScale] when the prediction was made. */
    val baselineStrength: Double,
    val plan: PlannedWorkout,
    val predictedPct: Double,
    val curve: List<CurvePoint>,
    val trainedOn: Int,
    val typicalMiss: Double?,
    /** What the lifter said: accurate (true), not accurate (false), not answered yet (null). */
    val verdict: Boolean?,
    val source: String,
) {
    val plannedGapHours: Double get() = (plannedFor - baselineAt) / 3_600_000.0
}

/** A locked prediction compared with the workout that was then done. */
data class PredictionCheck(
    val workout: Workout,
    val gapHours: Double,
    val actualPct: Double,
    /** What the curve said for the rest actually taken. */
    val predictedPct: Double,
    /** The best set done, and what the curve said it would be at that set's weight. */
    val bestSet: WorkSet,
    val predictedEffortAtThatWeight: Double,
    val close: Boolean,
    /** Set by set against last time, for exercises compared that way; null otherwise. */
    val sets: ProgressScale.SetComparison? = null,
) {
    val missPct: Double get() = actualPct - predictedPct
}

/** The suggested best next workout of one exercise. */
data class BestWorkout(
    val name: String,
    val last: Workout,
    val plan: Plan,
    val weight: Double,
    val effort: Double,
    val predictedPct: Double,
    val curve: List<CurvePoint>,
)

/**
 * How progress is measured for one exercise. Every scale turns a workout's best set into a
 * score; progress from one workout to the next is the change in score, as a percentage.
 *
 * With [setBySet], progress is instead the set-by-set recovery score ([compareSets]) less 50, in
 * points: 0 is the same as last time, +16 is 66%.
 */
sealed class ProgressScale {
    abstract val timed: Boolean
    /** Compare set by set rather than best set against best set; null = best set. */
    abstract val setBySet: SetBySet?
    abstract fun bestSet(sets: List<WorkSet>): WorkSet?
    /** The working sets that can be scored, in set order. */
    abstract fun scoredSets(sets: List<WorkSet>): List<WorkSet>
    /** How far set [b] is past set [a]: reps (at [b]'s weight) or seconds of time under tension. */
    abstract fun effortGap(a: WorkSet, b: WorkSet): Double
    abstract fun score(set: WorkSet): Double
    /** Progress from score [before] to [after], in percent. */
    abstract fun change(before: Double, after: Double): Double
    abstract fun apply(before: Double, pct: Double): Double
    /** Reps, or seconds of time under tension, the [score] allows at [weight]. */
    abstract fun effortAt(score: Double, weight: Double): Double
    abstract fun effortOf(set: WorkSet): Double
    abstract fun effortText(effort: Double): String
    /** A change in plain words: "+2.1%" or "+0:30 TUT". */
    abstract fun changeText(pct: Double): String

    fun score(sets: List<WorkSet>): Double? = bestSet(sets)?.let { score(it) }

    /** A set must be [sameEffort] reps or seconds better (or worse) than last time's to count. */
    data class SetBySet(val sameEffort: Double)

    /**
     * Set by set against last time: [points] per matched set (1 better, half same, 0 worse),
     * [setsPct] their average as a percentage (50% = same as last time), [pct] after the [rating].
     */
    data class SetComparison(val points: List<Double>, val setsPct: Double, val rating: RecoveryRating?, val pct: Double) {
        /** The score with the same as last time at 0. */
        val progress: Double get() = pct - 50
    }

    /**
     * Set 1 against last time's set 1, set 2 against set 2..., over the sets both workouts have,
     * every set counting the same. A set within [SetBySet.sameEffort] of last time's is the same.
     * The [rating] pulls the score towards its own value, more the closer the sets are to 50%:
     * not at all when every set was better (or every set worse), up to halfway when they even out.
     * Null without [setBySet] or with nothing to compare.
     */
    fun compareSets(before: List<WorkSet>, after: List<WorkSet>, rating: RecoveryRating?): SetComparison? {
        val same = max(setBySet?.sameEffort ?: return null, 1e-6)
        val a = scoredSets(before)
        val b = scoredSets(after)
        if (a.isEmpty() || b.isEmpty()) return null
        val points = a.zip(b) { x, y ->
            val gap = effortGap(x, y)
            if (gap >= same - 1e-9) 1.0 else if (gap <= -same + 1e-9) 0.0 else 0.5
        }
        val sets = points.average() * 100
        val feel = rating?.pct ?: return SetComparison(points, sets, rating, sets)
        val weight = 0.5 * (1 - abs(sets - 50) / 50)
        return SetComparison(points, sets, rating, sets + weight * (feel - sets))
    }

    /** Progress from workout [before] to [after]: set by set ([compareSets]) or best set against best set. */
    fun progress(before: Workout, after: Workout): Double =
        compareSets(before.sets, after.sets, after.recovery)?.progress ?: change(score(before.sets)!!, score(after.sets)!!)

    /** Set by set, a change is in points of the recovery score: "+16 pts". */
    protected fun pointsText(pct: Double) = signed(pct) + "${abs(pct).roundToInt()} pts"

    /**
     * Reps: a set's estimated one-rep max (Epley: weight × (1 + reps / 30)); a timed set counts
     * one rep per [SEC_PER_REP] seconds. Progress is the percent change in that max.
     */
    class Reps(private val unit: WeightUnit, override val setBySet: SetBySet? = null) : ProgressScale() {
        override val timed = false
        private fun reps(s: WorkSet) = s.reps?.toDouble() ?: s.tutSec?.div(SEC_PER_REP)
        private fun weight(s: WorkSet) = Progress.weightIn(s, unit)

        override fun scoredSets(sets: List<WorkSet>) =
            sets.filter { it.kind == SetKind.WORKING && it.weight != null && reps(it) != null && weight(it) > 0 }.sortedBy { it.number }

        override fun bestSet(sets: List<WorkSet>) = scoredSets(sets).maxByOrNull { score(it) }

        override fun effortGap(a: WorkSet, b: WorkSet) = 30 * (score(b) - score(a)) / weight(b)
        override fun score(set: WorkSet) = weight(set) * (1 + reps(set)!! / 30)
        override fun change(before: Double, after: Double) = (after / before - 1) * 100
        // Set by set predicts recovery, not strength: the best set is expected to match last time's.
        override fun apply(before: Double, pct: Double) = if (setBySet != null) before else before * (1 + pct / 100)
        override fun effortAt(score: Double, weight: Double) = if (weight <= 0) 0.0 else max(0.0, 30 * (score / weight - 1))
        override fun effortOf(set: WorkSet) = reps(set) ?: 0.0
        override fun effortText(effort: Double) = if (effort < 0.5) "under 1 rep (too heavy)" else "${effort.roundToInt()} reps"
        override fun changeText(pct: Double) = if (setBySet != null) pointsText(pct) else signed(pct) + Fmt1.pct(abs(pct))
    }

    /**
     * The time-under-tension ladder: time under tension climbs until it reaches [goalSec], then the
     * weight goes up one [step] and the time drops back. One weight step is worth [stepValueSec]
     * seconds (learned from how far the time dropped after earlier weight increases), so
     * score = weight / step × stepValueSec + time under tension. Progress is the change in score
     * as a percentage of the goal: +10% with a 6:00 goal is 36 seconds more.
     */
    class Tut(
        private val unit: WeightUnit,
        val goalSec: Double,
        val step: Double,
        val stepValueSec: Double,
        /** Where [stepValueSec] came from: the trade set by hand, learned from workouts, or a guess. */
        val stepValueSource: StepValueSource = StepValueSource.GUESSED,
        override val setBySet: SetBySet? = null,
    ) : ProgressScale() {
        override val timed = true
        private fun weight(s: WorkSet) = if (s.weight == null) 0.0 else Progress.weightIn(s, unit)

        /**
         * The set highest on the ladder, not simply the heaviest: 8.5 lb × 6:00 beats a 9.5 lb
         * attempt held for 1:00 after it. Ties go to the heavier set.
         */
        override fun bestSet(sets: List<WorkSet>) = heaviest(sets).let { heavy ->
            sets.filter { it.kind == SetKind.WORKING && it.tutSec != null }
                .maxWithOrNull(compareBy({ score(it) }, { weight(it) })) ?: heavy
        }

        /** The heaviest timed working set: where the weight went up to, for learning the trade. */
        override fun scoredSets(sets: List<WorkSet>) =
            sets.filter { it.kind == SetKind.WORKING && it.tutSec != null }.sortedBy { it.number }

        override fun effortGap(a: WorkSet, b: WorkSet) = score(b) - score(a)

        fun heaviest(sets: List<WorkSet>) =
            sets.filter { it.kind == SetKind.WORKING && it.tutSec != null }
                .maxWithOrNull(compareBy({ weight(it) }, { it.tutSec!! }))

        override fun score(set: WorkSet) = weight(set) / step * stepValueSec + set.tutSec!!
        override fun change(before: Double, after: Double) = (after - before) / goalSec * 100
        override fun apply(before: Double, pct: Double) = if (setBySet != null) before else before + pct / 100 * goalSec
        override fun effortAt(score: Double, weight: Double) = max(0.0, score - weight / step * stepValueSec)
        override fun effortOf(set: WorkSet) = set.tutSec?.toDouble() ?: 0.0
        // Never "0:00": a weight the time under tension can't even start with is called too heavy.
        override fun effortText(effort: Double) =
            if (effort < 1) "under 0:01 TUT (too heavy)" else formatDuration(effort.roundToInt()) + " TUT"
        override fun changeText(pct: Double) =
            if (setBySet != null) pointsText(pct) else signed(pct) + formatDuration((abs(pct) / 100 * goalSec).roundToInt()) + " TUT"

        /** The weight for the next workout: one step up once the time under tension reached the goal. */
        fun nextWeight(last: WorkSet): Double = weight(last) + if (last.tutSec!! >= goalSec) step else 0.0
    }

    protected fun signed(v: Double) = if (v >= 0) "+" else "−"

    enum class StepValueSource { SET, LEARNED, GUESSED }

    companion object {
        const val SEC_PER_REP = 4.0
    }
}

private object Fmt1 {
    fun pct(v: Double): String =
        java.math.BigDecimal.valueOf((v * 10).roundToInt() / 10.0).stripTrailingZeros().toPlainString() + "%"
}

/**
 * Predicts how much progress the best set of a planned workout will make over the last time the
 * same exercise was done, from every earlier "workout vs the one before it" in the database.
 *
 * Exercises progressed by time under tension are measured on their [ProgressScale.Tut] ladder
 * (settings: goal and weight step per exercise), others by estimated one-rep max. The weight
 * lifted is left out of what the model learns from: heavier sets go with being stronger, and the
 * model would otherwise "learn" that lifting heavier makes you stronger.
 *
 * Exercises switched to set by set ([AppSettings.comparesAllSets]) learn the recovery score
 * instead ([ProgressScale.compareSets]): every working set against the same set last time.
 *
 * Workouts marked "near the limit" cap the predictions: no prediction goes above the progress
 * those workouts made.
 */
object Predictions {
    /** Fewer comparisons than this and the model isn't trained. */
    const val MIN_ROWS = 6
    /** Within this many percent counts as close. */
    const val CLOSE_PCT = 2.5
    private const val MISSING = -1e6

    /** Whether an input to the model is "not known" (e.g. no RIR given). */
    fun isMissing(v: Double): Boolean = v <= MISSING / 2

    /** What each input to the model is, in the order the model sees them ([features]). */
    val inputNames: List<String> = listOf(
        "Rest since last time",
        "Working sets",
        "Rest between sets",
        "RIR",
        "Last time's working sets",
        "Last time's RIR",
        "Last time's rest between sets",
        "Last time's progress",
        "Rest before last time",
        "Average progress so far",
        "Workouts logged",
        "Last best set (reps, or seconds if timed)",
        "Compared set by set",
    )

    /** One workout the model learned from: its inputs and the progress it really made. */
    data class LearnedRow(val name: String, val at: Long, val inputs: DoubleArray, val actual: Double)
    private const val HOUR = 3_600_000.0

    /** Rest lengths the curve is drawn over: every 12 hours, up to 10 days. */
    val gapGrid: List<Double> = (1..20).map { it * 12.0 }

    /** Rest a planned or suggested workout can have: 2 to 7 days. */
    val restDays: IntRange = 2..7

    fun key(name: String) = name.trim().lowercase()

    /**
     * Everything known about an exercise before the workout being predicted. The fatigue level
     * ([Fatigue]) is left out: it's only shown on the exercise, never a prediction input.
     */
    data class History(
        val workouts: List<Workout>,
        val strengths: List<Double>,
        val scale: ProgressScale,
        val s: AppSettings,
    ) {
        val last: Workout get() = workouts.last()
        val lastStrength: Double get() = strengths.last()
        val lastBest: WorkSet get() = scale.bestSet(last.sets)!!
        val changes: List<Double> get() = workouts.zipWithNext(scale::progress)
    }

    fun planOf(w: Workout, lastAt: Long, s: AppSettings): Plan {
        val sum = Bulletins.summarize(w.at, w.sets, s)
        val rests = w.sets.filter { it.kind == SetKind.WORKING }.mapNotNull { it.restSec }
        return Plan(
            gapHours = (w.at - lastAt) / HOUR,
            workingSets = sum.workingSets,
            warmupSets = w.sets.count { it.kind == SetKind.WARMUP && it.hasData },
            restSec = rests.takeIf { it.isNotEmpty() }?.average(),
            rir = sum.avgRir,
        )
    }

    private fun features(p: Plan, h: History): DoubleArray {
        val lastPlan = planOf(h.last, h.workouts.getOrNull(h.workouts.size - 2)?.at ?: h.last.at, h.s)
        val changes = h.changes
        return doubleArrayOf(
            p.gapHours,
            p.workingSets.toDouble(),
            // Warm-up sets don't count toward predictions.
            p.restSec ?: MISSING,
            p.rir ?: MISSING,
            lastPlan.workingSets.toDouble(),
            lastPlan.rir ?: MISSING,
            lastPlan.restSec ?: MISSING,
            changes.lastOrNull() ?: MISSING,
            if (h.workouts.size >= 2) lastPlan.gapHours else MISSING,
            changes.takeIf { it.isNotEmpty() }?.average() ?: MISSING,
            h.workouts.size.toDouble(),
            h.scale.effortOf(h.lastBest),
            if (h.scale.setBySet != null) 1.0 else 0.0,
        )
    }

    /**
     * The scale an exercise is measured on: the time-under-tension ladder when most of its
     * workouts have timed working sets, else reps.
     */
    fun scaleFor(name: String, workouts: List<Workout>, s: AppSettings): ProgressScale {
        val timed = workouts.count { w -> w.sets.any { it.kind == SetKind.WORKING && it.tutSec != null } }
        val allSets = s.comparesAllSets(name)
        if (timed * 2 < workouts.size || timed == 0) {
            return ProgressScale.Reps(s.weightUnit, if (allSets) ProgressScale.SetBySet(s.setSameReps.toDouble()) else null)
        }
        val sb = if (allSets) ProgressScale.SetBySet(s.setSameTutSec.toDouble()) else null
        val rule = s.progressionFor(name)
        val goal = rule.tutGoalSec.toDouble().coerceAtLeast(1.0)
        val step = rule.loadStep.toDouble().coerceAtLeast(0.01)
        // How far the time dropped each time the weight went up, per step.
        val probe = ProgressScale.Tut(s.weightUnit, goal, step, 0.0)
        val drops = workouts.sortedBy { it.at }.mapNotNull { w -> probe.heaviest(w.sets) }.zipWithNext { a, b ->
            val steps = ((b.weight ?: 0.0) - (a.weight ?: 0.0)) / step
            if (steps in 0.5..5.0 && a.tutSec!! > b.tutSec!!) (a.tutSec - b.tutSec) / steps else null
        }.filterNotNull().sorted()
        // The trade set by hand wins; then what the workouts show; then half the goal as a guess.
        rule.secondsPerWeight?.let { perWeight ->
            return ProgressScale.Tut(s.weightUnit, goal, step, (perWeight * step).coerceAtLeast(1.0), ProgressScale.StepValueSource.SET, sb)
        }
        if (drops.isEmpty()) return ProgressScale.Tut(s.weightUnit, goal, step, goal / 2, ProgressScale.StepValueSource.GUESSED, sb)
        val mid = drops.size / 2
        val median = if (drops.size % 2 == 1) drops[mid] else (drops[mid - 1] + drops[mid]) / 2
        val stepValue = median.coerceIn(goal * 0.05, goal)
        return ProgressScale.Tut(s.weightUnit, goal, step, stepValue, ProgressScale.StepValueSource.LEARNED, sb)
    }

    class Model internal constructor(
        private val gbdt: Gbdt?,
        /** How many "workout vs the one before" comparisons it learned from. */
        val rows: Int,
        /** Average miss on workouts it was not shown while learning, in percent; null with too little data. */
        val typicalMiss: Double?,
        private val histories: Map<String, History>,
        /** Most progress allowed per exercise, from workouts marked near the limit. */
        private val limits: Map<String, Double>,
        /** Shortest and longest rest between workouts of each exercise it learned from, in hours. */
        private val restRanges: Map<String, ClosedFloatingPointRange<Double>>,
        /** Average rest between workouts of each exercise it learned from, in hours. */
        private val restAverages: Map<String, Double>,
        /** Exercise names it can predict, most recently done first. */
        val names: List<String>,
    ) {
        val ready: Boolean get() = gbdt != null

        fun history(name: String): History? = histories[key(name)]
        fun scale(name: String): ProgressScale? = history(name)?.scale
        fun limit(name: String): Double? = limits[key(name)]

        /**
         * Whether the workouts of [name] it learned from had rests at least a day apart. If they
         * didn't (e.g. always 3 days), it can't know how rest changes the result: its curve is flat.
         */
        fun restVaries(name: String): Boolean = restRanges[key(name)]?.let { it.endInclusive - it.start >= 24.0 } ?: false

        fun restRange(name: String): ClosedFloatingPointRange<Double>? = restRanges[key(name)]

        fun restAverage(name: String): Double? = restAverages[key(name)]

        /** The trees, for the prediction model page (read only). */
        val trees: Gbdt? get() = gbdt

        /** The inputs for doing last time's workout of [name] again at [now]. */
        fun inputsNow(name: String, now: Long): DoubleArray? {
            val h = history(name) ?: return null
            val plan = planOf(h.last, h.workouts.getOrNull(h.workouts.size - 2)?.at ?: h.last.at, h.s)
            return features(plan.copy(gapHours = (now - h.last.at) / HOUR), h)
        }

        /** Every workout it learned from, built the same way as when it learned. */
        fun learnedRows(): List<LearnedRow> = histories.values.flatMap { h ->
            (1 until h.workouts.size).map { i ->
                val before = History(h.workouts.subList(0, i), h.strengths.subList(0, i), h.scale, h.s)
                val plan = planOf(h.workouts[i], h.workouts[i - 1].at, h.s)
                LearnedRow(h.workouts[i].name, h.workouts[i].at, features(plan, before), h.scale.progress(h.workouts[i - 1], h.workouts[i]))
            }
        }

        /**
         * The supercompensation curve to draw for [curve]. Fitted to the model's predictions when
         * they show how rest matters; otherwise the classic shape is assumed: its peak at the
         * rest this exercise usually had before workouts that made progress (3 days if none),
         * as high as the model predicts or, failing that, as the average gain of those workouts.
         */
        fun supercompensation(name: String, curve: List<CurvePoint>): Supercompensation.Fit {
            val fitted = Supercompensation.fit(curve)
            if (fitted.amplitude > 1e-6 && restVaries(name)) return fitted
            val h = history(name)
            val gains = h?.let { hist ->
                hist.workouts.zipWithNext { a, b -> (b.at - a.at) / HOUR }.zip(hist.changes).filter { it.second > 0 }
            }.orEmpty()
            val peak = gains.map { it.first }.sorted().let { g ->
                if (g.isEmpty()) 72.0 else if (g.size % 2 == 1) g[g.size / 2] else (g[g.size / 2 - 1] + g[g.size / 2]) / 2
            }.coerceIn(36.0, 168.0)
            val height = listOfNotNull(
                curve.maxOfOrNull { it.pct }?.takeIf { it > 0 },
                gains.map { it.second }.takeIf { it.isNotEmpty() }?.average(),
            ).firstOrNull() ?: 5.0
            return Supercompensation.Fit(peak, height, assumed = true)
        }

        fun predict(name: String, plan: Plan): Double? {
            val m = gbdt ?: return null
            val h = history(name) ?: return null
            val p = m.predict(features(plan, h))
            return limit(name)?.let { minOf(p, it) } ?: p
        }

        /**
         * Predicted progress for every rest length in [gapGrid], everything else as in [plan].
         * Lightly smoothed, since trees predict in steps.
         */
        fun curve(name: String, plan: Plan): List<CurvePoint>? {
            val raw = gapGrid.map { g -> predict(name, plan.copy(gapHours = g)) ?: return null }
            return gapGrid.mapIndexed { i, g ->
                val window = raw.subList(max(0, i - 2), minOf(raw.size, i + 3))
                CurvePoint(g, window.average())
            }
        }

        /** The weight to suggest after [h]'s last workout: the ladder's next step, or last time's weight. */
        fun nextWeight(h: History): Double {
            val scale = h.scale
            val best = h.lastBest
            return if (scale is ProgressScale.Tut) scale.nextWeight(best) else Progress.weightIn(best, h.s.weightUnit)
        }

        /**
         * The next workout of [name] predicted to make the most progress: rest days, sets, rest
         * between sets and RIR. On the time-under-tension ladder the weight follows the ladder;
         * otherwise it's picked so the best set is about as many reps as last time.
         */
        fun best(name: String, now: Long, s: AppSettings): BestWorkout? {
            val h = history(name) ?: return null
            if (gbdt == null) return null
            val last = h.last
            val lastPlan = planOf(last, h.workouts.getOrNull(h.workouts.size - 2)?.at ?: last.at, s)
            val sinceLast = (now - last.at) / HOUR
            val rests = listOf(60.0, 90.0, 120.0, 180.0, 240.0, 300.0)
            // RIR as the three levels, each its number from the RIR settings.
            val rirs: List<Double?> = if (s.rirEnabled) listOf(s.rirLow, s.rirModerate, s.rirHigh).map { it.toDouble() } else listOf(null)
            var best: Pair<Plan, List<CurvePoint>>? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (sets in 1..6) for (rest in rests) for (rir in rirs) {
                val base = Plan(0.0, sets, lastPlan.warmupSets, rest, rir)
                val curve = curve(name, base) ?: continue
                // 2 to 7 days of rest, and only rest lengths still ahead (can't go back in time).
                val ahead = curve.filter { it.hours >= sinceLast }
                val peak = (ahead.filter { it.hours / 24 in restDays.first.toDouble()..restDays.last.toDouble() }.ifEmpty { ahead })
                    .maxByOrNull { it.pct } ?: continue
                // Ties (common: trees predict in steps) go to what's closest to last time.
                val distance = abs(sets - lastPlan.workingSets) / 6.0 +
                    abs(rest - (lastPlan.restSec ?: rest)) / 300.0 +
                    (if (rir != null && lastPlan.rir != null) abs(rir - lastPlan.rir) / 4.0 else 0.0)
                val score = peak.pct - 0.01 * distance
                if (score > bestScore) {
                    bestScore = score
                    best = base.copy(gapHours = peak.hours) to curve
                }
            }
            val (plan, curve) = best ?: return null
            val pct = curve.first { it.hours == plan.gapHours }.pct
            val strength = h.scale.apply(h.lastStrength, pct)
            val weight = when (val scale = h.scale) {
                is ProgressScale.Tut -> {
                    // Follow the ladder, but never suggest a weight the time can't reach: back off a
                    // step at a time until it's at least 0:30 (or last time's time, if that was less).
                    val least = minOf(30.0, scale.effortOf(h.lastBest)).coerceAtLeast(1.0)
                    var w = scale.nextWeight(h.lastBest)
                    var guard = 0
                    while (scale.effortAt(strength, w) < least && w - scale.step >= 0 && guard++ < 40) w -= scale.step
                    w
                }
                is ProgressScale.Reps -> {
                    val step = if (s.weightUnit == WeightUnit.KG) 1.25 else 2.5
                    (strength / (1 + scale.effortOf(h.lastBest) / 30) / step).roundToInt() * step
                }
            }
            return BestWorkout(
                name = last.name, last = last, plan = plan, weight = weight,
                effort = h.scale.effortAt(strength, weight), predictedPct = pct, curve = curve,
            )
        }

        /** The first workout of the same exercise after the one the prediction started from, compared with it. */
        fun check(p: LockedPrediction, workouts: List<Workout>, s: AppSettings): PredictionCheck? {
            val same = workouts.filter { it.key == key(p.name) }
            val scale = scale(p.name) ?: scaleFor(p.name, same, s)
            // Last time's score on today's scale (progression settings may have changed since).
            val baselineWorkout = same.firstOrNull { it.exerciseId == p.baselineExerciseId }
            val baseline = baselineWorkout?.let { scale.score(it.sets) } ?: p.baselineStrength
            val done = same
                .filter { it.exerciseId != p.baselineExerciseId && it.at >= p.baselineAt }
                .sortedWith(compareBy({ it.at }, { it.exerciseId }))
                .firstOrNull { scale.bestSet(it.sets) != null } ?: return null
            val bestSet = scale.bestSet(done.sets)!!
            val gap = (done.at - p.baselineAt) / HOUR
            val predicted = at(p.curve, gap)
            val sets = baselineWorkout?.let { scale.compareSets(it.sets, done.sets, done.recovery) }
            // Set by set needs last time's sets: without them (that block deleted) it can't be checked.
            if (scale.setBySet != null && sets == null) return null
            val actual = sets?.progress ?: scale.change(baseline, scale.score(bestSet))
            val setWeight = if (bestSet.weight == null) 0.0 else Progress.weightIn(bestSet, s.weightUnit)
            return PredictionCheck(
                workout = done, gapHours = gap, actualPct = actual, predictedPct = predicted, bestSet = bestSet,
                predictedEffortAtThatWeight = scale.effortAt(scale.apply(baseline, predicted), setWeight),
                close = abs(actual - predicted) <= CLOSE_PCT, sets = sets,
            )
        }
    }

    /** Learns from every workout in [workouts]. Blocking; takes well under a second on a phone. */
    fun train(workouts: List<Workout>, s: AppSettings): Model {
        val histories = workouts.filter { it.name.isNotBlank() }.groupBy { it.key }.mapNotNull { (k, list) ->
            val scale = scaleFor(list.first().name, list, s)
            val scored = list.mapNotNull { w -> scale.score(w.sets)?.let { w to it } }
                .sortedWith(compareBy({ it.first.at }, { it.first.exerciseId }))
            if (scored.isEmpty()) return@mapNotNull null
            k to History(scored.map { it.first }, scored.map { it.second }, scale, s)
        }.toMap()

        val x = mutableListOf<DoubleArray>()
        val y = mutableListOf<Double>()
        val marked = mutableMapOf<String, MutableList<Double>>()
        val gaps = mutableMapOf<String, MutableList<Double>>()
        val markedByKind = mutableMapOf<Boolean, MutableList<Double>>()
        for ((k, h) in histories) {
            for (i in 1 until h.workouts.size) {
                val before = History(h.workouts.subList(0, i), h.strengths.subList(0, i), h.scale, s)
                val change = h.scale.progress(h.workouts[i - 1], h.workouts[i])
                val plan = planOf(h.workouts[i], h.workouts[i - 1].at, s)
                gaps.getOrPut(k) { mutableListOf() } += plan.gapHours
                x += features(plan, before)
                y += change
                if (h.workouts[i].limitMarked) {
                    marked.getOrPut(k) { mutableListOf() } += change
                    markedByKind.getOrPut(h.scale.timed) { mutableListOf() } += change
                }
            }
        }
        // Each exercise's limit is the average of its own marked workouts; without any, the
        // average of marked workouts measured the same way (percent of TUT goal, or of max).
        val limits = histories.mapNotNull { (k, h) ->
            (marked[k] ?: markedByKind[h.scale.timed])?.average()?.let { k to it }
        }.toMap()
        val names = histories.values.sortedByDescending { it.last.at }.map { it.last.name }
        val restRanges = gaps.mapValues { (_, g) -> g.min()..g.max() }
        val restAverages = gaps.mapValues { (_, g) -> g.average() }
        if (x.size < MIN_ROWS) return Model(null, x.size, null, histories, limits, restRanges, restAverages, names)
        val gbdt = Gbdt.fit(x, y.toDoubleArray(), minLeaf = max(2, x.size / 25))
        return Model(gbdt, x.size, crossValidatedMiss(x, y), histories, limits, restRanges, restAverages, names)
    }

    /** Average miss on rows held out of training, five folds; null with too few rows to say. */
    private fun crossValidatedMiss(x: List<DoubleArray>, y: List<Double>): Double? {
        if (x.size < 10) return null
        val folds = 5
        val misses = mutableListOf<Double>()
        for (f in 0 until folds) {
            val train = x.indices.filter { it % folds != f }
            val test = x.indices.filter { it % folds == f }
            val m = Gbdt.fit(train.map { x[it] }, train.map { y[it] }.toDoubleArray(), minLeaf = max(2, train.size / 25))
            test.forEach { misses += abs(m.predict(x[it]) - y[it]) }
        }
        return misses.average()
    }

    /** The curve's value at [hours], straight lines between its points. */
    fun at(curve: List<CurvePoint>, hours: Double): Double {
        if (curve.isEmpty()) return 0.0
        if (hours <= curve.first().hours) return curve.first().pct
        if (hours >= curve.last().hours) return curve.last().pct
        val i = curve.indexOfFirst { it.hours >= hours }
        val a = curve[i - 1]
        val b = curve[i]
        return a.pct + (b.pct - a.pct) * (hours - a.hours) / (b.hours - a.hours)
    }

    /** Whole days of rest from [lastAt] until [now], at least 1. */
    fun daysSince(lastAt: Long, now: Long): Int = max(1, ceil((now - lastAt) / (24 * HOUR)).toInt())
}

/**
 * The classic supercompensation curve ([Adaptation.level]: fatigue dip, back to baseline,
 * supercompensation peak, back to baseline) fitted to a prediction curve: the peak lands where
 * the model's predictions say rest pays off most, as high as they say.
 */
object Supercompensation {
    /** [scaleHours]: when the peak comes after the workout. [amplitude]: how high it is (and how deep the dip). */
    /** [assumed]: the classic shape placed by the exercise's history, not fitted to the predictions. */
    data class Fit(val scaleHours: Double, val amplitude: Double, val assumed: Boolean = false) {
        fun at(hours: Double): Double = amplitude * Adaptation.level(hours, scaleHours)

        /**
         * The same curve, moved so it passes through the prediction: [pct] after [hours] of rest.
         * A predicted loss lands in the fatigue dip (the peak comes later), a gain on the way up
         * or down from the peak; of the places that fit, the one with the peak closest to this
         * curve's. The curve only grows taller when the prediction is beyond its height.
         */
        fun through(hours: Double, pct: Double): Fit {
            val amp = maxOf(amplitude, abs(pct), 1e-6)
            // Peak times every half hour up to about 6 weeks, with how far each misses the prediction.
            val tries = (12..2_000).map { it / 2.0 }.map { s -> s to abs(amp * Adaptation.level(hours, s) - pct) }
            val fits = tries.filter { it.second <= amp * 0.01 }
            val scale = fits.minByOrNull { abs(it.first - scaleHours) }?.first ?: tries.minBy { it.second }.first
            return copy(scaleHours = scale, amplitude = amp)
        }
    }

    /**
     * Least-squares fit over peak times every 6 hours, the peak kept inside the curve. The peak
     * can't be higher than the model's own best prediction, nor below 0 (no upside-down curves):
     * with no progress predicted anywhere there is no peak.
     */
    fun fit(curve: List<CurvePoint>): Fit {
        val top = maxOf(0.0, curve.maxOfOrNull { it.pct } ?: 0.0)
        var best = Fit(curve.maxByOrNull { it.pct }?.hours ?: 48.0, 0.0)
        // Nothing above last time, or the same for every rest: there's no peak in the data to fit.
        val flat = curve.maxOf { it.pct } - curve.minOf { it.pct } < 1e-6
        if (top <= 0.0 || flat) return best
        var bestErr = Double.MAX_VALUE
        var scale = 12.0
        val last = curve.last().hours
        while (scale <= last) {
            val levels = curve.map { Adaptation.level(it.hours, scale) }
            val ll = levels.sumOf { it * it }
            if (ll > 1e-9) {
                val amp = (levels.indices.sumOf { levels[it] * curve[it].pct } / ll).coerceIn(0.0, top)
                val err = levels.indices.sumOf { val d = amp * levels[it] - curve[it].pct; d * d }
                if (err < bestErr) {
                    bestErr = err
                    best = Fit(scale, amp)
                }
            }
            scale += 6.0
        }
        return best
    }
}
