package com.robertlangat.todoplanner.core

import com.robertlangat.todoplanner.core.json.Json
import com.robertlangat.todoplanner.core.planner.AiPlanValidator
import com.robertlangat.todoplanner.core.planner.BusyBlock
import com.robertlangat.todoplanner.core.planner.DraftFlag
import com.robertlangat.todoplanner.core.planner.OfflinePlanner
import com.robertlangat.todoplanner.core.planner.PlanPriority
import com.robertlangat.todoplanner.core.planner.PlanRequest
import com.robertlangat.todoplanner.core.planner.PlanSource
import com.robertlangat.todoplanner.core.planner.PlannerTask
import com.robertlangat.todoplanner.core.planner.RoughInputParser
import com.robertlangat.todoplanner.core.planner.TimeWindow
import com.robertlangat.todoplanner.core.progress.ProgressCalculator
import com.robertlangat.todoplanner.core.progress.ProgressTask
import com.robertlangat.todoplanner.core.progress.Ratio
import com.robertlangat.todoplanner.core.recurrence.Frequency
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PlannerTest {
    private val today = LocalDate.parse("2026-11-16")
    private fun t(h: Int, m: Int = 0) = today.atTime(h, m)

    @Test fun parsesTheExamplePrompt() {
        val r = RoughInputParser.parse(
            "I have two hours after work. Help me revise for an exam and plan tomorrow's errands.", today,
        )
        assertEquals(120, r.availability!!.minutes)
        assertEquals(LocalTime.of(17, 30), r.availability!!.start)
        assertEquals(listOf("Revise for an exam", "Tomorrow's errands"), r.tasks.map { it.title })
        assertEquals(60, r.tasks[0].durationMinutes)
        assertEquals(today.plusDays(1).atTime(23, 59), r.tasks[1].deadline)
    }

    @Test fun parsesBulletsDurationsAndPriorities() {
        val r = RoughInputParser.parse("- Pay rent urgent 10m\n- Gym (1.5h)\n* call mum !low\n3. Write essay by friday", today)
        assertEquals(listOf("Pay rent", "Gym", "Call mum", "Write essay"), r.tasks.map { it.title })
        assertEquals(listOf(10, 90, 15, 60), r.tasks.map { it.durationMinutes })
        assertEquals(PlanPriority.HIGH, r.tasks[0].priority)
        assertEquals(PlanPriority.LOW, r.tasks[2].priority)
        assertEquals(LocalDate.parse("2026-11-20"), r.tasks[3].deadline!!.toLocalDate())
        assertNull(r.availability)
    }

    @Test fun offlinePlannerAvoidsExistingTasksAndOrdersByDeadlineThenPriority() {
        val request = PlanRequest(
            tasks = listOf(
                PlannerTask("1", "Low thing", 30, PlanPriority.LOW),
                PlannerTask("2", "Urgent", 30, PlanPriority.HIGH),
                PlannerTask("3", "Due soon", 30, PlanPriority.NONE, deadline = t(19)),
            ),
            availability = listOf(TimeWindow(t(17, 30), t(19, 30))),
            busy = listOf(BusyBlock("Dinner", TimeWindow(t(18), t(18, 30)))),
            bufferMinutes = 0,
        )
        val draft = OfflinePlanner.plan(request)
        assertEquals(PlanSource.OFFLINE, draft.source)
        val byTitle = draft.items.associateBy { it.task.title }
        assertEquals(t(17, 30), byTitle.getValue("Due soon").slot!!.start)
        assertEquals(t(18, 30), byTitle.getValue("Urgent").slot!!.start)
        assertEquals(t(19), byTitle.getValue("Low thing").slot!!.start)
        assertTrue(draft.items.none { it.slot!!.overlaps(request.busy[0].window) })
    }

    @Test fun tasksThatDontFitAreReturnedUnscheduled() {
        val draft = OfflinePlanner.plan(
            PlanRequest(listOf(PlannerTask("1", "Huge", 240)), listOf(TimeWindow(t(18), t(20))), emptyList()),
        )
        assertTrue(DraftFlag.UNSCHEDULED in draft.items.single().flags)
        assertTrue(draft.warnings.isNotEmpty())
    }

    @Test fun recheckFlagsOverlapsAfterUserEdits() {
        val draft = OfflinePlanner.plan(
            PlanRequest(listOf(PlannerTask("1", "A", 60), PlannerTask("2", "B", 60)), listOf(TimeWindow(t(9), t(12))), emptyList()),
        )
        val moved = draft.items.map { if (it.task.title == "B") it.copy(slot = TimeWindow(t(9, 30), t(10, 30))) else it }
        val checked = OfflinePlanner.recheck(moved, listOf(BusyBlock("Standup", TimeWindow(t(10), t(10, 15)))), listOf(TimeWindow(t(9), t(12))))
        val b = checked.first { it.task.title == "B" }
        assertTrue(DraftFlag.OVERLAPS_DRAFT in b.flags)
        assertTrue(DraftFlag.OVERLAPS_EXISTING in b.flags)
        assertTrue("Standup" in b.conflictsWith)
    }

    @Test fun aiValidatorClampsDropsAndFlags() {
        val json = Json.stringify(
            mapOf(
                "summary" to "Evening plan", "model" to "test-model", "extra" to 1,
                "tasks" to listOf(
                    mapOf("title" to "Revise chapter 3", "durationMinutes" to 9999, "priority" to "HIGH", "start" to "2026-11-16T18:00", "subtasks" to listOf("Read", 3, "Flashcards")),
                    mapOf("title" to "   "),
                    mapOf("title" to "Groceries", "durationMinutes" to 30, "start" to "18:15"),
                    "not an object",
                ),
            ),
        )
        val draft = AiPlanValidator.validate(json, today, emptyList(), listOf(TimeWindow(t(17), t(23))))
        assertEquals(PlanSource.AI, draft.source)
        assertEquals("test-model", draft.modelName)
        assertEquals(2, draft.items.size)
        val revise = draft.items.first { it.task.title == "Revise chapter 3" }
        assertEquals(720, revise.task.durationMinutes)
        assertEquals(listOf("Read", "Flashcards"), revise.task.subtasks)
        assertEquals(PlanPriority.HIGH, revise.task.priority)
        val groceries = draft.items.first { it.task.title == "Groceries" }
        assertEquals(LocalDateTime.of(2026, 11, 16, 18, 15), groceries.slot!!.start)
        assertTrue(DraftFlag.OVERLAPS_DRAFT in groceries.flags)
        assertTrue(draft.warnings.size >= 3)
    }

    @Test fun aiValidatorRejectsGarbage() {
        assertTrue(runCatching { AiPlanValidator.validate("<html>", today, emptyList(), emptyList()) }.isFailure)
        assertTrue(runCatching { AiPlanValidator.validate("""{"tasks":[]}""", today, emptyList(), emptyList()) }.isFailure)
    }

    @Test fun progressCountsRecurringOccurrencesIndividually() {
        val tasks = listOf(
            ProgressTask("daily", "fitness", today.minusDays(10), RecurrenceRule(Frequency.DAILY), false, setOf(today, today.minusDays(1))),
            ProgressTask("oneoff", "work", today, null, completed = true),
            ProgressTask("late", "work", today.minusDays(2), null, completed = false),
            ProgressTask("undated", null, null, null, completed = false),
        )
        val s = ProgressCalculator.compute(tasks, today)
        // 2026-11-16 is a Monday: week = 16..22
        assertEquals(Ratio(2, 2), s.today)
        assertEquals(Ratio(2, 8), s.thisWeek) // 7 daily occurrences + 1 one-off
        // overdue: daily occurrences 6th..15th minus the 15th = 9, plus the late one-off
        assertEquals(10, s.overdue)
        assertEquals(Ratio(1, 2), s.byList["work"])
    }

    @Test fun jsonRoundTripsUnicodeAndEscapes() {
        val value = mapOf("s" to "Line\n\"quoted\" — ✓", "n" to 42L, "d" to 1.5, "l" to listOf(true, null))
        assertEquals(value, Json.parse(Json.stringify(value)))
    }
}
