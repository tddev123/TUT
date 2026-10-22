package com.example.tut

import com.example.tut.data.AppSettings
import com.example.tut.data.Fatigue
import com.example.tut.data.Predictions
import com.example.tut.data.ProgressionRule
import com.example.tut.data.RecoveryRating
import com.example.tut.data.SetKind
import com.example.tut.data.WorkSet
import com.example.tut.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetBySetTest {
    // Hamstring curls on a 6:00 ladder, compared set by set; 30 seconds counts as a difference.
    private val s = AppSettings(
        progressionRules = listOf(ProgressionRule("hamstring curls", 360, 1f, tradeFromSec = 360, tradeWeight = 1f, tradeToSec = 120)),
        allSetsExercises = setOf("hamstring curls"),
    )
    private val day = 24 * 3_600_000L

    private fun w(id: Long, vararg tuts: Int, rating: RecoveryRating? = null) = Workout(
        id, "Hamstring Curls", id * 3 * day,
        tuts.mapIndexed { n, tut -> WorkSet(0, id, SetKind.WORKING, n + 1, weight = 20.0, tutSec = tut) },
        recovery = rating,
    )

    private fun scale(vararg ws: Workout) = Predictions.scaleFor("Hamstring Curls", ws.toList(), s)

    @Test fun eachSetAgainstTheSameSetLastTime() {
        // 4:00 → 3:00 worse, 4:20 → 4:10 same, 3:50 → 4:30 better: back to the same, 50%.
        val last = w(1, 240, 260, 230)
        val now = w(2, 180, 250, 270)
        val c = scale(last, now).compareSets(last.sets, now.sets, null)!!
        assertEquals(listOf(0.0, 0.5, 1.0), c.points)
        assertEquals(50.0, c.pct, 1e-9)
        assertEquals(0.0, scale(last, now).progress(last, now), 1e-9)
    }

    @Test fun twoOfThreeBetterIs66() {
        val last = w(1, 240, 240, 240)
        val c = scale(last).compareSets(last.sets, w(2, 300, 300, 200).sets, null)!!
        assertEquals(200.0 / 3, c.pct, 1e-9)
    }

    @Test fun theRatingCountsOnlyWhenTheSetsDontDecide() {
        val last = w(1, 240, 240, 240)
        val sc = scale(last)
        // All better: the rating is ignored.
        assertEquals(100.0, sc.compareSets(last.sets, w(2, 300, 300, 300).sets, RecoveryRating.KIND_OF_FATIGUED)!!.pct, 1e-9)
        // All the same: pulled halfway to "kind of fatigued" (35%).
        assertEquals(42.5, sc.compareSets(last.sets, w(2, 240, 250, 230).sets, RecoveryRating.KIND_OF_FATIGUED)!!.pct, 1e-9)
        // "Can't tell" changes nothing.
        assertEquals(50.0, sc.compareSets(last.sets, w(2, 240, 250, 230).sets, RecoveryRating.CANT_TELL)!!.pct, 1e-9)
    }

    @Test fun onlyTheSetsBothWorkoutsHaveAreCompared() {
        val last = w(1, 240, 240, 240)
        assertEquals(2, scale(last).compareSets(last.sets, w(2, 300, 300).sets, null)!!.points.size)
    }

    @Test fun offForOtherExercises() {
        val other = Workout(1, "bench press", 0, listOf(WorkSet(0, 1, SetKind.WORKING, 1, weight = 20.0, tutSec = 200)))
        assertNull(Predictions.scaleFor("bench press", listOf(other), s).setBySet)
    }

    @Test fun samePerformanceButFatiguedBuildsFatigue() {
        val list = listOf(
            w(1, 240, 240, 240),
            w(2, 240, 250, 230, rating = RecoveryRating.KIND_OF_FATIGUED), // same, felt fatigued: +1
            w(3, 240, 240, 240, rating = RecoveryRating.FATIGUED),         // same again: +1
            w(4, 240, 240, 240, rating = RecoveryRating.RECOVERED),        // same, felt recovered: −1
            w(5, 300, 300, 240, rating = RecoveryRating.FATIGUED),         // two better (67%+): cleared
        )
        val l = Fatigue.levels(list, Predictions.scaleFor("Hamstring Curls", list, s), s)
        assertEquals(listOf(0, 1, 2, 1, 0), list.map { l[it.exerciseId]!!.level })
    }
}
