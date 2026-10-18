package com.example.tut.data

import kotlin.math.abs
import kotlin.math.roundToLong

/** Progress comparison between two performances of the same exercise. */
object Progress {
    private const val KG_PER_LB = 0.45359237

    /** The best working set: highest weight, ties broken by reps or time under tension. */
    fun topWorkingSet(sets: List<WorkSet>, unit: WeightUnit): WorkSet? =
        sets.filter { it.kind == SetKind.WORKING && it.weight != null }
            .maxWithOrNull(compareBy<WorkSet>({ weightIn(it, unit) }, { effort(it) }))

    private fun effort(s: WorkSet): Int = s.reps ?: s.tutSec ?: 0

    fun weightIn(s: WorkSet, unit: WeightUnit): Double {
        val w = s.weight ?: return 0.0
        val from = s.unit ?: unit
        return when {
            from == unit -> w
            from == WeightUnit.LBS -> w * KG_PER_LB
            else -> w / KG_PER_LB
        }
    }

    /**
     * Green if the new top working set is up by at least the "stronger" threshold (DataOps turns
     * streaks gold), red if it is down by at least the "weaker" threshold, gray in between. Weight
     * decides first; on equal-ish weight, reps (or time under tension) do. Null when either side
     * has no working-set data.
     */
    fun compare(previous: List<WorkSet>, current: List<WorkSet>, s: AppSettings): BlockColor? {
        val prev = topWorkingSet(previous, s.weightUnit) ?: return null
        val cur = topWorkingSet(current, s.weightUnit) ?: return null
        val dw = weightIn(cur, s.weightUnit) - weightIn(prev, s.weightUnit)
        if (atLeast(dw, s.weightUpThreshold.toDouble())) return BlockColor.GREEN
        if (atLeast(-dw, s.weightDownThreshold.toDouble())) return BlockColor.RED
        val (de, up, down) = when {
            cur.reps != null && prev.reps != null -> Triple(cur.reps - prev.reps, s.repsUpThreshold, s.repsDownThreshold)
            cur.tutSec != null && prev.tutSec != null -> Triple(cur.tutSec - prev.tutSec, s.tutUpThresholdSec, s.tutDownThresholdSec)
            else -> return BlockColor.GRAY
        }
        return when {
            atLeast(de.toDouble(), up.toDouble()) -> BlockColor.GREEN
            atLeast(-de.toDouble(), down.toDouble()) -> BlockColor.RED
            else -> BlockColor.GRAY
        }
    }

    /** A change [d] counts when it is positive and at least [threshold] (0 = any change counts). */
    private fun atLeast(d: Double, threshold: Double): Boolean = d > 1e-9 && d >= threshold - 1e-9

    /** Green and every gold mean stronger than the time before. */
    fun improved(c: BlockColor?): Boolean = c == BlockColor.GREEN || c?.isGold == true

    /**
     * Big block colour from its small blocks: whichever of stronger/weaker dominates, else gray.
     * Stronger shows gold when most of the stronger exercises are on a gold streak, in the
     * shade of the longest streak among them.
     */
    fun aggregate(colors: List<BlockColor?>): BlockColor? {
        val known = colors.filterNotNull()
        if (known.isEmpty()) return null
        val golds = known.filter { it.isGold }
        val gold = golds.size
        val green = known.count { it == BlockColor.GREEN }
        val reds = known.filter { it.isWeaker }
        val red = reds.size
        return when {
            gold + green > red -> if (gold > 0 && gold >= green) golds.min() else BlockColor.GREEN
            red > gold + green -> reds.max() // the worst weaker streak among them
            else -> BlockColor.GRAY
        }
    }

    /** Hours between two workouts, rounded to whole days (24, 48, 72 ...). */
    fun restHoursRounded(previousAt: Long, currentAt: Long): Long {
        val hours = abs(currentAt - previousAt) / 3_600_000.0
        return (hours / 24.0).roundToLong() * 24
    }
}
