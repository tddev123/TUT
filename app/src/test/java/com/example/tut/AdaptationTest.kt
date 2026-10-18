package com.example.tut

import com.example.tut.data.Adaptation
import com.example.tut.data.BlockColor
import com.example.tut.data.Outcome
import com.example.tut.data.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptationTest {
    private val gap = 96.0 // 4 days

    @Test fun strongerLandsOnThePeak() {
        val s = Adaptation.scaleFor(gap, Outcome.STRONGER)
        assertEquals(1.0, Adaptation.level(gap, s), 1e-9)
        // dips first, back at baseline between day 2.5 and 3, then gone again afterwards
        assertTrue(Adaptation.level(24.0, s) < 0)
        assertEquals(0.0, Adaptation.level(0.7 * gap, s), 1e-9)
        assertTrue(0.7 * gap in 60.0..72.0)
        assertEquals(0.0, Adaptation.level(1.6 * gap, s), 1e-9)
    }

    @Test fun dipAndPeakAreTheSameSize() {
        val s = Adaptation.scaleFor(gap, Outcome.STRONGER)
        val low = (0..1000).minOf { Adaptation.level(gap * 2 * it / 1000, s) }
        assertEquals(-1.0, low, 1e-3)
    }

    @Test fun sameLandsBetweenDipAndPeak() {
        val s = Adaptation.scaleFor(gap, Outcome.SAME)
        assertEquals(0.0, Adaptation.level(gap, s), 1e-9)
        assertTrue(Adaptation.level(gap * 0.9, s) < 0) // the dip came just before
        assertTrue(Adaptation.level(gap * 1.1, s) > 0) // the peak was still to come
    }

    @Test fun weakerLandsInTheDip() {
        val s = Adaptation.scaleFor(gap, Outcome.WEAKER)
        assertEquals(-1.0, Adaptation.level(gap, s), 1e-9)
    }

    @Test fun goldBlock() {
        assertEquals(BlockColor.GOLD, Progress.aggregate(listOf(BlockColor.GOLD)))
        assertEquals(BlockColor.GREEN, Progress.aggregate(listOf(BlockColor.GREEN)))
        assertEquals(BlockColor.GRAY, Progress.aggregate(listOf(BlockColor.GOLD, BlockColor.RED)))
        assertEquals(Outcome.STRONGER, Outcome.of(BlockColor.GOLD))
        assertEquals(Outcome.STRONGER, Outcome.of(BlockColor.GOLD_DARK))
        assertEquals(BlockColor.GOLD_MEDIUM, Progress.aggregate(listOf(BlockColor.GOLD_DARK, BlockColor.GOLD_MEDIUM, BlockColor.GREEN)))
        assertEquals(BlockColor.GOLD_DARK, BlockColor.GREEN.nextInStreak())
        assertEquals(BlockColor.GOLD_MEDIUM, BlockColor.GOLD_DARK.nextInStreak())
        assertEquals(BlockColor.GOLD, BlockColor.GOLD_MEDIUM.nextInStreak())
        assertEquals(BlockColor.GOLD, BlockColor.GOLD.nextInStreak())
        assertEquals(BlockColor.RED_DARK, BlockColor.RED.nextWeaker())
        assertEquals(BlockColor.BLACK, BlockColor.RED_DARK.nextWeaker())
        assertEquals(BlockColor.BLACK, BlockColor.BLACK.nextWeaker())
        assertEquals(Outcome.WEAKER, Outcome.of(BlockColor.BLACK))
        assertEquals(BlockColor.BLACK, Progress.aggregate(listOf(BlockColor.RED, BlockColor.BLACK, BlockColor.GRAY)))
    }
}
