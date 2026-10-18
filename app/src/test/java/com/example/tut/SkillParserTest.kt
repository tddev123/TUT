package com.example.tut

import com.example.tut.data.Skill
import com.example.tut.data.SkillLevel
import com.example.tut.data.SkillLog
import com.example.tut.parse.SkillParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SkillParserTest {
    private val music = Skill(1, "Music", 0, 0)
    private val tennis = Skill(2, "Tennis", 1, 0)
    private val jazz = Skill(3, "Jazz Piano", 2, 0)
    private val skills = listOf(music, tennis, jazz)

    private fun p(text: String) = SkillParser.parse(text, skills).logs.map { it.skill.name to it.hours }

    @Test fun nameThenHours() = assertEquals(listOf("Music" to 2.0), p("Music, 2 hours."))
    @Test fun spelledOut() = assertEquals(listOf("Music" to 2.0), p("music two hours"))
    @Test fun anHourAndAHalf() = assertEquals(listOf("Tennis" to 1.5), p("tennis an hour and a half"))
    @Test fun twoAndAHalf() = assertEquals(listOf("Tennis" to 2.5), p("tennis two and a half hours"))
    @Test fun minutes() = assertEquals(listOf("Music" to 0.75), p("45 minutes of music"))
    @Test fun hoursAndMinutes() = assertEquals(listOf("Music" to 1.5), p("music for 1 hour and 30 minutes"))
    @Test fun misheardTo() = assertEquals(listOf("Music" to 2.0), p("music to hours"))
    @Test fun twoSkills() = assertEquals(listOf("Music" to 1.0, "Tennis" to 3.0), p("music one hour and tennis three hours"))
    @Test fun longestName() = assertEquals(listOf("Jazz Piano" to 2.0), p("jazz piano 2 hours"))
    @Test fun tutWordsIgnored() = assertEquals(emptyList<Pair<String, Double>>(), p("working set 1 10 reps done"))

    @Test fun unknownSkill() {
        val r = SkillParser.parse("guitar 2 hours", skills)
        assertTrue(r.logs.isEmpty())
        assertTrue(r.unmatchedTime)
    }

    @Test fun dailyChange() {
        assertEquals(-5.0, SkillLevel.dailyChange(0.0, 5.0), 1e-9)
        assertEquals(5.0, SkillLevel.dailyChange(5.0, 5.0), 1e-9)
        assertEquals(0.0, SkillLevel.dailyChange(2.5, 5.0), 1e-9)
        assertEquals(7.0, SkillLevel.dailyChange(7.0, 5.0), 1e-9)
    }

    @Test fun fiveMissedDays() {
        val cal = Calendar.getInstance().apply { set(2026, 0, 10, 12, 0) }
        val created = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 4)
        val pts = SkillLevel.series(created, emptyList(), 5.0, cal.timeInMillis)
        assertEquals(listOf(-5.0, -10.0, -15.0, -20.0, -25.0), pts.map { it.level })
    }

    @Test fun hoursRaiseTheLine() {
        val cal = Calendar.getInstance().apply { set(2026, 0, 10, 9, 0) }
        val created = cal.timeInMillis
        val logs = listOf(SkillLog(1, 1, 3.0, created), SkillLog(2, 1, 2.0, created + 3_600_000))
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val pts = SkillLevel.series(created, logs, 5.0, cal.timeInMillis)
        assertEquals(listOf(5.0, 0.0), pts.map { it.level })
    }
}
