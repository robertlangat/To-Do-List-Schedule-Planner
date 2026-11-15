package com.robertlangat.todoplanner.core.recurrence

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Recurrence rules for repeating tasks. See docs/RECURRENCE.md for the full rule set.
 *
 * Key decisions:
 * - Occurrences are *dates* (wall-clock, time-zone independent). A task's optional time of day is
 *   applied afterwards in whatever zone the device is in, so a 09:00 task stays at 09:00 local
 *   after travel or a DST change.
 * - Monthly rules are anchored to the start date's day of month. If a month is too short the
 *   occurrence is clamped to the month's last day (Jan 31 -> Feb 28/29 -> Mar 31). The anchor
 *   never drifts.
 * - Yearly rules on Feb 29 fall on Feb 28 in non-leap years.
 * - COUNT includes occurrences later excluded with EXDATE (RFC 5545 semantics), so detaching or
 *   deleting a single occurrence never shifts the rest of the series.
 */
enum class Frequency { DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY }

sealed interface RecurrenceEnd {
    data object Never : RecurrenceEnd
    data class Until(val date: LocalDate) : RecurrenceEnd
    data class Count(val count: Int) : RecurrenceEnd {
        init { require(count >= 1) { "count must be >= 1" } }
    }
}

data class RecurrenceRule(
    val frequency: Frequency,
    val interval: Int = 1,
    /** Only used for WEEKLY. Empty means "the start date's weekday". */
    val byDays: Set<DayOfWeek> = emptySet(),
    val end: RecurrenceEnd = RecurrenceEnd.Never,
    /** Occurrence dates removed from the series (single-occurrence delete or detach). */
    val exDates: Set<LocalDate> = emptySet(),
) {
    init { require(interval in 1..999) { "interval must be 1..999" } }

    fun withExDate(date: LocalDate) = copy(exDates = exDates + date)

    /** Human-readable summary for the UI. */
    fun describe(start: LocalDate): String {
        val base = when (frequency) {
            Frequency.DAILY -> if (interval == 1) "Every day" else "Every $interval days"
            Frequency.WEEKDAYS -> "Every weekday"
            Frequency.WEEKLY -> {
                val days = effectiveDays(start).sorted().joinToString(", ") {
                    it.name.lowercase().replaceFirstChar(Char::titlecase).take(3)
                }
                (if (interval == 1) "Weekly" else "Every $interval weeks") + " on $days"
            }
            Frequency.MONTHLY -> (if (interval == 1) "Monthly" else "Every $interval months") +
                " on day ${start.dayOfMonth}" + if (start.dayOfMonth > 28) " (or last day)" else ""
            Frequency.YEARLY -> (if (interval == 1) "Yearly" else "Every $interval years") +
                " on ${start.month.name.lowercase().replaceFirstChar(Char::titlecase)} ${start.dayOfMonth}"
        }
        return base + when (val e = end) {
            RecurrenceEnd.Never -> ""
            is RecurrenceEnd.Until -> ", until ${e.date}"
            is RecurrenceEnd.Count -> ", ${e.count} times"
        }
    }

    internal fun effectiveDays(start: LocalDate): Set<DayOfWeek> =
        byDays.ifEmpty { setOf(start.dayOfWeek) }

    /** Serialises to a compact RRULE-like string stored in the database and sync files. */
    fun format(): String = buildList {
        add("FREQ=${frequency.name}")
        if (interval != 1) add("INTERVAL=$interval")
        if (byDays.isNotEmpty()) add("BYDAY=" + byDays.sorted().joinToString(",") { it.name.take(2) })
        when (val e = end) {
            RecurrenceEnd.Never -> Unit
            is RecurrenceEnd.Until -> add("UNTIL=${e.date}")
            is RecurrenceEnd.Count -> add("COUNT=${e.count}")
        }
        if (exDates.isNotEmpty()) add("EXDATE=" + exDates.sorted().joinToString(","))
    }.joinToString(";")

    companion object {
        private val dayCodes = DayOfWeek.entries.associateBy { it.name.take(2) }

        /** Returns null for blank or malformed input rather than throwing. */
        fun parse(text: String?): RecurrenceRule? {
            if (text.isNullOrBlank()) return null
            return runCatching {
                val parts = text.split(';').filter { it.isNotBlank() }.associate {
                    val (k, v) = it.split('=', limit = 2)
                    k.trim().uppercase() to v.trim()
                }
                val freq = Frequency.valueOf(parts.getValue("FREQ").uppercase())
                val end = when {
                    parts["UNTIL"] != null -> RecurrenceEnd.Until(LocalDate.parse(parts["UNTIL"]))
                    parts["COUNT"] != null -> RecurrenceEnd.Count(parts.getValue("COUNT").toInt())
                    else -> RecurrenceEnd.Never
                }
                RecurrenceRule(
                    frequency = freq,
                    interval = parts["INTERVAL"]?.toInt() ?: 1,
                    byDays = parts["BYDAY"]?.split(',')?.filter { it.isNotBlank() }
                        ?.map { dayCodes.getValue(it.trim().uppercase()) }?.toSet() ?: emptySet(),
                    end = end,
                    exDates = parts["EXDATE"]?.split(',')?.filter { it.isNotBlank() }
                        ?.map { LocalDate.parse(it.trim()) }?.toSet() ?: emptySet(),
                )
            }.getOrNull()
        }
    }
}

