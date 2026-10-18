package com.example.tut

import com.example.tut.parse.Action
import com.example.tut.parse.CommandParser
import com.example.tut.parse.CommandParser.WordRun
import org.junit.Assert.assertEquals
import org.junit.Test

class StartStopRunTest {
    /** Feeds pieces of speech one after another; returns the in-a-row count of every Start heard. */
    private fun starts(vararg pieces: String): List<Int> {
        var run = WordRun()
        val out = mutableListOf<Int>()
        for (p in pieces) {
            val (actions, next) = CommandParser.parseWithRuns(p, run)
            run = next
            out += actions.filter { it.first == Action.Start }.map { it.second }
        }
        return out
    }

    @Test fun countsInOneBreath() {
        assertEquals(listOf(1, 2, 3), starts("start start start"))
        assertEquals(listOf(1, 2, 3), starts("Start, start, start."))
    }

    @Test fun countsAcrossPiecesOfSpeech() {
        assertEquals(listOf(1, 2, 3), starts("start", "start start"))
    }

    @Test fun anyWordInBetweenStartsOver() {
        assertEquals(listOf(1, 2, 1), starts("start start hello start"))
        assertEquals(listOf(1, 2, 1), starts("start start", "okay", "start"))
        assertEquals(listOf(1, 1), starts("start stop start"))
    }
}
