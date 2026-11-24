package com.robertlangat.todoplanner

import com.robertlangat.todoplanner.core.recurrence.Frequency
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import com.robertlangat.todoplanner.domain.SubtaskDraft
import com.robertlangat.todoplanner.domain.TaskDraft
import java.time.LocalDate
import java.time.LocalTime

/**
 * Google Play screenshot mode, used only by CI:
 *   adb shell am start -n com.robertlangat.todoplanner/.MainActivity -e ScreenshotScene today|focus|calendar|planner|editor|mine
 * Seeds demo data into an empty (freshly cleared) install, shows no ads or consent prompts, and opens the requested screen.
 */
object ScreenshotMode {
    var scene: String? = null
    var reportId: String? = null
    val isOn: Boolean get() = scene != null

    const val PLANNER_PROMPT =
        "Tomorrow I have three hours after work. Prepare slides for the client presentation, revise chapter 5 and do the laundry."

    suspend fun seedIfEmpty(c: AppContainer) {
        val repo = c.repository
        val existing = repo.snapshot()
        if (existing.tasks.isNotEmpty()) {
            reportId = existing.tasks.firstOrNull { it.title == "Finish quarterly report" }?.id
            return
        }
        c.settings.setStarterListsOffered()
        val t = LocalDate.now()
        val work = repo.createList("Work", 0xFF00897B, "work")
        val personal = repo.createList("Personal", 0xFF5C6BC0, "personal")
        val study = repo.createList("Study", 0xFF8E24AA, "study")
        val fitness = repo.createList("Fitness", 0xFFE53935, "fitness")
        val family = repo.createList("Family events", 0xFFD81B60, "family")

        val standup = repo.create(TaskDraft("Team stand-up", listId = work, dueDate = t, dueTime = LocalTime.of(9, 30),
            rule = RecurrenceRule(Frequency.WEEKDAYS), reminderOffsets = listOf(10)))
        repo.setCompleted(standup, t, true)
        reportId = repo.create(TaskDraft("Finish quarterly report", notes = "Share the final PDF with the team before Friday.",
            listId = work, priority = 3, starred = true, dueDate = t, dueTime = LocalTime.of(11, 0),
            reminderOffsets = listOf(0, 30), estimatedMinutes = 90,
            subtasks = listOf(SubtaskDraft(title = "Collect sales figures", done = true),
                SubtaskDraft(title = "Draft the summary", done = true), SubtaskDraft(title = "Review with Amina"))))
        repo.create(TaskDraft("Pay electricity bill", listId = personal, priority = 3, dueDate = t, reminderOffsets = listOf(0)))
        repo.create(TaskDraft("Revise chapter 4 for exam", listId = study, priority = 2, dueDate = t, dueTime = LocalTime.of(17, 30),
            reminderOffsets = listOf(15), estimatedMinutes = 60))
        repo.create(TaskDraft("Gym - leg day", listId = fitness, dueDate = t, dueTime = LocalTime.of(19, 0),
            rule = RecurrenceRule(Frequency.WEEKLY), estimatedMinutes = 60))
        val mum = repo.create(TaskDraft("Call Mum", listId = personal, dueDate = t))
        repo.setCompleted(mum, t, true)
        val reading = repo.create(TaskDraft("Read 20 pages", listId = study, dueDate = t.minusDays(10), dueTime = LocalTime.of(21, 0),
            rule = RecurrenceRule(Frequency.DAILY), reminderOffsets = listOf(0)))
        for (back in 1L..10L) if (back != 3L) repo.setCompleted(reading, t.minusDays(back), true)
        repo.create(TaskDraft("Renew car insurance", listId = personal, priority = 2, dueDate = t.minusDays(1)))
        repo.create(TaskDraft("Buy groceries", listId = personal, dueDate = t.plusDays(1), dueTime = LocalTime.of(18, 0), estimatedMinutes = 45,
            subtasks = listOf(SubtaskDraft(title = "Milk"), SubtaskDraft(title = "Eggs"), SubtaskDraft(title = "Bread"))))
        repo.create(TaskDraft("Dentist appointment", listId = personal, dueDate = t.plusDays(2), dueTime = LocalTime.of(10, 0), reminderOffsets = listOf(60)))
        repo.create(TaskDraft("Sarah's birthday", listId = family, starred = true, dueDate = t.plusDays(4),
            rule = RecurrenceRule(Frequency.YEARLY), reminderOffsets = listOf(1440)))
        repo.create(TaskDraft("Book flights to Mombasa", listId = personal, priority = 1, dueDate = t.plusDays(6)))
        repo.create(TaskDraft("Client presentation", listId = work, priority = 3, dueDate = t.plusDays(8), dueTime = LocalTime.of(14, 0)))
        val plants = repo.create(TaskDraft("Water the plants", listId = personal, dueDate = t.minusDays(3),
            rule = RecurrenceRule(Frequency.DAILY, interval = 3)))
        repo.setCompleted(plants, t.minusDays(3), true)
        val rentStart = t.withDayOfMonth(1)
        val rent = repo.create(TaskDraft("Pay rent", listId = personal, priority = 3, dueDate = rentStart, rule = RecurrenceRule(Frequency.MONTHLY)))
        if (rentStart < t) repo.setCompleted(rent, rentStart, true)
        repo.create(TaskDraft("Plan weekend hike", listId = fitness, starred = true))
        repo.create(TaskDraft("Mock exam", listId = study, priority = 2, dueDate = t.plusDays(12), dueTime = LocalTime.of(9, 0)))
    }
}
