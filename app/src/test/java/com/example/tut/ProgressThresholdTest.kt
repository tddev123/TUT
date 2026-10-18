package com.example.tut

import com.example.tut.data.AppSettings
import com.example.tut.data.BlockColor
import com.example.tut.data.Progress
import com.example.tut.data.SetKind
import com.example.tut.data.WeightUnit
import com.example.tut.data.WorkSet
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressThresholdTest {
    private fun set(w: Double, reps: Int = 8) = listOf(WorkSet(1, 1, SetKind.WORKING, 1, weight = w, unit = WeightUnit.LBS, reps = reps))

    @Test fun twoPoundsDown() {
        val prev = set(185.0)
        val cur = set(183.0)
        assertEquals(BlockColor.GRAY, Progress.compare(prev, cur, AppSettings(weightDownThreshold = 2.5f)))
        assertEquals(BlockColor.RED, Progress.compare(prev, cur, AppSettings(weightDownThreshold = 2f)))
        assertEquals(BlockColor.RED, Progress.compare(prev, cur, AppSettings(weightDownThreshold = 0f)))
    }

    @Test fun separateUpAndDown() {
        val s = AppSettings(weightUpThreshold = 5f, weightDownThreshold = 1f)
        assertEquals(BlockColor.GRAY, Progress.compare(set(185.0), set(187.5), s))
        assertEquals(BlockColor.GREEN, Progress.compare(set(185.0), set(190.0), s))
        assertEquals(BlockColor.GRAY, Progress.compare(set(185.0), set(185.0), AppSettings(weightUpThreshold = 0f, repsUpThreshold = 0)))
        assertEquals(BlockColor.RED, Progress.compare(set(185.0, 8), set(185.0, 7), s))
    }
}
