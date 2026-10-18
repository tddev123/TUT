package com.example.tut

import com.example.tut.data.Pattern
import com.example.tut.data.Streaks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreakTest {
    @Test fun patterns() {
        assertEquals(Pattern.SAME, Streaks.pattern(listOf(2.0, 2.0, 2.0), 1.0))
        assertEquals(Pattern.STEADY, Streaks.pattern(listOf(4.0, 5.0, 4.5), 1.0))
        assertEquals(Pattern.UP, Streaks.pattern(listOf(185.0, 190.0, 195.0), 0.0))
        assertEquals(Pattern.DOWN, Streaks.pattern(listOf(3.0, 2.0, 1.0), 0.5))
        assertNull(Streaks.pattern(listOf(3.0, 1.0, 4.0), 0.5))
        assertNull(Streaks.pattern(listOf(3.0, null), 1.0))
        assertNull(Streaks.pattern(listOf(3.0), 1.0))
    }
}
