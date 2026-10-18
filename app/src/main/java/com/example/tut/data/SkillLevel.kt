package com.example.tut.data

import java.util.Calendar
import kotlin.math.max

/**
 * Skill level over time. Every day the skill is expected to get the daily skill capacity
 * (hours). Each hour put in is +1 point; each hour of the capacity left undone is −1 point.
 * With a 5 hour capacity: 0 h = −5, 2.5 h = 0, 5 h = +5, 7 h = +7.
 */
object SkillLevel {
    /** Level at the end of one day (for today: as if the day ended now). */
    data class Point(val dayStart: Long, val hours: Double, val level: Double)

    fun dailyChange(hours: Double, capacity: Double): Double = hours - max(0.0, capacity - hours)

    /** One point per day, from the day the skill was made (or its first log) through today. */
    fun series(createdAt: Long, logs: List<SkillLog>, capacity: Double, now: Long): List<Point> {
        val hoursByDay = logs.groupBy { dayStart(it.loggedAt) }.mapValues { (_, l) -> l.sumOf { it.hours } }
        val first = (listOf(createdAt) + logs.map { it.loggedAt }).min()
        val today = dayStart(now)
        val cal = Calendar.getInstance().apply { timeInMillis = dayStart(first) }
        val points = mutableListOf<Point>()
        var level = 0.0
        while (cal.timeInMillis <= today) {
            val day = cal.timeInMillis
            val hours = hoursByDay[day] ?: 0.0
            level += dailyChange(hours, capacity)
            points += Point(day, hours, level)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return points
    }

    fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
