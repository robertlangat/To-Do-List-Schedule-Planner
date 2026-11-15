package com.robertlangat.todoplanner.core

import com.robertlangat.todoplanner.core.recurrence.Frequency
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import com.robertlangat.todoplanner.core.reminders.ReminderPlanner
import com.robertlangat.todoplanner.core.reminders.ReminderTask
import com.robertlangat.todoplanner.core.reminders.Snooze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderPlannerTest {
    private val nairobi = ZoneId.of("Africa/Nairobi")
    private val london = ZoneId.of("Europe/London")
    private val nineAm = LocalTime.of(9, 0)
    private fun d(s: String) = LocalDate.parse(s)

    private fun task(
        id: String = "t1",
        date: String = "2026-11-16",
        time: LocalTime? = LocalTime.of(18, 0),
        rule: RecurrenceRule? = null,
        offsets: List<Int> = listOf(0),
        completed: Set<LocalDate> = emptySet(),
    ) = ReminderTask(id, "Task $id", d(date), time, rule, offsets, completed)

    @Test fun reminderAtTimeAndBefore() {
        val now = ZonedDateTime.of(2026, 11, 16, 8, 0, 0, 0, nairobi).toInstant()
        val got = ReminderPlanner.desired(listOf(task(offsets = listOf(0, 30))), emptyList(), now, nairobi, nineAm)
        assertEquals(
            listOf("2026-11-16T14:30:00Z", "2026-11-16T15:00:00Z").map(Instant::parse),
            got.map { it.triggerAt },
        )
    }

    @Test fun completedOccurrenceGetsNoReminder() {
        val now = ZonedDateTime.of(2026, 11, 16, 8, 0, 0, 0, nairobi).toInstant()
        val t = task(rule = RecurrenceRule(Frequency.DAILY), completed = setOf(d("2026-11-16")))
        val got = ReminderPlanner.desired(listOf(t), emptyList(), now, nairobi, nineAm, horizonDays = 2)
        assertEquals(listOf(d("2026-11-17"), d("2026-11-18")), got.map { it.occurrenceDate })
    }

    @Test fun allDayTaskUsesAllDayTime() {
        val now = ZonedDateTime.of(2026, 11, 16, 6, 0, 0, 0, nairobi).toInstant()
        val got = ReminderPlanner.desired(listOf(task(time = null)), emptyList(), now, nairobi, nineAm)
        assertEquals(ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, nairobi).toInstant(), got.single().triggerAt)
    }

    @Test fun timeZoneChangeKeepsWallClockTime() {
        val t = task(date = "2026-11-20", time = LocalTime.of(9, 0))
        val now = Instant.parse("2026-11-16T00:00:00Z")
        val inNairobi = ReminderPlanner.desired(listOf(t), emptyList(), now, nairobi, nineAm).single()
        val inLondon = ReminderPlanner.desired(listOf(t), emptyList(), now, london, nineAm).single()
        assertEquals(LocalTime.of(9, 0), inNairobi.triggerAt.atZone(nairobi).toLocalTime())
        assertEquals(LocalTime.of(9, 0), inLondon.triggerAt.atZone(london).toLocalTime())
        assertEquals(inNairobi.key, inLondon.key) // same alarm slot, rescheduled not duplicated
    }

    @Test fun dstGapAndOverlapResolveConsistently() {
        // Europe/London springs forward 2027-03-28 01:00 -> 02:00
        val gap = ReminderPlanner.triggerFor(d("2027-03-28"), LocalTime.of(1, 30), 0, london, nineAm)
        assertEquals(LocalTime.of(2, 30), gap.atZone(london).toLocalTime())
        // falls back 2026-10-25 02:00 -> 01:00: 01:30 happens twice, earlier one (BST) is used
        val overlap = ReminderPlanner.triggerFor(d("2026-10-25"), LocalTime.of(1, 30), 0, london, nineAm)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), overlap)
        // daily 09:00 across the change stays 09:00 local
        val before = ReminderPlanner.triggerFor(d("2027-03-27"), nineAm, 0, london, nineAm)
        val after = ReminderPlanner.triggerFor(d("2027-03-28"), nineAm, 0, london, nineAm)
        assertEquals(nineAm, before.atZone(london).toLocalTime())
        assertEquals(nineAm, after.atZone(london).toLocalTime())
    }

    @Test fun editingTaskReschedulesSameKeyWithoutDuplicate() {
        val now = Instant.parse("2026-11-16T00:00:00Z")
        val first = ReminderPlanner.desired(listOf(task()), emptyList(), now, nairobi, nineAm)
        val existing = first.associate { it.key to it.triggerAt.toEpochMilli() }
        // Same task moved to 19:00
        val edited = ReminderPlanner.desired(listOf(task(time = LocalTime.of(19, 0))), emptyList(), now, nairobi, nineAm)
        val plan = ReminderPlanner.diff(edited, existing)
        assertEquals(1, plan.toSchedule.size)
        assertTrue(plan.toCancel.isEmpty())
        assertEquals(first.single().key, plan.toSchedule.single().key)
        // Re-running with nothing changed is a no-op
        val again = ReminderPlanner.diff(edited, edited.associate { it.key to it.triggerAt.toEpochMilli() })
        assertTrue(again.toSchedule.isEmpty() && again.toCancel.isEmpty())
    }

    @Test fun deletedOrCompletedTaskCancelsAlarms() {
        val now = Instant.parse("2026-11-16T00:00:00Z")
        val existing = ReminderPlanner.desired(listOf(task()), emptyList(), now, nairobi, nineAm)
            .associate { it.key to it.triggerAt.toEpochMilli() }
        val afterDelete = ReminderPlanner.desired(listOf(task().copy(deleted = true)), emptyList(), now, nairobi, nineAm)
        assertEquals(existing.keys, ReminderPlanner.diff(afterDelete, existing).toCancel)
        val afterComplete = ReminderPlanner.desired(listOf(task(completed = setOf(d("2026-11-16")))), emptyList(), now, nairobi, nineAm)
        assertEquals(existing.keys, ReminderPlanner.diff(afterComplete, existing).toCancel)
    }

    @Test fun snoozeAddsSingleSnoozeAlarm() {
        val now = Instant.parse("2026-11-16T15:00:00Z")
        val snooze = Snooze("t1", d("2026-11-16"), now.plusSeconds(600))
        val got = ReminderPlanner.desired(listOf(task()), listOf(snooze, snooze), now, nairobi, nineAm, deliveredKeys = setOf("t1@2026-11-16#0"))
        val snoozes = got.filter { it.isSnooze }
        // duplicate snooze requests collapse to one deterministic key
        assertEquals(1, snoozes.distinctBy { it.key }.size)
        assertEquals(now.plusSeconds(600), snoozes.first().triggerAt)
    }

    @Test fun missedReminderAfterRebootFiresOnceWithinGrace() {
        val trigger = Instant.parse("2026-11-16T15:00:00Z") // 18:00 Nairobi
        val now = trigger.plusSeconds(300)
        val got = ReminderPlanner.desired(listOf(task()), emptyList(), now, nairobi, nineAm)
        assertEquals(1, got.size)
        val delivered = ReminderPlanner.desired(listOf(task()), emptyList(), now, nairobi, nineAm, deliveredKeys = setOf(got.single().key))
        assertTrue(delivered.isEmpty())
    }
}
