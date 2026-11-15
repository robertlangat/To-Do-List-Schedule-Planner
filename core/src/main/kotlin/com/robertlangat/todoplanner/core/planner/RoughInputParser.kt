package com.robertlangat.todoplanner.core.planner

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Heuristic parser for pasted task lists and short goal descriptions, used by the offline planner.
 * It is deliberately predictable rather than clever: one task per line / bullet / "and"-clause,
 * with durations ("45m", "1.5h", "two hours"), priorities ("!", "urgent", "!low") and simple
 * deadlines ("today", "tomorrow", "by friday") pulled out of the text.
 * Sentences about availability ("I have two hours after work") become an [AvailabilityHint].
 */
object RoughInputParser {

    data class AvailabilityHint(val date: LocalDate, val start: LocalTime?, val minutes: Int?)

    data class Result(val tasks: List<PlannerTask>, val availability: AvailabilityHint?)

    private val numberWords = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
        "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "half an" to 0.5,
        "fifteen" to 15.0, "twenty" to 20.0, "thirty" to 30.0, "forty-five" to 45.0, "forty five" to 45.0,
    )
    private val numberPattern = "(\\d+(?:\\.\\d+)?|half an|forty[- ]five|a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty)"
    private val durationRegex = Regex("\\(?\\b$numberPattern\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes)\\b\\)?", RegexOption.IGNORE_CASE)
    private val availabilityRegex = Regex("\\b(i have|i've got|i got|i'm free|i am free|free for|available)\\b", RegexOption.IGNORE_CASE)
    private val fillerPrefix = Regex(
        "^(please\\s+)?(help me( to)?|i need to|i have to|i must|i want to|i should|remind me to|plan( out)?|also|then|and|to)\\s+",
        RegexOption.IGNORE_CASE,
    )
    private val bullet = Regex("^\\s*([-*•]|\\d+[.)]|\\[ ?\\])\\s*")
    private val weekdays = DayOfWeek.entries.associateBy { it.name.lowercase() }

    fun parse(text: String, today: LocalDate, workEnds: LocalTime = LocalTime.of(17, 30)): Result {
        var availability: AvailabilityHint? = null
        val tasks = mutableListOf<PlannerTask>()
        val sentences = text.split(Regex("\\n|;|\\?(?=\\s|$)|\\.(?=\\s|$)"))
            .flatMap { line -> if (bullet.containsMatchIn(line)) listOf(line) else line.split(Regex("(?<=[a-z]),\\s+")) }
            .map { it.trim() }.filter { it.isNotEmpty() }

        for (sentence in sentences) {
            if (availabilityRegex.containsMatchIn(sentence) && availability == null) {
                availability = parseAvailability(sentence, today, workEnds)
                continue
            }
            val clauses = sentence.replace(bullet, "")
                .split(Regex("\\s+(?:and then|and also|then|and)\\s+", RegexOption.IGNORE_CASE))
            for (clause in clauses) {
                val task = parseTask(clause, today, tasks.size) ?: continue
                tasks += task
            }
        }
        return Result(tasks, availability)
    }

    private fun parseAvailability(sentence: String, today: LocalDate, workEnds: LocalTime): AvailabilityHint {
        val lower = sentence.lowercase()
        val minutes = durationRegex.find(lower)?.let { toMinutes(it) }
        val date = when {
            "tomorrow" in lower -> today.plusDays(1)
            else -> weekdays.entries.firstOrNull { lower.contains(it.key) }?.let { today.with(TemporalAdjusters.next(it.value)) } ?: today
        }
        val start = when {
            "after work" in lower -> workEnds
            "morning" in lower -> LocalTime.of(8, 0)
            "lunch" in lower -> LocalTime.of(12, 30)
            "afternoon" in lower -> LocalTime.of(14, 0)
            "evening" in lower || "tonight" in lower -> LocalTime.of(18, 30)
            else -> Regex("\\bat (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(lower)?.let { m ->
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                if (m.groupValues[3] == "pm" && h < 12) h += 12
                if (m.groupValues[3] == "am" && h == 12) h = 0
                if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
            }
        }
        return AvailabilityHint(date, start, minutes)
    }

    private fun parseTask(raw: String, today: LocalDate, index: Int): PlannerTask? {
        var t = raw.trim().trimEnd(',', '!').trim()
        if (t.isEmpty()) return null
        var priority = PlanPriority.NONE
        val lower0 = t.lowercase()
        when {
            "!low" in lower0 || "(low)" in lower0 -> priority = PlanPriority.LOW
            "!!" in raw || "urgent" in lower0 || "asap" in lower0 || "important" in lower0 || "!high" in lower0 -> priority = PlanPriority.HIGH
            raw.trim().endsWith("!") || "!med" in lower0 -> priority = PlanPriority.MEDIUM
        }
        t = t.replace(Regex("!+(low|med|medium|high)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(urgent|asap)\\b", RegexOption.IGNORE_CASE), "")

        var duration: Int? = null
        durationRegex.find(t)?.let { m -> duration = toMinutes(m); t = t.removeRange(m.range) }

        var deadline: LocalDateTime? = null
        val lower = t.lowercase()
        Regex("\\b(by |before |due )?(today|tonight|tomorrow(?:'s)?)\\b").find(lower)?.let { m ->
            val day = if (m.groupValues[2].startsWith("tomorrow")) today.plusDays(1) else today
            deadline = day.atTime(23, 59)
            // Keep possessive phrases like "tomorrow's errands" readable; drop "by today".
            if (m.groupValues[1].isNotEmpty()) t = t.removeRange(m.range)
        }
        Regex("\\b(by|before|due) (monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").find(t.lowercase())?.let { m ->
            deadline = today.with(TemporalAdjusters.nextOrSame(weekdays.getValue(m.groupValues[2]))).atTime(23, 59)
            t = t.removeRange(m.range)
        }

        t = t.replace(Regex("\\s{2,}"), " ").trim().trim(',', '-', ':', '(', ')').trim()
        repeat(3) { t = t.replace(fillerPrefix, "").trim() }
        if (t.length < 2) return null
        val title = t.replaceFirstChar { it.uppercase() }
        return PlannerTask(
            id = "draft-$index",
            title = title,
            durationMinutes = duration ?: defaultDuration(title),
            priority = priority,
            deadline = deadline,
        )
    }

    private fun toMinutes(m: MatchResult): Int {
        val key = m.groupValues[1].lowercase().replace('-', ' ')
        val n = key.toDoubleOrNull() ?: numberWords[key] ?: 1.0
        val unit = m.groupValues[2].lowercase()
        val minutes = if (unit.startsWith("h")) n * 60 else n
        return minutes.toInt().coerceIn(5, 12 * 60)
    }

    /** Sensible defaults when the user gives no duration. */
    fun defaultDuration(title: String): Int {
        val l = title.lowercase()
        return when {
            listOf("revise", "study", "exam", "essay", "write", "assignment", "project").any { it in l } -> 60
            listOf("errand", "shopping", "groceries", "gym", "workout", "clean").any { it in l } -> 45
            listOf("call", "email", "text", "pay", "book", "reply").any { it in l } -> 15
            else -> 30
        }
    }
}
