package com.example.tut

import com.example.tut.data.WordAlias
import com.example.tut.data.WordAliases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordAliasesTest {
    private val aliases = listOf(
        WordAlias("bench", "bench press"),
        WordAlias("poles", "pull-ups"),
        WordAlias("", ""),
        WordAlias("squat", ""),
    )

    @Test fun replacesWholeWords() {
        assertEquals("exercise bench press", WordAliases.apply("exercise bench", aliases))
        assertEquals("Exercise pull-ups.", WordAliases.apply("Exercise Poles.", aliases))
        assertEquals("benches", WordAliases.apply("benches", aliases))
    }

    @Test fun leavesTheFullWordAlone() {
        assertEquals("exercise bench press", WordAliases.apply("exercise bench press", aliases))
    }

    @Test fun ignoresHalfFilledPairs() {
        assertEquals("exercise squat", WordAliases.apply("exercise squat", aliases))
    }

    @Test fun exerciseNameUsesTheTypedSpelling() {
        assertEquals("pull-ups", WordAliases.nameFor("pull ups", aliases))
        assertEquals("pull-ups", WordAliases.nameFor("Poles", aliases))
        assertNull(WordAliases.nameFor("deadlift", aliases))
    }
}
