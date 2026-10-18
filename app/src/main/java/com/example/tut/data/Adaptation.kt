package com.example.tut.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** How an exercise went compared with the last time it was done. */
enum class Outcome { STRONGER, SAME, WEAKER;
    companion object {
        fun of(color: BlockColor?): Outcome? = when (color) {
            BlockColor.GREEN, BlockColor.GOLD_DARK, BlockColor.GOLD_MEDIUM, BlockColor.GOLD -> STRONGER
            BlockColor.GRAY -> SAME
            BlockColor.RED, BlockColor.RED_DARK, BlockColor.BLACK -> WEAKER
            null -> null
        }
    }
}

/** Units on the adaptation curve's time axis. */
enum class CurveUnit(val label: String) { HOURS("Hours"), DAYS("Days") }

/**
 * Classic supercompensation curve. Time 0 is the last workout: fitness dips below baseline
 * (fatigue), climbs back to baseline, overshoots by as much as it dipped (supercompensation),
 * then falls back to baseline (involution).
 *
 * On a time scale `s`: the dip runs 0..0.7s (lowest at 0.35s), the curve rises to its peak at
 * `s`, then eases back to baseline at 1.6s. The scale is picked so the current workout lands
 * where its result says it did: stronger = on the peak, same = back at baseline between the
 * dip and the peak (came back too early to gain), weaker = still in the dip.
 */
object Adaptation {
    private const val CROSS = 0.7
    private const val DIP_LOW = CROSS / 2
    private const val END = 1.6

    fun scaleFor(gapHours: Double, outcome: Outcome): Double {
        val gap = gapHours.coerceAtLeast(1.0)
        return when (outcome) {
            Outcome.STRONGER -> gap
            Outcome.SAME -> gap / CROSS
            Outcome.WEAKER -> gap / DIP_LOW
        }
    }

    /** Fitness relative to baseline at [t] hours after the last workout, from −1 to +1. */
    fun level(t: Double, scale: Double): Double {
        val x = t / scale
        return when {
            x <= 0 -> 0.0
            x < CROSS -> -sin(PI * x / CROSS)
            x < 1.0 -> sin(PI / 2 * (x - CROSS) / (1.0 - CROSS))
            x < END -> cos(PI / 2 * (x - 1.0) / (END - 1.0))
            else -> 0.0
        }
    }
}
