package com.robertlangat.todoplanner.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.robertlangat.todoplanner.MainActivity
import com.robertlangat.todoplanner.R
import com.robertlangat.todoplanner.container
import com.robertlangat.todoplanner.core.recurrence.RecurrenceEngine
import com.robertlangat.todoplanner.domain.dueLocalDate
import com.robertlangat.todoplanner.domain.dueLocalTime
import com.robertlangat.todoplanner.domain.rule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Runs [block] off the main thread while keeping the broadcast alive. */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try { block() } finally { pending.finish() }
    }
}

private fun keyParts(key: String): Pair<String, LocalDate>? = runCatching {
    val taskId = key.substringBefore('@')
    val date = LocalDate.parse(key.substringAfter('@').substringBefore('#'))
    taskId to date
}.getOrNull()

private fun notificationId(taskId: String, date: LocalDate) = "$taskId@$date".hashCode()

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "com.robertlangat.todoplanner.REMINDER"
        const val EXTRA_KEY = "key"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        async {
            val c = context.container
            val (taskId, date) = keyParts(key) ?: return@async
            c.db.alarms().markDelivered(key)
            if (key.endsWith("#snooze")) c.db.alarms().deleteSnooze(key)
            val task = c.repository.task(taskId)
            val settings = c.settings.current()
            // Guard against stale alarms: deleted, completed, or no longer an occurrence.
            val stillDue = task != null && settings.remindersEnabled && settings.remindersOnThisDevice && run {
                val rule = task.rule
                val start = task.dueLocalDate
                if (rule != null && start != null) {
                    RecurrenceEngine.isOccurrence(start, rule, date) &&
                        c.db.occurrences().forTask(taskId).none { it.date == date.toEpochDay() && it.completed }
                } else !task.completed && start == date
            }
            if (stillDue && task != null) show(context, task.id, task.title, date, task.dueLocalTime, task.rrule != null, settings.snoozeMinutes,
                NotificationChannels.idFor(settings.reminderSound))
            c.reminders.reconcile()
        }
    }

    private fun show(
        context: Context, taskId: String, title: String, date: LocalDate, time: java.time.LocalTime?,
        recurring: Boolean, snoozeMinutes: Int, channel: String,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nid = notificationId(taskId, date)
        val whenText = buildString {
            append(if (date == LocalDate.now()) "Today" else date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
            if (time != null) append(" · ").append(time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
            if (recurring) append(" · repeats")
        }
        val open = PendingIntent.getActivity(
            context, nid,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_TASK)
                .setData(Uri.parse("todoplanner://task/$taskId/$date"))
                .putExtra(MainActivity.EXTRA_TASK_ID, taskId)
                .putExtra(MainActivity.EXTRA_DATE, date.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(action: String) = PendingIntent.getBroadcast(
            context, nid,
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("todoplanner://action/$action/$taskId/$date"))
                .putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId)
                .putExtra(ReminderActionReceiver.EXTRA_DATE, date.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(whenText)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_complete), action(ReminderActionReceiver.ACTION_COMPLETE))
            .addAction(0, context.getString(R.string.action_snooze) + " $snoozeMinutes min", action(ReminderActionReceiver.ACTION_SNOOZE))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(nid, n)
        } catch (_: SecurityException) {
            // Permission revoked concurrently; the task itself is unaffected.
        }
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COMPLETE = "com.robertlangat.todoplanner.COMPLETE"
        const val ACTION_SNOOZE = "com.robertlangat.todoplanner.SNOOZE"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_DATE = "date"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        NotificationManagerCompat.from(context).cancel(notificationId(taskId, date))
        async {
            val c = context.container
            when (intent.action) {
                ACTION_COMPLETE -> c.repository.setCompleted(taskId, date, true)
                ACTION_SNOOZE -> c.reminders.snooze(taskId, date, c.settings.current().snoozeMinutes)
            }
        }
    }
}

/**
 * Boot, app update, clock/time-zone changes, exact-alarm permission changes and the midnight
 * rollover all lead to a full alarm rebuild and a widget/Wear refresh.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            DateRolloverScheduler.ACTION_ROLLOVER,
            -> async {
                context.container.refreshTimeDependents(rebuildAlarms = true)
                DateRolloverScheduler.scheduleNext(context)
            }
        }
    }
}

/** Schedules a cheap, inexact alarm just after local midnight to refresh "Today" everywhere. */
object DateRolloverScheduler {
    const val ACTION_ROLLOVER = "com.robertlangat.todoplanner.DATE_ROLLOVER"

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val next = ZonedDateTime.now(ZoneId.systemDefault()).toLocalDate().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).plusMinutes(1).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, SystemEventsReceiver::class.java).setAction(ACTION_ROLLOVER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setWindow(AlarmManager.RTC, next, 10 * 60_000L, pi)
    }
}
