package com.example.tut

import com.example.tut.data.ExerciseNames
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseNamesTest {
    @Test
    fun benchIsBenchPress() {
        assertEquals("Bench press", ExerciseNames.canonical("bench"))
        assertEquals("Bench press", ExerciseNames.canonical(" Bench "))
        assertEquals("bench press", ExerciseNames.canonical("bench press"))
        assertEquals("Incline bench", ExerciseNames.canonical("Incline bench"))
        assertEquals("Squat", ExerciseNames.canonical("Squat"))
    }
}
