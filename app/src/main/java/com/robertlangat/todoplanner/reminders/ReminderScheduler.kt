package com.robertlangat.todoplanner.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.robertlangat.todoplanner.core.reminders.ReminderPlanner
import com.robertlangat.todoplanner.core.reminders.ReminderTask
import com.robertlangat.todoplanner.core.reminders.Snooze
import com.robertlangat.todoplanner.data.SettingsRepository
import com.robertlangat.todoplanner.data.db.AppDatabase
import com.robertlangat.todoplanner.data.db.ScheduledAlarmEntity
import com.robertlangat.todoplanner.domain.dueLocalDate
import com.robertlangat.todoplanner.domain.dueLocalTime
import com.robertlangat.todoplanner.domain.offsets
import com.robertlangat.todoplanner.domain.rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ReminderTiming { EXACT, APPROXIMATE }

/**
 * Keeps AlarmManager in step with the database. It is safe to call [reconcile] as often as you like:
 * each alarm's PendingIntent is identified by its deterministic key (in the Intent data URI), so
 * re-scheduling replaces rather than duplicates, and anything no longer wanted is cancelled.
 */
class ReminderScheduler(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    fun timing(): ReminderTiming =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) ReminderTiming.EXACT
        else ReminderTiming.APPROXIMATE

    fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * @param rebuildAll re-register every desired alarm (after boot, app start, time/zone change or
     *   restore), because AlarmManager may have lost them even though our table still lists them.
     */
    suspend fun reconcile(rebuildAll: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val s = settings.current()
            val now = Instant.now()
            val alarms = db.alarms()
            alarms.purgeSnoozes(now.minusSeconds(24 * 3600).toEpochMilli())

            val existing = alarms.getAll()
            val desired = if (s.remindersEnabled && s.remindersOnThisDevice) {
                val completions = db.occurrences().getAll().filter { it.completed }.groupBy { it.taskId }
                val tasks = db.tasks().getAll().map { t ->
                    val done = if (t.rrule != null) completions[t.id].orEmpty().map { LocalDate.ofEpochDay(it.date) }.toSet()
                    else if (t.completed) setOfNotNull(t.dueLocalDate) else emptySet()
                    ReminderTask(t.id, t.title, t.dueLocalDate, t.dueLocalTime, t.rule, t.offsets, done, t.deleted)
                }
                val snoozes = alarms.snoozes().map { Snooze(it.taskId, LocalDate.ofEpochDay(it.occurrenceDate), Instant.ofEpochMilli(it.until)) }
                ReminderPlanner.desired(
                    tasks, snoozes, now, ZoneId.systemDefault(), s.allDayReminderTime,
                    deliveredKeys = existing.filter { it.delivered }.map { it.key }.toSet(),
                )
            } else emptyList()

            val current = if (rebuildAll) emptyMap() else existing.filterNot { it.delivered }.associate { it.key to it.triggerAt }
            val plan = ReminderPlanner.diff(desired, current)
            val desiredKeys = desired.map { it.key }.toSet()

            for (row in existing) {
                if (row.key in desiredKeys) continue
                if (!row.delivered) alarmManager.cancel(pendingIntent(row.key))
                // Keep delivered rows for a while so a missed-reminder catch-up never fires twice.
                if (!row.delivered || row.triggerAt < now.minusSeconds(3600).toEpochMilli()) alarms.delete(row.key)
            }
            val exact = timing() == ReminderTiming.EXACT
            for (a in plan.toSchedule) {
                setAlarm(a.key, a.triggerAt.toEpochMilli(), exact)
                alarms.upsert(ScheduledAlarmEntity(a.key, a.taskId, a.occurrenceDate.toEpochDay(), a.triggerAt.toEpochMilli(), delivered = false))
            }
        }
    }

    private fun setAlarm(key: String, at: Long, exact: Boolean) {
        val pi = pendingIntent(key)
        try {
            if (exact) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            // Exact-alarm access was revoked between the check and the call.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pendingIntent(key: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .setData(Uri.parse("todoplanner://alarm/" + Uri.encode(key)))
            .putExtra(ReminderReceiver.EXTRA_KEY, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    suspend fun snooze(taskId: String, date: LocalDate, minutes: Int) {
        val key = ReminderPlanner.snoozeKey(taskId, date)
        db.alarms().upsertSnooze(
            com.robertlangat.todoplanner.data.db.SnoozeEntity(key, taskId, date.toEpochDay(), System.currentTimeMillis() + minutes * 60_000L),
        )
        // A new snooze of the same occurrence replaces the old one (same key) and is "not yet delivered".
        db.alarms().delete(key)
        reconcile()
    }
}
