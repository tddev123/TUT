package com.example.tut

import com.example.tut.data.AppSettings
import com.example.tut.data.Bulletins
import com.example.tut.data.RirLevel
import com.example.tut.data.SetKind
import com.example.tut.data.WeightUnit
import com.example.tut.data.WorkSet
import org.junit.Assert.assertEquals
import org.junit.Test

class BulletinTest {
    @Test fun summarizesWorkingSetsOnly() {
        val sets = listOf(
            WorkSet(1, 1, SetKind.WARMUP, 1, weight = 95.0, reps = 10),
            WorkSet(2, 1, SetKind.WORKING, 1, weight = 185.0, unit = WeightUnit.LBS, reps = 8, rirValue = 2),
            WorkSet(3, 1, SetKind.WORKING, 2, weight = 185.0, unit = WeightUnit.LBS, reps = 6, rirLevel = RirLevel.LOW),
            WorkSet(4, 1, SetKind.WORKING, 3), // never filled in
        )
        val s = AppSettings(rirLow = 1)
        val w = Bulletins.summarize(0, sets, s)
        assertEquals(185.0, w.topWeight!!, 1e-9)
        assertEquals(2, w.workingSets)
        assertEquals(7.0, w.avgReps!!, 1e-9)
        assertEquals(1.5, w.avgRir!!, 1e-9)
    }
}
