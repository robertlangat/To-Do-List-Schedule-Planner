package com.robertlangat.todoplanner

import com.robertlangat.todoplanner.core.recurrence.Frequency
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import com.robertlangat.todoplanner.data.db.OccurrenceEntity
import com.robertlangat.todoplanner.data.db.TaskEntity
import com.robertlangat.todoplanner.domain.AppData
import com.robertlangat.todoplanner.ui.tasks.DateFilter
import com.robertlangat.todoplanner.ui.tasks.Filters
import com.robertlangat.todoplanner.ui.tasks.StatusFilter
import com.robertlangat.todoplanner.ui.tasks.TaskView
import com.robertlangat.todoplanner.ui.tasks.TaskViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskViewsTest {
    private val today = LocalDate.of(2026, 11, 16)

    private fun task(id: String, date: LocalDate? = null, rrule: String? = null, priority: Int = 0, starred: Boolean = false, completed: Boolean = false, listId: String? = null) =
        TaskEntity(id = id, listId = listId, title = "Task $id", priority = priority, starred = starred, dueDate = date?.toEpochDay(),
            rrule = rrule, completed = completed, createdAt = 0, version = 1, deviceId = "d")

    private val data = AppData(
        tasks = listOf(
            task("today", today, priority = 3),
            task("late", today.minusDays(2)),
            task("next", today.plusDays(3), starred = true),
            task("undated"),
            task("done", today, completed = true),
            task("daily", today.minusDays(5), RecurrenceRule(Frequency.DAILY).format(), listId = "work"),
        ),
        occurrences = listOf(OccurrenceEntity(OccurrenceEntity.idFor("daily", today.toEpochDay()), "daily", today.toEpochDay(), true, 1, 1, "d")),
    )

    @Test fun todayViewHasOverdueTodayAndDoneSections() {
        val sections = TaskViews.build(TaskView.Today, data, today, "", Filters())
        val byTitle = sections.associate { it.title to it.items.map { o -> o.task.id } }
        assertTrue("late" in byTitle.getValue("Overdue"))
        // the daily task missed on 5 days shows up once
        assertEquals(1, byTitle.getValue("Overdue").count { it == "daily" })
        assertEquals(listOf("today"), byTitle.getValue("Today"))
        assertEquals(setOf("done", "daily"), byTitle.getValue("Done today").toSet())
    }

    @Test fun upcomingGroupsByDate() {
        val sections = TaskViews.build(TaskView.Upcoming, data, today, "", Filters())
        assertTrue(sections.first().items.any { it.task.id == "daily" })
        assertTrue(sections.flatMap { it.items }.any { it.task.id == "next" })
    }

    @Test fun inboxStarredCompletedAndSearch() {
        assertTrue(TaskViews.build(TaskView.Inbox, data, today, "", Filters()).single().items.none { it.task.id == "daily" })
        assertEquals(listOf("next"), TaskViews.build(TaskView.Starred, data, today, "", Filters()).single().items.map { it.task.id })
        val completed = TaskViews.build(TaskView.Completed, data, today, "", Filters()).single().items.map { it.task.id }.toSet()
        assertEquals(setOf("done", "daily"), completed)
        assertEquals(listOf("undated"), TaskViews.build(TaskView.Inbox, data, today, "task undated", Filters()).single().items.map { it.task.id })
    }

    @Test fun filtersCombine() {
        val f = Filters(priorities = setOf(3), date = DateFilter.TODAY, status = StatusFilter.OPEN)
        assertEquals(listOf("today"), TaskViews.build(TaskView.Inbox, data, today, "", f).single().items.map { it.task.id })
        val overdue = TaskViews.build(TaskView.Inbox, data, today, "", Filters(date = DateFilter.OVERDUE)).single().items.map { it.task.id }
        assertEquals(listOf("late"), overdue)
    }

    @Test fun focusViewPicksThreeByPriority() {
        val focus = TaskViews.focus(data, today)
        assertTrue(focus.size <= 3)
        assertEquals("today", focus.first().task.id)
    }
}
