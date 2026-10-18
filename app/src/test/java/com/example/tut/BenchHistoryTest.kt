package com.example.tut

import com.example.tut.data.AppSettings
import com.example.tut.data.Plan
import com.example.tut.data.Predictions
import com.example.tut.data.ProgressScale
import com.example.tut.data.ProgressionRule
import com.example.tut.data.SetKind
import com.example.tut.data.WorkSet
import com.example.tut.data.Workout
import com.example.tut.data.Supercompensation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real bench press history (custom data), every 3 days: the ladder at 6:00, 1 lb steps. */
class BenchHistoryTest {
    private val s = AppSettings(progressionRules = listOf(ProgressionRule("bench press", 360, 1f)))
    private val day = 24 * 3_600_000L

    private fun w(id: Long, i: Int, vararg sets: Pair<Double, Int>) = Workout(
        id, "bench press", i * 3 * day,
        sets.mapIndexed { n, (weight, tut) -> WorkSet(0, id, SetKind.WORKING, n + 1, restSec = 150, weight = weight, tutSec = tut) },
    )

    val history = listOf(
        w(33, 0, 7.5 to 120),
        w(34, 1, 7.5 to 240),
        w(35, 2, 7.5 to 360),
        w(36, 3, 8.5 to 180),
        w(37, 4, 8.5 to 360),
        w(38, 5, 8.5 to 360, 9.5 to 120),
        w(39, 6, 8.5 to 360, 9.5 to 60),
        w(40, 7, 8.5 to 120),
    )

    @Test fun bestSetIsTheHighestOnTheLadderNotTheHeaviest() {
        val scale = Predictions.scaleFor("bench press", history, s) as ProgressScale.Tut
        // 8.5 lb × 6:00, then a 9.5 lb attempt for 1:00: the 6:00 set is the best.
        val best = scale.bestSet(history[6].sets)!!
        assertEquals(8.5, best.weight!!, 0.0)
        assertEquals(360, best.tutSec)
        // Holding 8.5 × 6:00 again is no change, not a loss.
        assertEquals(0.0, scale.change(scale.score(history[5].sets)!!, scale.score(history[6].sets)!!), 1e-9)
        // The weight went up twice: 6:00 -> 3:00 and 6:00 -> 2:00 per 1 lb, so a step is worth 3:30.
        assertEquals(210.0, scale.stepValueSec, 1e-9)
    }

    @Test fun sameRestEveryTimeIsSaidPlainly() {
        val m = Predictions.train(history, s)
        assertTrue(m.ready)
        assertFalse(m.restVaries("bench press"))
        val curve = m.curve("bench press", Plan(0.0, 1, 0, 150.0, 1.0))!!
        // Nothing to learn rest from: flat, and no supercompensation peak made up for it.
        assertEquals(0.0, Supercompensation.fit(curve).amplitude, 0.0)
        // The graph still shows the classic shape: peak at the usual 3 days of rest, above last time.
        val shown = m.supercompensation("bench press", curve)
        assertTrue(shown.assumed)
        assertEquals(72.0, shown.scaleHours, 0.0)
        assertTrue(shown.amplitude > 0)
        assertTrue(shown.at(24.0) < 0) // the fatigue dip
        assertEquals(shown.amplitude, shown.at(72.0), 1e-9)
    }
}
