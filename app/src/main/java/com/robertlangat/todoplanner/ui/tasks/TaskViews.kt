package com.robertlangat.todoplanner.ui.tasks

import com.robertlangat.todoplanner.domain.AppData
import com.robertlangat.todoplanner.domain.TaskOccurrence
import com.robertlangat.todoplanner.domain.dueLocalDate
import com.robertlangat.todoplanner.domain.sortOccurrences
import com.robertlangat.todoplanner.ui.components.formatDate
import java.time.LocalDate

sealed interface TaskView {
    val title: String
    data object Inbox : TaskView { override val title = "Inbox" }
    data object Today : TaskView { override val title = "Today" }
    data object Upcoming : TaskView { override val title = "Upcoming" }
    data object Starred : TaskView { override val title = "Starred" }
    data object Completed : TaskView { override val title = "Completed" }
    data class InList(val listId: String, override val title: String) : TaskView
}

enum class DateFilter(val label: String) { ANY("Any date"), TODAY("Today"), THIS_WEEK("Next 7 days"), OVERDUE("Overdue"), NO_DATE("No date") }
enum class StatusFilter(val label: String) { OPEN("Open"), COMPLETED("Completed"), ALL("All") }

data class Filters(
    /** null = any list; "" = Inbox only. */
    val listId: String? = null,
    val priorities: Set<Int> = emptySet(),
    val date: DateFilter = DateFilter.ANY,
    val status: StatusFilter = StatusFilter.OPEN,
) {
    val active: Boolean get() = listId != null || priorities.isNotEmpty() || date != DateFilter.ANY || status != StatusFilter.OPEN
}

data class Section(val title: String?, val items: List<TaskOccurrence>)

object TaskViews {

    /** Open "current" occurrences plus completed one-offs, for list-like views and search. */
    private fun candidates(data: AppData, today: LocalDate, status: StatusFilter): List<TaskOccurrence> = data.tasks.flatMap { t ->
        buildList {
            if (t.rrule == null) {
                val occ = data.occurrence(t, t.dueLocalDate)
                if (status == StatusFilter.ALL || (status == StatusFilter.OPEN) != occ.completed) add(occ)
            } else {
                if (status != StatusFilter.COMPLETED) data.currentOccurrence(t, today)?.let { add(it) }
                if (status != StatusFilter.OPEN) {
                    data.completedDates(t.id).sortedDescending().take(5).forEach { add(data.occurrence(t, it)) }
                }
            }
        }
    }

    private fun applyFilters(items: List<TaskOccurrence>, f: Filters, today: LocalDate) = items.filter { o ->
        (f.listId == null || (o.task.listId ?: "") == f.listId) &&
            (f.priorities.isEmpty() || o.task.priority in f.priorities) &&
            when (f.date) {
                DateFilter.ANY -> true
                DateFilter.TODAY -> o.date == today
                DateFilter.THIS_WEEK -> o.date != null && o.date >= today && o.date <= today.plusDays(6)
                DateFilter.OVERDUE -> o.isOverdue(today)
                DateFilter.NO_DATE -> o.date == null
            }
    }

    fun build(view: TaskView, data: AppData, today: LocalDate, query: String, filters: Filters): List<Section> {
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            val hits = candidates(data, today, filters.status).filter { o ->
                o.task.title.lowercase().contains(q) || o.task.notes.lowercase().contains(q) ||
                    data.subtasksOf(o.task.id).any { it.title.lowercase().contains(q) } ||
                    (o.list?.name?.lowercase()?.contains(q) == true)
            }
            return listOf(Section("Search results", sortOccurrences(applyFilters(hits, filters, today))))
        }
        return when (view) {
            TaskView.Inbox -> listOf(Section(null, sortOccurrences(applyFilters(
                candidates(data, today, filters.status).filter { it.task.listId == null }, filters, today))))

            is TaskView.InList -> listOf(Section(null, sortOccurrences(applyFilters(
                candidates(data, today, filters.status).filter { it.task.listId == view.listId }, filters, today))))

            TaskView.Starred -> listOf(Section(null, sortOccurrences(applyFilters(
                candidates(data, today, filters.status).filter { it.task.starred }, filters, today))))

            TaskView.Today -> {
                val todays = data.occurrencesBetween(today, today)
                val overdue = data.occurrencesBetween(today.minusDays(30), today.minusDays(1)).filter { !it.completed }
                val f = filters.copy(status = StatusFilter.ALL)
                val showDone = filters.status != StatusFilter.OPEN
                listOfNotNull(
                    Section("Overdue", sortOccurrences(applyFilters(overdue, f, today))).takeIf { it.items.isNotEmpty() },
                    Section("Today", sortOccurrences(applyFilters(todays.filter { showDone || !it.completed }, f, today))),
                    Section("Done today", sortOccurrences(applyFilters(todays.filter { it.completed }, f, today)))
                        .takeIf { !showDone && it.items.isNotEmpty() },
                )
            }

            TaskView.Upcoming -> applyFilters(data.occurrencesBetween(today.plusDays(1), today.plusDays(30)), filters.copy(status = StatusFilter.ALL), today)
                .filter { filters.status == StatusFilter.ALL || (filters.status == StatusFilter.OPEN) != it.completed }
                .groupBy { it.date!! }.toSortedMap()
                .map { (date, items) -> Section(formatDate(date, today), sortOccurrences(items)) }

            TaskView.Completed -> {
                val oneOffs = data.tasks.filter { it.rrule == null && it.completed }.map { data.occurrence(it, it.dueLocalDate) }
                val recurring = data.occurrences.filter { it.completed }.mapNotNull { o ->
                    data.tasks.firstOrNull { it.id == o.taskId }?.let { data.occurrence(it, LocalDate.ofEpochDay(o.date)) }
                }
                val all = applyFilters(oneOffs + recurring, filters.copy(status = StatusFilter.ALL), today)
                listOf(Section(null, all.sortedByDescending { o ->
                    if (o.isRecurring) data.occurrences.firstOrNull { it.taskId == o.task.id && it.date == o.date?.toEpochDay() }?.completedAt ?: 0
                    else o.task.completedAt ?: 0
                }))
            }
        }
    }

    /** Focus view: up to three open tasks for today/overdue, highest priority first. */
    fun focus(data: AppData, today: LocalDate): List<TaskOccurrence> {
        val due = (data.occurrencesBetween(today.minusDays(30), today)).filter { !it.completed }
        val undatedImportant = data.tasks.filter { it.dueDate == null && !it.completed && (it.priority >= 2 || it.starred) }
            .map { data.occurrence(it, null) }
        return (due + undatedImportant)
            .sortedWith(compareByDescending<TaskOccurrence> { it.task.priority }.thenByDescending { it.task.starred }
                .thenBy { it.date ?: LocalDate.MAX }.thenBy { it.time })
            .take(3)
    }
}
