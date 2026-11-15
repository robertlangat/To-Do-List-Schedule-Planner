package com.robertlangat.todoplanner

import android.app.Application
import android.content.Context
import androidx.glance.appwidget.updateAll
import com.robertlangat.todoplanner.data.SettingsRepository
import com.robertlangat.todoplanner.data.TaskRepository
import com.robertlangat.todoplanner.data.db.AppDatabase
import com.robertlangat.todoplanner.planner.AiPlannerClient
import com.robertlangat.todoplanner.reminders.DateRolloverScheduler
import com.robertlangat.todoplanner.reminders.NotificationChannels
import com.robertlangat.todoplanner.reminders.ReminderScheduler
import com.robertlangat.todoplanner.sync.DriveAuth
import com.robertlangat.todoplanner.sync.SyncManager
import com.robertlangat.todoplanner.sync.SyncWorker
import com.robertlangat.todoplanner.wear.PhoneWearBridge
import com.robertlangat.todoplanner.widget.TodayWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TodoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationChannels.create(this)
        container.start()
    }
}

val Context.container: AppContainer get() = (applicationContext as TodoApp).container

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.build(context) }
    val settings = SettingsRepository(context)
    val reminders by lazy { ReminderScheduler(context, db, settings) }
    val repository: TaskRepository by lazy { TaskRepository(db, settings) { fromSync -> notifyChanged(fromSync) } }
    val wear by lazy { PhoneWearBridge(context, repository) }
    val driveAuth by lazy { DriveAuth(context, settings) }
    val sync by lazy { SyncManager(context, repository, settings, driveAuth, reminders) }
    val ai by lazy { AiPlannerClient() }

    private data class Change(val fromSync: Boolean)
    private val changes = Channel<Change>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            // Alarms don't survive force-stop, so rebuild them from the database on every process start.
            reminders.reconcile(rebuildAll = true)
            DateRolloverScheduler.scheduleNext(context)
            if (settings.current().driveSignedIn) SyncWorker.schedulePeriodic(context)
        }
        scope.launch {
            for (c in changes) {
                delay(150) // coalesce bursts (e.g. a sync applying many rows)
                runCatching { reminders.reconcile() }
                runCatching { TodayWidget().updateAll(context) }
                runCatching { wear.publish() }
                if (!c.fromSync) {
                    val s = settings.current()
                    if (s.driveSignedIn && s.autoSync) SyncWorker.scheduleSoon(context)
                }
            }
        }
    }

    /** Called by the repository after every committed change. */
    suspend fun notifyChanged(fromSync: Boolean) {
        changes.send(Change(fromSync))
    }

    /** Refresh everything that depends on "today" (date rollover, time zone change). */
    suspend fun refreshTimeDependents(rebuildAlarms: Boolean) {
        reminders.reconcile(rebuildAll = rebuildAlarms)
        runCatching { TodayWidget().updateAll(context) }
        runCatching { wear.publish() }
    }
}
