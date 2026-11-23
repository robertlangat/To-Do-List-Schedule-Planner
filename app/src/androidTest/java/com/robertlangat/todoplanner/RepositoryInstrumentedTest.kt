package com.robertlangat.todoplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robertlangat.todoplanner.core.recurrence.Frequency
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import com.robertlangat.todoplanner.core.reminders.ReminderPlanner
import com.robertlangat.todoplanner.core.reminders.ReminderTask
import com.robertlangat.todoplanner.core.sync.SyncMerger
import com.robertlangat.todoplanner.core.sync.SyncRecord
import com.robertlangat.todoplanner.data.SettingsRepository
import com.robertlangat.todoplanner.data.SyncMapping
import com.robertlangat.todoplanner.data.TaskRepository
import com.robertlangat.todoplanner.data.db.AppDatabase
import com.robertlangat.todoplanner.domain.EditScope
import com.robertlangat.todoplanner.domain.TaskDraft
import com.robertlangat.todoplanner.domain.dueLocalDate
import com.robertlangat.todoplanner.domain.dueLocalTime
import com.robertlangat.todoplanner.domain.offsets
import com.robertlangat.todoplanner.domain.rule
import com.robertlangat.todoplanner.reminders.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repo: TaskRepository
    private val today = LocalDate.now()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.inMemory(context)
        settings = SettingsRepository(context)
        repo = TaskRepository(db, settings) { }
    }

    @After fun tearDown() = db.close()

    @Test fun createCompleteUndoAndDelete() = runBlocking {
        val id = repo.quickAdd("Buy milk")
        repo.setCompleted(id, null, true)
        assertTrue(repo.task(id)!!.completed)
        repo.setCompleted(id, null, false) // undo completion
        assertFalse(repo.task(id)!!.completed)
        val token = repo.delete(id)!!
        assertNull(repo.task(id))
        repo.undo(token)
        assertEquals("Buy milk", repo.task(id)!!.title)
    }

    @Test fun monthlyAndYearlyCompletionsArePerOccurrence() = runBlocking {
        val monthly = repo.create(TaskDraft("Pay rent", dueDate = LocalDate.of(2027, 1, 31), rule = RecurrenceRule(Frequency.MONTHLY)))
        val yearly = repo.create(TaskDraft("Birthday", dueDate = LocalDate.of(2024, 2, 29), rule = RecurrenceRule(Frequency.YEARLY)))
        repo.setCompleted(monthly, LocalDate.of(2027, 2, 28), true)
        repo.setCompleted(yearly, LocalDate.of(2027, 2, 28), true)
        val data = repo.snapshot()
        assertEquals(setOf(LocalDate.of(2027, 2, 28)), data.completedDates(monthly))
        val march = data.occurrencesBetween(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 31)).single { it.task.id == monthly }
        assertEquals(LocalDate.of(2027, 3, 31), march.date)
        assertFalse(march.completed)
        assertEquals(setOf(LocalDate.of(2027, 2, 28)), data.completedDates(yearly))
    }

    @Test fun editingRecurringTaskDoesNotDuplicateReminders() = runBlocking {
        val id = repo.create(TaskDraft("Stretch", dueDate = today, dueTime = LocalTime.of(23, 0),
            rule = RecurrenceRule(Frequency.DAILY), reminderOffsets = listOf(0, 10)))
        val at = today.plusDays(3)
        repo.update(id, repo.draftOf(repo.task(id)!!).copy(title = "Stretch longer", dueDate = at), EditScope.THIS_AND_FUTURE, at)
        repo.update(id, repo.draftOf(repo.task(id)!!).copy(title = "Moved once"), EditScope.THIS_OCCURRENCE, today.plusDays(1))

        val data = repo.snapshot()
        val tasks = data.tasks.map { t ->
            ReminderTask(t.id, t.title, t.dueLocalDate, t.dueLocalTime, t.rule, t.offsets, data.completedDates(t.id))
        }
        val alarms = ReminderPlanner.desired(tasks, emptyList(), Instant.now(), ZoneId.systemDefault(), LocalTime.of(9, 0), horizonDays = 10)
        // Exactly one alarm per (occurrence date, offset) across the old series, split series and detached task.
        val slots = alarms.map { it.occurrenceDate to it.key.substringAfter('#') }
        assertEquals(slots.size, slots.toSet().size)
        assertEquals(3, data.tasks.size)
        // The single-occurrence copy keeps that occurrence's date when the start date wasn't changed.
        assertEquals(today.plusDays(1), data.tasks.single { it.title == "Moved once" }.dueLocalDate)
    }

    @Test fun syncedDeletionStaysDeleted() = runBlocking {
        val id = repo.quickAdd("Old task")
        val beforeDelete = repo.exportRecords().single { it.id == id }
        repo.delete(id)
        // Another device, offline, edited the same task after we deleted it, without knowing about the delete.
        val offlineEdit = beforeDelete.copy(
            version = repo.exportRecords().single { it.id == id }.version + 1000,
            deviceId = "tablet", data = beforeDelete.data + ("title" to "Edited offline"), seenDeleteVersion = 0,
        )
        val merge = SyncMerger.merge(repo.exportRecords(), listOf(offlineEdit), System.currentTimeMillis())
        repo.applyRecords(merge.changedLocally)
        assertNull(repo.task(id))
        assertTrue(merge.merged.single { it.id == id }.deleted)
    }

    @Test fun restoreFromAnotherDeviceDoesNotDuplicate() = runBlocking {
        repo.quickAdd("A")
        val exported: List<SyncRecord> = repo.exportRecords()
        // Simulate a fresh device restoring the same records twice.
        val other = TaskRepository(AppDatabase.inMemory(context), settings) { }
        other.applyRecords(SyncMerger.merge(other.exportRecords(), exported, System.currentTimeMillis()).changedLocally)
        other.applyRecords(SyncMerger.merge(other.exportRecords(), exported, System.currentTimeMillis()).changedLocally)
        assertEquals(1, other.snapshot().tasks.size)
        assertEquals(exported.map { it.key }.toSet(), other.exportRecords().map { it.key }.toSet())
        assertEquals(SyncMapping.TASK, exported.single().type)
    }

    @Test fun reminderReconcileIsIdempotentAndSurvivesRebuild() = runBlocking {
        settings.setRemindersEnabled(true)
        settings.setRemindersOnThisDevice(true)
        val scheduler = ReminderScheduler(context, db, settings)
        repo.create(TaskDraft("Call", dueDate = today.plusDays(1), dueTime = LocalTime.of(10, 0), reminderOffsets = listOf(0, 15)))
        scheduler.reconcile()
        val first = db.alarms().getAll().map { it.key }.toSet()
        assertEquals(2, first.size)
        scheduler.reconcile()
        scheduler.reconcile(rebuildAll = true) // what boot / time-zone change does
        assertEquals(first, db.alarms().getAll().map { it.key }.toSet())

        // Snoozing twice keeps a single snooze alarm.
        val taskId = db.tasks().getAll().single().id
        scheduler.snooze(taskId, today.plusDays(1), 10)
        scheduler.snooze(taskId, today.plusDays(1), 10)
        assertEquals(1, db.alarms().getAll().count { it.key.endsWith("#snooze") })

        // Completing cancels them.
        repo.setCompleted(taskId, null, true)
        scheduler.reconcile()
        assertTrue(db.alarms().getAll().isEmpty())
    }
}
