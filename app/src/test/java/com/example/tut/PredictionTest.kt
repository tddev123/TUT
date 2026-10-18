package com.example.tut

import com.example.tut.data.AppSettings
import com.example.tut.data.CurvePoint
import com.example.tut.data.Gbdt
import com.example.tut.data.LockedPrediction
import com.example.tut.data.Plan
import com.example.tut.data.PlannedWorkout
import com.example.tut.data.Predictions
import com.example.tut.data.ProgressScale
import com.example.tut.data.ProgressionRule
import com.example.tut.data.SetKind
import com.example.tut.data.WeightUnit
import com.example.tut.data.WorkSet
import com.example.tut.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PredictionTest {
    private val s = AppSettings(rirEnabled = false)
    private val hour = 3_600_000L

    @Test fun treesLearnAStep() {
        val x = (0 until 60).map { doubleArrayOf(it.toDouble()) }
        val y = DoubleArray(60) { if (it < 30) -2.0 else 3.0 }
        val m = Gbdt.fit(x, y, trees = 300, learningRate = 0.1)
        assertEquals(-2.0, m.predict(doubleArrayOf(5.0)), 0.3)
        assertEquals(3.0, m.predict(doubleArrayOf(50.0)), 0.3)
    }

    private fun sets(weight: Double, reps: Int, working: Int = 3) =
        (1..working).map { WorkSet(0, 0, SetKind.WORKING, it, restSec = 120, weight = weight, reps = reps) }

    private fun timedSets(weight: Double, tutSec: Int) =
        (1..3).map { WorkSet(0, 0, SetKind.WORKING, it, restSec = 120, weight = weight, tutSec = tutSec) }

    /**
     * Fake bench history where 2-4 days of rest makes you about 3% stronger and anything
     * else about 2% weaker. Always 8 reps; the weight carries the change exactly.
     */
    private fun history(n: Int, marked: (Int) -> Boolean = { false }): List<Workout> {
        val r = Random(1)
        var at = 0L
        var strength = 100.0 * (1 + 8 / 30.0)
        return (0 until n).map { i ->
            val gapDays = if (i == 0) 0 else listOf(1, 2, 3, 4, 6, 8)[r.nextInt(6)]
            at += gapDays * 24 * hour
            if (i > 0) strength *= if (gapDays in 2..4) 1.03 else 0.98
            Workout(i.toLong() + 1, "Bench press", at, sets(strength / (1 + 8 / 30.0), 8), limitMarked = marked(i))
        }
    }

    @Test fun notTrainedWithTooLittleData() {
        val m = Predictions.train(history(4), s)
        assertFalse(m.ready)
        assertEquals(3, m.rows)
    }

    @Test fun curvePeaksWhereRestPaidOff() {
        val m = Predictions.train(history(80), s)
        assertTrue(m.ready)
        val curve = m.curve("bench press", Plan(0.0, 3, 0, 120.0, null))!!
        val peak = curve.maxBy { it.pct }
        assertTrue("peak at ${peak.hours} h", peak.hours in 36.0..108.0)
        assertTrue(Predictions.at(curve, 72.0) > 1.5)
        assertTrue(Predictions.at(curve, 24.0 * 8) < 0.0)
    }

    @Test fun bestNextWorkoutPicksTheRestThatWorked() {
        val w = history(80)
        val m = Predictions.train(w, s)
        val best = m.best("Bench press", w.last().at + 12 * hour, s)!!
        assertTrue("best rest ${best.plan.gapHours} h", best.plan.gapHours in 36.0..108.0)
        assertTrue(best.predictedPct > 1.0)
    }

    @Test fun markedWorkoutsCapPredictions() {
        // The 3% jumps are marked as about the most one workout can make; cap them lower to see it bite.
        val w = history(80).mapIndexed { i, x -> if (i == 5) x.copy(limitMarked = true) else x }
        val m = Predictions.train(w, s)
        val limit = m.limit("bench press")!!
        assertTrue(m.curve("bench press", Plan(0.0, 3, 0, 120.0, null))!!.all { it.pct <= limit + 1e-9 })
    }

    @Test fun tutLadderMeasuresTimeThenWeight() {
        val rules = s.copy(progressionRules = listOf(ProgressionRule("Curl", tutGoalSec = 300, loadStep = 2f)))
        // 4:00 -> 5:00 (goal reached), then 2 lb up and back to 3:00, then 3:30.
        val w = listOf(
            Workout(1, "Curl", 0, timedSets(20.0, 240)),
            Workout(2, "Curl", 72 * hour, timedSets(20.0, 300)),
            Workout(3, "Curl", 144 * hour, timedSets(22.0, 180)),
            Workout(4, "Curl", 216 * hour, timedSets(22.0, 210)),
        )
        val scale = Predictions.scaleFor("Curl", w, rules) as ProgressScale.Tut
        assertEquals(300.0, scale.goalSec, 0.0)
        // One step was worth the 2:00 the time dropped by.
        assertEquals(120.0, scale.stepValueSec, 0.0)
        val scores = w.map { scale.score(it.sets)!! }
        // +1:00 of a 5:00 goal is +20%; adding the step at 3:00 is "no progress"; +0:30 is +10%.
        assertEquals(20.0, scale.change(scores[0], scores[1]), 1e-9)
        assertEquals(0.0, scale.change(scores[1], scores[2]), 1e-9)
        assertEquals(10.0, scale.change(scores[2], scores[3]), 1e-9)
        assertEquals("+0:30 TUT", scale.changeText(10.0))
        // At the goal the next weight goes up a step; below it, it stays.
        assertEquals(22.0, scale.nextWeight(w[1].sets.first()), 0.0)
        assertEquals(22.0, scale.nextWeight(w[3].sets.first()), 0.0)
    }

    @Test fun tradeSetByHandWins() {
        // "6:00 at a weight = 1 lb more for 2:00": 4:00 per lb, so a 0.5 lb step is worth 2:00.
        val rules = s.copy(
            progressionRules = listOf(
                ProgressionRule("Curl", tutGoalSec = 360, loadStep = 0.5f, tradeFromSec = 360, tradeWeight = 1f, tradeToSec = 120),
            ),
        )
        val w = listOf(Workout(1, "Curl", 0, timedSets(9.0, 360)), Workout(2, "Curl", 72 * hour, timedSets(9.0, 300)))
        val scale = Predictions.scaleFor("Curl", w, rules) as ProgressScale.Tut
        assertEquals(120.0, scale.stepValueSec, 1e-9)
        assertEquals(ProgressScale.StepValueSource.SET, scale.stepValueSource)
        // At the 6:00 goal with 9 lb, 9.5 lb with no progress is 4:00.
        val score = scale.score(w[0].sets)!!
        assertEquals(240.0, scale.effortAt(score, 9.5), 1e-9)
    }

    @Test fun neverShowsZeroTime() {
        val scale = ProgressScale.Tut(WeightUnit.LBS, 360.0, 1.0, 360.0)
        assertEquals("under 0:01 TUT (too heavy)", scale.effortText(0.0))
        assertEquals("under 1 rep (too heavy)", ProgressScale.Reps(WeightUnit.LBS).effortText(0.2))
    }

    @Test fun supercompensationPeakLandsWherePredictionsPeak() {
        // Predictions shaped like the classic curve with its peak at 72 h, 4% high.
        val truth = com.example.tut.data.Supercompensation.Fit(72.0, 4.0)
        val curve = Predictions.gapGrid.map { CurvePoint(it, truth.at(it)) }
        val fit = com.example.tut.data.Supercompensation.fit(curve)
        assertEquals(72.0, fit.scaleHours, 6.0)
        assertEquals(4.0, fit.amplitude, 0.3)
        // Nothing but losses: no upside-down curve.
        assertEquals(0.0, com.example.tut.data.Supercompensation.fit(Predictions.gapGrid.map { CurvePoint(it, -2.0) }).amplitude, 0.0)
    }

    @Test fun curveGoesThroughThePrediction() {
        val usual = com.example.tut.data.Supercompensation.Fit(72.0, 30.0, assumed = true)
        // Predicted a loss after 3 days: the 3-day mark sits in the fatigue dip and the peak comes later.
        val loss = usual.through(72.0, -10.0)
        assertEquals(-10.0, loss.at(72.0), 0.5)
        assertTrue(loss.scaleHours > 72.0)
        assertTrue(loss.assumed)
        // Predicted more than the curve's height: the peak is the prediction.
        val big = usual.through(72.0, 40.0)
        assertEquals(40.0, big.amplitude, 1e-9)
        assertEquals(72.0, big.scaleHours, 0.5)
    }

    @Test fun lockedPredictionIsCheckedAgainstTheNextWorkout() {
        val last = Workout(1, "Bench press", 0, sets(100.0, 8))
        val m = Predictions.train(listOf(last), s)
        val scale = ProgressScale.Reps(WeightUnit.LBS)
        val p = LockedPrediction(
            id = 1, name = "Bench press", createdAt = 1, plannedFor = 72 * hour, baselineExerciseId = 1, baselineAt = 0,
            baselineStrength = scale.score(last.sets)!!,
            plan = PlannedWorkout(100.0, WeightUnit.LBS, 3, 0, 120, null, timed = false),
            predictedPct = 3.0, curve = Predictions.gapGrid.map { CurvePoint(it, 3.0) },
            trainedOn = 10, typicalMiss = null, verdict = null, source = "manual",
        )
        assertNull(m.check(p, listOf(last), s))
        // Did one more rep than last time: 100 × 9 vs 100 × 8 is about +2.7%, close to the +3% predicted.
        val done = Workout(2, "bench press", 72 * hour, sets(100.0, 9))
        val c = m.check(p, listOf(last, done), s)!!
        assertEquals(2.7, c.actualPct, 0.1)
        assertTrue(c.close)
        assertEquals(9.0, c.predictedEffortAtThatWeight, 0.2)
        // Two reps fewer than predicted is not close.
        val bad = m.check(p, listOf(last, done.copy(sets = sets(100.0, 7))), s)!!
        assertFalse(bad.close)
        assertTrue(abs(bad.missPct) > Predictions.CLOSE_PCT)
    }
}
