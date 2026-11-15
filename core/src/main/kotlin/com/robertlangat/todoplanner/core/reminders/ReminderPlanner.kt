package com.robertlangat.todoplanner.core.reminders

import com.robertlangat.todoplanner.core.recurrence.RecurrenceEngine
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Everything the reminder planner needs to know about one task. */
data class ReminderTask(
    val taskId: String,
    val title: String,
    val startDate: LocalDate?,
    /** Null for all-day tasks; the user's all-day reminder time is used instead. */
    val time: LocalTime?,
    val rule: RecurrenceRule?,
    /** Minutes before the task time. 0 = at the time. */
    val offsetsMinutes: List<Int>,
    /** Completed occurrence dates (recurring) or the due date (one-off) - no reminders for these. */
    val completedDates: Set<LocalDate>,
    val deleted: Boolean = false,
)

data class Snooze(val taskId: String, val occurrenceDate: LocalDate, val until: Instant)

data class DesiredAlarm(
    val key: String,
    val taskId: String,
    val title: String,
    val occurrenceDate: LocalDate,
    val triggerAt: Instant,
    val isSnooze: Boolean,
)

data class ReconcilePlan(val toSchedule: List<DesiredAlarm>, val toCancel: Set<String>)

/**
 * Computes the exact set of alarms that should exist right now. The Android layer stores what it
 * has scheduled and applies [diff], so editing, snoozing, restoring or syncing a task can never
 * create a second alarm for the same occurrence: every alarm has a deterministic key
 * (`taskId@date#offset`) and the platform PendingIntent is keyed by it.
 */
object ReminderPlanner {

    fun alarmKey(taskId: String, date: LocalDate, offsetMinutes: Int) = "$taskId@$date#$offsetMinutes"
    fun snoozeKey(taskId: String, date: LocalDate) = "$taskId@$date#snooze"

    /**
     * Converts a wall-clock occurrence to an instant in [zone].
     * - DST gap (e.g. 02:30 on spring-forward day) resolves to the first valid time after the gap.
     * - DST overlap (repeated hour) resolves to the earlier offset.
     * The offset is subtracted on the instant timeline ("15 minutes before" = 15 real minutes).
     */
    fun triggerFor(date: LocalDate, time: LocalTime?, offsetMinutes: Int, zone: ZoneId, allDayTime: LocalTime): Instant =
        ZonedDateTime.of(date, time ?: allDayTime, zone).toInstant().minusSeconds(offsetMinutes * 60L)

    fun desired(
        tasks: List<ReminderTask>,
        snoozes: List<Snooze>,
        now: Instant,
        zone: ZoneId,
        allDayTime: LocalTime,
        deliveredKeys: Set<String> = emptySet(),
        horizonDays: Long = 35,
        missedGrace: Duration = Duration.ofMinutes(15),
        maxAlarms: Int = 200,
    ): List<DesiredAlarm> {
        val today = now.atZone(zone).toLocalDate()
        // Look one day back so "1 day before" reminders for tomorrow and late reminders are covered.
        val from = today.minusDays(1)
        val to = today.plusDays(horizonDays)
        val graceStart = now.minus(missedGrace)
        val result = mutableListOf<DesiredAlarm>()

        for (task in tasks) {
            if (task.deleted || task.startDate == null || task.offsetsMinutes.isEmpty()) continue
            val maxOffsetDays = (task.offsetsMinutes.maxOrNull() ?: 0) / (24 * 60) + 1
            val dates = if (task.rule == null) listOf(task.startDate)
            else RecurrenceEngine.occurrencesBetween(task.startDate, task.rule, from, to.plusDays(maxOffsetDays.toLong()))
            for (date in dates) {
                if (date in task.completedDates) continue
                for (offset in task.offsetsMinutes.distinct()) {
                    val trigger = triggerFor(date, task.time, offset, zone, allDayTime)
                    val key = alarmKey(task.taskId, date, offset)
                    val inFuture = trigger.isAfter(now)
                    val missedButRecent = !inFuture && trigger.isAfter(graceStart) && key !in deliveredKeys
                    if (!inFuture && !missedButRecent) continue
                    if (trigger.isAfter(to.plusDays(1).atStartOfDay(zone).toInstant())) continue
                    result += DesiredAlarm(key, task.taskId, task.title, date, if (inFuture) trigger else now.plusSeconds(5), false)
                }
            }
        }
        val liveTasks = tasks.filter { !it.deleted }.associateBy { it.taskId }
        for (s in snoozes) {
            val t = liveTasks[s.taskId] ?: continue
            if (s.occurrenceDate in t.completedDates) continue
            val key = snoozeKey(s.taskId, s.occurrenceDate)
            if (!s.until.isAfter(now) && key in deliveredKeys) continue
            result += DesiredAlarm(key, s.taskId, t.title, s.occurrenceDate, maxOf(s.until, now.plusSeconds(5)), true)
        }
        return result.sortedBy { it.triggerAt }.take(maxAlarms)
    }

    /** [existing] maps alarm key -> scheduled trigger epoch millis. */
    fun diff(desired: List<DesiredAlarm>, existing: Map<String, Long>): ReconcilePlan {
        val desiredKeys = desired.associateBy { it.key }
        val toCancel = existing.keys.filterNot { it in desiredKeys }.toSet()
        val toSchedule = desired.filter { existing[it.key] != it.triggerAt.toEpochMilli() }
        return ReconcilePlan(toSchedule, toCancel)
    }
}
