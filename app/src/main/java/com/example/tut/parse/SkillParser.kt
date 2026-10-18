package com.example.tut.parse

import com.example.tut.data.Skill

/**
 * Action words for the Skills database: a skill's name plus how long, e.g. "music 2 hours",
 * "tennis an hour and a half", "45 minutes of guitar". The TUT action words don't apply here.
 */
object SkillParser {

    data class Log(val skill: Skill, val hours: Double)

    /** What was heard: the logs, and whether a time was said without a known skill. */
    data class Result(val logs: List<Log>, val unmatchedTime: Boolean)

    private const val N = """\d+(?:\.\d+)?"""
    private const val DUR = """(?:($N) hours(?: and)?(?: ($N) minutes)?|(\d+):(\d{2}) hours|($N) minutes)"""
    private const val FILLER_AFTER = """(?: (?:for|practice|practiced|practicing|played|playing|today|session|is|was))*"""
    private const val FILLER_BEFORE = """(?: (?:of|on|for|doing|practicing|playing|in))*"""
    private val anyDuration = Regex("""\b$DUR\b""")

    fun normalize(text: String): String {
        val t = text.lowercase()
            .replace(Regex("""\bhalf (?:an? )?hour\b"""), "30 minutes")
            .replace(Regex("""\b(?:a )?quarter (?:of )?(?:an )?hour\b"""), "15 minutes")
            .replace(Regex("""\ban? hour and a half\b"""), "1.5 hours")
            .replace(Regex("""\ban? hour\b"""), "1 hour")
            .replace(Regex("""\b(?:to|too) hours?\b"""), "2 hours")
            .replace(Regex("""\b(?:for|fore) hours\b"""), "4 hours")
            .replace(Regex("""\b(\d+(?:\.\d+)?)\s*(?:h|hrs?)\b"""), "$1 hours")
        return CommandParser.normalize(t)
            .replace(Regex("""\b(?:hours?|hrs?|ours)\b"""), "hours")
    }

    private fun hours(m: MatchResult, offset: Int): Double? {
        val g = m.groupValues
        val h = when {
            g[offset + 1].isNotEmpty() -> g[offset + 1].toDouble() + (g[offset + 2].toDoubleOrNull() ?: 0.0) / 60
            g[offset + 3].isNotEmpty() -> g[offset + 3].toDouble() + g[offset + 4].toDouble() / 60
            g[offset + 5].isNotEmpty() -> g[offset + 5].toDouble() / 60
            else -> return null
        }
        return h.takeIf { it > 0 && it <= 24 }
    }

    fun parse(text: String, skills: List<Skill>): Result {
        val norm = normalize(text)
        if (norm.isBlank()) return Result(emptyList(), false)
        val found = mutableListOf<Triple<IntRange, Skill, Double>>()
        // Longer names first so "jazz piano" wins over "piano".
        for (skill in skills.sortedByDescending { it.name.length }) {
            val name = CommandParser.normalize(skill.name).takeIf { it.isNotBlank() } ?: continue
            val n = Regex.escape(name)
            val after = Regex("""\b$n\b$FILLER_AFTER $DUR\b""")
            val before = Regex("""\b$DUR$FILLER_BEFORE $n\b""")
            for (m in after.findAll(norm)) hours(m, 0)?.let { found += Triple(m.range, skill, it) }
            for (m in before.findAll(norm)) hours(m, 0)?.let { found += Triple(m.range, skill, it) }
        }
        found.sortWith(compareBy<Triple<IntRange, Skill, Double>> { it.first.first }.thenByDescending { it.first.last })
        val logs = mutableListOf<Log>()
        val used = mutableListOf<IntRange>()
        for ((range, skill, h) in found) {
            if (used.any { it.first <= range.last && range.first <= it.last }) continue
            used += range
            logs += Log(skill, h)
        }
        val unmatched = anyDuration.findAll(norm).any { d -> used.none { it.first <= d.range.first && d.range.last <= it.last } }
        return Result(logs, unmatched)
    }

    /** "2 hours", "1 hour", "1.5 hours", "45 minutes". */
    fun speakHours(h: Double): String {
        if (h < 1) {
            val m = Math.round(h * 60)
            return if (m == 1L) "1 minute" else "$m minutes"
        }
        val r = Math.round(h * 100) / 100.0
        return if (r == 1.0) "1 hour" else "${fmtNumber(r)} hours"
    }
}
