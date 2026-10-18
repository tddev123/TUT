package com.example.tut

import com.example.tut.data.AppSettings
import com.example.tut.data.Fatigue
import com.example.tut.data.Predictions
import com.example.tut.data.ProgressionRule
import com.example.tut.data.SetKind
import com.example.tut.data.WorkSet
import com.example.tut.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FatigueTest {
    // 1 lb steps at a 6:00 goal; 6:00 at a weight = 1 lb more for 2:00, so 1 lb is worth 4:00.
    private val s = AppSettings(
        progressionRules = listOf(ProgressionRule("bench press", 360, 1f, tradeFromSec = 360, tradeWeight = 1f, tradeToSec = 120)),
    )
    private val day = 24 * 3_600_000L

    private fun w(id: Long, vararg sets: Pair<Double, Int>, manual: Int? = null) = Workout(
        id, "bench press", id * 3 * day,
        sets.mapIndexed { n, (weight, tut) -> WorkSet(0, id, SetKind.WORKING, n + 1, weight = weight, tutSec = tut) },
        fatigueManual = manual,
    )

    private fun levels(vararg ws: Workout): List<Int> {
        val list = ws.toList()
        val scale = Predictions.scaleFor("bench press", list, s)
        val l = Fatigue.levels(list, scale, s)
        return list.map { l[it.exerciseId]!!.level }
    }

    private val a = w(1, 8.5 to 360, 9.5 to 120) // 6:00, then 9.5 for 2:00: as good as each other
    private val b = w(2, 9.5 to 60)              // 9.5 for only 1:00: worse by 1:00
    private val c = w(3, 8.5 to 120)             // 8.5 for 2:00: worse by 3:00, the big drop
    private val d = w(4, 8.5 to 100)             // 8.5 for 1:40: within 0:30, the same
    private val e = w(5, 8.5 to 200)             // 8.5 for 3:20: better, cleared

    @Test fun declinesAddUpAndTheSameKeepsTheLevel() {
        assertEquals(listOf(0, 1, 3, 3, 0), levels(a, b, c, d, e))
    }

    @Test fun smallerDropsAddOneLevel() {
        // With two levels only from 4:00 down, the 3:00 drop is one level: 0, 1, 2, then 2 stays.
        val gentle = s.copy(fatigueBigDropSec = 240)
        val list = listOf(a, b, c, d)
        val l = Fatigue.levels(list, Predictions.scaleFor("bench press", list, gentle), gentle)
        assertEquals(listOf(0, 1, 2, 2), list.map { l[it.exerciseId]!!.level })
    }

    @Test fun aLevelSetByHandIsCountedOnFrom() {
        val manual = c.copy(fatigueManual = 1)
        assertEquals(listOf(0, 1, 1, 1, 0), levels(a, b, manual, d, e))
        val l = Fatigue.levels(listOf(a, b, manual), Predictions.scaleFor("bench press", listOf(a, b, manual), s), s)
        assertTrue(l[3]!!.manual)
    }

    @Test fun theSameAgainTakesALevelOff() {
        // At level 2 after 8.5 for 2:00, 8.5 for 2:10 is about the same: one level off.
        val start = w(1, 8.5 to 120, manual = 2)
        assertEquals(listOf(2, 1), levels(start, w(2, 8.5 to 130)))
        // Exactly the same takes one off too; never below 0.
        assertEquals(listOf(2, 1, 0, 0), levels(start, w(2, 8.5 to 120), w(3, 8.5 to 120), w(4, 8.5 to 120)))
        // A little worse (1:40) keeps it.
        assertEquals(listOf(2, 2), levels(start, w(2, 8.5 to 100)))
    }

    @Test fun bigWeightDropAddsTwo() {
        // 6.5 lb for 5:00 after 8.5 for 3:20: 2 lb lighter (the default threshold) is two levels.
        val f = w(6, 6.5 to 300)
        assertEquals(listOf(0, 2), levels(e, f))
    }
}