object RecurrenceEngine {
    private const val MAX_ITERATIONS = 50_000

    /**
     * Generates the raw series (before EXDATE filtering) starting at [start] in order, honouring
     * UNTIL and COUNT. The sequence is lazy, so callers can stop early.
     */
    fun series(start: LocalDate, rule: RecurrenceRule): Sequence<LocalDate> = sequence {
        var produced = 0
        val countLimit = (rule.end as? RecurrenceEnd.Count)?.count
        val until = (rule.end as? RecurrenceEnd.Until)?.date
        for (candidate in candidates(start, rule).take(MAX_ITERATIONS)) {
            if (candidate < start) continue
            if (until != null && candidate > until) break
            if (countLimit != null && produced >= countLimit) break
            produced++
            yield(candidate)
        }
    }

    /** Occurrences in [from, to] inclusive, with EXDATEs removed. */
    fun occurrencesBetween(start: LocalDate, rule: RecurrenceRule, from: LocalDate, to: LocalDate): List<LocalDate> =
        series(start, rule)
            .takeWhile { it <= to }
            .filter { it >= from && it !in rule.exDates }
            .toList()

    /** True if [date] is a live (not excluded) occurrence of the series. */
    fun isOccurrence(start: LocalDate, rule: RecurrenceRule, date: LocalDate): Boolean =
        date !in rule.exDates && series(start, rule).takeWhile { it <= date }.any { it == date }

    /** The next live occurrence on or after [from], or null if the series has ended. */
    fun nextOnOrAfter(start: LocalDate, rule: RecurrenceRule, from: LocalDate): LocalDate? =
        series(start, rule).firstOrNull { it >= from && it !in rule.exDates }

    /**
     * Splits a series for "this and future occurrences" edits.
     * Returns the rule for the original series (ending the day before [at]) and the rule for the new
     * series starting at [at]. COUNT is divided so the total number of occurrences is unchanged.
     * Returns a null first rule if [at] is the very first occurrence (the whole series is replaced).
     */
    fun split(start: LocalDate, rule: RecurrenceRule, at: LocalDate): Pair<RecurrenceRule?, RecurrenceRule> {
        val before = series(start, rule).takeWhile { it < at }.toList()
        val oldExDates = rule.exDates.filter { it < at }.toSet()
        val newExDates = rule.exDates.filter { it >= at }.toSet()
        val newEnd = when (val e = rule.end) {
            is RecurrenceEnd.Count -> RecurrenceEnd.Count((e.count - before.size).coerceAtLeast(1))
            else -> e
        }
        val newRule = rule.copy(end = newEnd, exDates = newExDates)
        if (before.isEmpty()) return null to newRule
        val oldEnd = when (rule.end) {
            is RecurrenceEnd.Count -> RecurrenceEnd.Count(before.size)
            else -> RecurrenceEnd.Until(at.minusDays(1))
        }
        return rule.copy(end = oldEnd, exDates = oldExDates) to newRule
    }

    private fun candidates(start: LocalDate, rule: RecurrenceRule): Sequence<LocalDate> = when (rule.frequency) {
        Frequency.DAILY -> generateSequence(start) { it.plusDays(rule.interval.toLong()) }
        Frequency.WEEKDAYS -> generateSequence(start) { it.plusDays(1) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        Frequency.WEEKLY -> {
            val days = rule.effectiveDays(start).sorted()
            val weekStart = start.minusDays((start.dayOfWeek.value - 1).toLong())
            generateSequence(0L) { it + 1 }.flatMap { week ->
                val monday = weekStart.plusWeeks(week * rule.interval)
                days.asSequence().map { monday.plusDays((it.value - 1).toLong()) }
            }
        }
        Frequency.MONTHLY -> {
            val anchor = start.dayOfMonth
            val first = YearMonth.from(start)
            generateSequence(0L) { it + 1 }.map { i ->
                clampDay(first.plusMonths(i * rule.interval), anchor)
            }
        }
        Frequency.YEARLY -> {
            val anchorMonth = start.monthValue
            val anchorDay = start.dayOfMonth
            generateSequence(0L) { it + 1 }.map { i ->
                clampDay(YearMonth.of(start.year, anchorMonth).plusYears(i * rule.interval), anchorDay)
            }
        }
    }

    private fun clampDay(month: YearMonth, day: Int): LocalDate =
        month.atDay(minOf(day, month.lengthOfMonth()))

    /** Days between two dates, used by callers for horizons. */
    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
}
