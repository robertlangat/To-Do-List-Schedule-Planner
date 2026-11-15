package com.robertlangat.todoplanner.core.progress

import com.robertlangat.todoplanner.core.recurrence.RecurrenceEngine
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import java.time.DayOfWeek
import java.time.LocalDate

data class ProgressTask(
    val id: String,
    val listId: String?,
    /** Due date (one-off) or series start (recurring). Null = undated. */
    val date: LocalDate?,
    val rule: RecurrenceRule?,
    /** One-off: whether completed. Ignored for recurring tasks. */
    val completed: Boolean,
    /** Recurring: the occurrence dates that were completed. */
    val completedDates: Set<LocalDate> = emptySet(),
)

data class Ratio(val done: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
    operator fun plus(o: Ratio) = Ratio(done + o.done, total + o.total)
}

data class ProgressStats(
    val today: Ratio,
    val thisWeek: Ratio,
    val overdue: Int,
    val byList: Map<String?, Ratio>,
)

/**
 * Progress counts every occurrence of a recurring task individually: a daily habit counts as 7
 * items in a week, and completing Monday's occurrence doesn't complete Tuesday's.
 */
object ProgressCalculator {
    const val OVERDUE_LOOKBACK_DAYS = 30L

    fun compute(tasks: List<ProgressTask>, today: LocalDate, weekStart: DayOfWeek = DayOfWeek.MONDAY): ProgressStats {
        val startOfWeek = today.minusDays(((today.dayOfWeek.value - weekStart.value + 7) % 7).toLong())
        val endOfWeek = startOfWeek.plusDays(6)
        var todayR = Ratio(0, 0)
        var weekR = Ratio(0, 0)
        var overdue = 0
        val byList = LinkedHashMap<String?, Ratio>()

        for (t in tasks) {
            if (t.rule != null && t.date != null) {
                val week = RecurrenceEngine.occurrencesBetween(t.date, t.rule, startOfWeek, endOfWeek)
                val w = Ratio(week.count { it in t.completedDates }, week.size)
                weekR += w
                byList[t.listId] = (byList[t.listId] ?: Ratio(0, 0)) + w
                if (RecurrenceEngine.occurrencesBetween(t.date, t.rule, today, today).isNotEmpty()) {
                    todayR += Ratio(if (today in t.completedDates) 1 else 0, 1)
                }
                overdue += RecurrenceEngine.occurrencesBetween(t.date, t.rule, today.minusDays(OVERDUE_LOOKBACK_DAYS), today.minusDays(1))
                    .count { it !in t.completedDates }
            } else {
                val done = if (t.completed) 1 else 0
                byList[t.listId] = (byList[t.listId] ?: Ratio(0, 0)) + Ratio(done, 1)
                if (t.date == today) todayR += Ratio(done, 1)
                if (t.date != null && t.date in startOfWeek..endOfWeek) weekR += Ratio(done, 1)
                if (t.date != null && t.date < today && !t.completed) overdue++
            }
        }
        return ProgressStats(todayR, weekR, overdue, byList)
    }
}
