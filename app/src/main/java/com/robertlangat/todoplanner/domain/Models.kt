package com.robertlangat.todoplanner.domain

import com.robertlangat.todoplanner.core.recurrence.RecurrenceEngine
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import com.robertlangat.todoplanner.data.db.ListEntity
import com.robertlangat.todoplanner.data.db.OccurrenceEntity
import com.robertlangat.todoplanner.data.db.SubtaskEntity
import com.robertlangat.todoplanner.data.db.TaskEntity
import java.time.LocalDate
import java.time.LocalTime

enum class Priority(val value: Int, val label: String, val marks: String) {
    NONE(0, "No priority", ""), LOW(1, "Low", "!"), MEDIUM(2, "Medium", "!!"), HIGH(3, "High", "!!!");
    companion object { fun of(v: Int) = entries.firstOrNull { it.value == v } ?: NONE }
}

enum class EditScope { ALL, THIS_OCCURRENCE, THIS_AND_FUTURE }

data class SubtaskDraft(val id: String? = null, val title: String, val done: Boolean = false)

data class TaskDraft(
    val title: String,
    val notes: String = "",
    val listId: String? = null,
    val priority: Int = 0,
    val starred: Boolean = false,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val rule: RecurrenceRule? = null,
    val reminderOffsets: List<Int> = emptyList(),
    val estimatedMinutes: Int? = null,
    val subtasks: List<SubtaskDraft> = emptyList(),
)

val TaskEntity.dueLocalDate: LocalDate? get() = dueDate?.let(LocalDate::ofEpochDay)
val TaskEntity.dueLocalTime: LocalTime? get() = dueTimeMinutes?.let { LocalTime.of(it / 60, it % 60) }
val TaskEntity.rule: RecurrenceRule? get() = RecurrenceRule.parse(rrule)
val TaskEntity.offsets: List<Int> get() = reminderOffsets.split(',').mapNotNull { it.trim().toIntOrNull() }
fun LocalTime.toMinutes() = hour * 60 + minute

/** One concrete thing to show in a list: a one-off task, or a single occurrence of a recurring one. */
data class TaskOccurrence(
    val task: TaskEntity,
    /** Occurrence date; null for undated tasks. */
    val date: LocalDate?,
    val completed: Boolean,
    val list: ListEntity?,
    val subtasksDone: Int,
    val subtasksTotal: Int,
) {
    val key: String get() = "${task.id}@${date ?: "none"}"
    val isRecurring: Boolean get() = task.rrule != null
    val time: LocalTime? get() = task.dueLocalTime
    val priority: Priority get() = Priority.of(task.priority)
    fun isOverdue(today: LocalDate) = !completed && date != null && date < today
}

/** Everything in the database (live rows only), with helpers for building views. */
data class AppData(
    val tasks: List<TaskEntity> = emptyList(),
    val lists: List<ListEntity> = emptyList(),
    val subtasks: List<SubtaskEntity> = emptyList(),
    val occurrences: List<OccurrenceEntity> = emptyList(),
) {
    val listsById: Map<String, ListEntity> by lazy { lists.associateBy { it.id } }
    private val subtasksByTask by lazy { subtasks.groupBy { it.taskId } }
    private val completedByTask: Map<String, Set<LocalDate>> by lazy {
        occurrences.filter { it.completed }.groupBy { it.taskId }
            .mapValues { (_, v) -> v.map { LocalDate.ofEpochDay(it.date) }.toSet() }
    }

    fun subtasksOf(taskId: String) = subtasksByTask[taskId].orEmpty()
    fun completedDates(taskId: String): Set<LocalDate> = completedByTask[taskId].orEmpty()

    fun occurrence(task: TaskEntity, date: LocalDate?): TaskOccurrence {
        val subs = subtasksOf(task.id)
        val done = if (task.rrule != null && date != null) date in completedDates(task.id) else task.completed
        return TaskOccurrence(task, date, done, task.listId?.let(listsById::get), subs.count { it.done }, subs.size)
    }

    /** All occurrences (recurring expanded, dated one-offs) with dates in [from, to]. */
    fun occurrencesBetween(from: LocalDate, to: LocalDate): List<TaskOccurrence> = tasks.flatMap { t ->
        val start = t.dueLocalDate ?: return@flatMap emptyList()
        val rule = t.rule
        if (rule == null) {
            if (start in from..to) listOf(occurrence(t, start)) else emptyList()
        } else {
            RecurrenceEngine.occurrencesBetween(start, rule, from, to).map { occurrence(t, it) }
        }
    }

    /** The "current" occurrence for list views: the earliest open one up to today, else the next one. */
    fun currentOccurrence(task: TaskEntity, today: LocalDate): TaskOccurrence? {
        val start = task.dueLocalDate ?: return occurrence(task, null)
        val rule = task.rule ?: return occurrence(task, start)
        val done = completedDates(task.id)
        val recentOpen = RecurrenceEngine.occurrencesBetween(start, rule, today.minusDays(7), today).firstOrNull { it !in done }
        val date = recentOpen ?: RecurrenceEngine.series(start, rule)
            .firstOrNull { it > today && it !in rule.exDates && it !in done } ?: return null
        return occurrence(task, date)
    }
}

fun sortOccurrences(items: List<TaskOccurrence>): List<TaskOccurrence> = items.sortedWith(
    compareBy<TaskOccurrence>({ it.completed }, { it.date ?: LocalDate.MAX }, { it.time ?: LocalTime.MIN })
        .thenByDescending { it.task.priority }
        .thenBy { it.task.sortOrder }
        .thenBy { it.task.createdAt },
)
