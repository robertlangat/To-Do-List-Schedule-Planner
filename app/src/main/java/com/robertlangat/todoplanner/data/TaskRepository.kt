package com.robertlangat.todoplanner.data

import androidx.room.withTransaction
import com.robertlangat.todoplanner.core.recurrence.RecurrenceEngine
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import com.robertlangat.todoplanner.core.sync.HybridLogicalClock
import com.robertlangat.todoplanner.core.sync.SyncRecord
import com.robertlangat.todoplanner.data.db.AppDatabase
import com.robertlangat.todoplanner.data.db.ListEntity
import com.robertlangat.todoplanner.data.db.OccurrenceEntity
import com.robertlangat.todoplanner.data.db.SubtaskEntity
import com.robertlangat.todoplanner.data.db.TaskEntity
import com.robertlangat.todoplanner.domain.AppData
import com.robertlangat.todoplanner.domain.EditScope
import com.robertlangat.todoplanner.domain.TaskDraft
import com.robertlangat.todoplanner.domain.dueLocalDate
import com.robertlangat.todoplanner.domain.rule
import com.robertlangat.todoplanner.domain.toMinutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID

/** Snapshot of rows before a destructive change, so the UI can offer Undo. */
class UndoToken internal constructor(
    val tasks: List<TaskEntity> = emptyList(),
    val subtasks: List<SubtaskEntity> = emptyList(),
    val occurrences: List<OccurrenceEntity> = emptyList(),
    val createdTaskIds: List<String> = emptyList(),
)

/**
 * The single source of truth for tasks and lists. All writes go through here so that every change
 * gets a fresh HLC version and triggers reminder reconciliation, widget/Wear refresh and sync.
 */
class TaskRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val onChanged: suspend (fromSync: Boolean) -> Unit,
) {
    private val clockLock = Mutex()
    private var clock: HybridLogicalClock? = null
    private var deviceIdCache: String? = null

    private suspend fun version(): Long = clockLock.withLock {
        val c = clock ?: HybridLogicalClock(db.tasks().maxVersion() ?: 0L).also { clock = it }
        c.now()
    }

    private suspend fun observeVersion(v: Long) = clockLock.withLock {
        val c = clock ?: HybridLogicalClock(db.tasks().maxVersion() ?: 0L).also { clock = it }
        c.observe(v)
    }

    private suspend fun device(): String = deviceIdCache ?: settings.deviceId().also { deviceIdCache = it }

    val data: Flow<AppData> = combine(
        db.tasks().observe(), db.lists().observe(), db.subtasks().observe(), db.occurrences().observe(),
    ) { t, l, s, o -> AppData(t, l, s, o) }

    suspend fun snapshot(): AppData = AppData(
        db.tasks().getAll(), db.lists().getAll(), db.subtasks().getAll().filterNot { it.deleted }, db.occurrences().getAll(),
    )

    suspend fun task(id: String): TaskEntity? = db.tasks().get(id)?.takeUnless { it.deleted }
    suspend fun subtasks(taskId: String) = db.subtasks().forTask(taskId)

    // ---------------------------------------------------------------- create / edit

    suspend fun quickAdd(title: String, date: LocalDate? = null, listId: String? = null): String =
        create(TaskDraft(title = title.trim(), dueDate = date, listId = listId))

    suspend fun create(draft: TaskDraft): String {
        require(draft.title.isNotBlank()) { "Title is required" }
        val id = UUID.randomUUID().toString()
        db.withTransaction { writeTask(newTaskEntity(id, draft), draft) }
        onChanged(false)
        return id
    }

    private suspend fun newTaskEntity(id: String, d: TaskDraft, seriesParentId: String? = null) = TaskEntity(
        id = id,
        listId = d.listId,
        title = d.title.trim(),
        notes = d.notes,
        priority = d.priority,
        starred = d.starred,
        dueDate = d.dueDate?.toEpochDay(),
        dueTimeMinutes = if (d.dueDate != null) d.dueTime?.toMinutes() else null,
        rrule = if (d.dueDate != null) d.rule?.format() else null,
        reminderOffsets = if (d.dueDate != null) d.reminderOffsets.distinct().sorted().joinToString(",") else "",
        estimatedMinutes = d.estimatedMinutes,
        seriesParentId = seriesParentId,
        createdAt = System.currentTimeMillis(),
        sortOrder = System.currentTimeMillis(),
        version = version(),
        deviceId = device(),
    )

    private fun TaskEntity.applyDraft(d: TaskDraft) = copy(
        listId = d.listId,
        title = d.title.trim(),
        notes = d.notes,
        priority = d.priority,
        starred = d.starred,
        dueDate = d.dueDate?.toEpochDay(),
        dueTimeMinutes = if (d.dueDate != null) d.dueTime?.toMinutes() else null,
        rrule = if (d.dueDate != null) d.rule?.format() else null,
        reminderOffsets = if (d.dueDate != null) d.reminderOffsets.distinct().sorted().joinToString(",") else "",
        estimatedMinutes = d.estimatedMinutes,
    )

    /** Writes the task row and reconciles its subtasks with the draft (by subtask id). */
    private suspend fun writeTask(task: TaskEntity, draft: TaskDraft, copySubtaskIds: Boolean = true) {
        db.tasks().upsert(task)
        val existing = db.subtasks().forTask(task.id).associateBy { it.id }
        val keep = mutableSetOf<String>()
        draft.subtasks.filter { it.title.isNotBlank() }.forEachIndexed { i, s ->
            val sid = s.id?.takeIf { copySubtaskIds && it in existing } ?: UUID.randomUUID().toString()
            keep += sid
            val old = existing[sid]
            if (old == null || old.title != s.title.trim() || old.done != s.done || old.sortOrder != i) {
                db.subtasks().upsert(
                    SubtaskEntity(sid, task.id, s.title.trim(), s.done, i, version(), device()),
                )
            }
        }
        existing.values.filter { it.id !in keep }.forEach {
            db.subtasks().upsert(it.copy(deleted = true, version = version(), deviceId = device()))
        }
    }

    /**
     * Edits a task. For recurring tasks:
     * - ALL: rewrites the series in place.
     * - THIS_OCCURRENCE: excludes [occurrence] from the series and creates a standalone task for it.
     * - THIS_AND_FUTURE: ends the series before [occurrence] and starts a new series from it.
     * Alarm keys include the task id, so the old occurrences' reminders are cancelled and only the
     * new task's are scheduled - never both.
     */
    suspend fun update(taskId: String, draft: TaskDraft, scope: EditScope = EditScope.ALL, occurrence: LocalDate? = null) {
        val current = db.tasks().get(taskId) ?: return
        val rule = current.rule
        val start = current.dueLocalDate
        db.withTransaction {
            if (rule == null || start == null || occurrence == null || scope == EditScope.ALL) {
                val updated = current.applyDraft(draft).copy(version = version(), deviceId = device())
                writeTask(updated, draft)
                // If the start date or rule changed, drop completion rows that no longer match an occurrence.
                if (current.rrule != null) pruneOrphanCompletions(updated)
            } else if (scope == EditScope.THIS_OCCURRENCE) {
                editSingleOccurrence(current, rule, occurrence, draft)
            } else {
                editThisAndFuture(current, rule, start, occurrence, draft)
            }
        }
        onChanged(false)
    }

    private suspend fun editSingleOccurrence(current: TaskEntity, rule: RecurrenceRule, occurrence: LocalDate, draft: TaskDraft) {
        db.tasks().upsert(current.copy(rrule = rule.withExDate(occurrence).format(), version = version(), deviceId = device()))
        val single = draft.copy(rule = null, dueDate = draft.dueDate ?: occurrence)
        val newId = UUID.randomUUID().toString()
        val wasDone = db.occurrences().forTask(current.id).any { it.completed && it.date == occurrence.toEpochDay() }
        writeTask(newTaskEntity(newId, single, seriesParentId = current.id).copy(completed = wasDone), single, copySubtaskIds = false)
        moveCompletions(current.id, null, setOf(occurrence))
    }

    private suspend fun editThisAndFuture(current: TaskEntity, rule: RecurrenceRule, start: LocalDate, occurrence: LocalDate, draft: TaskDraft) {
        val (oldRule, newRule) = RecurrenceEngine.split(start, rule, occurrence)
        if (oldRule == null) {
            val updated = current.applyDraft(draft).copy(version = version(), deviceId = device())
            writeTask(updated, draft)
            pruneOrphanCompletions(updated)
            return
        }
        db.tasks().upsert(current.copy(rrule = oldRule.format(), version = version(), deviceId = device()))
        val newStart = draft.dueDate ?: occurrence
        // Keep the split end (UNTIL/COUNT remainder) unless the user changed the repeat pattern itself.
        val futureRule = draft.rule?.let { if (it.copy(end = rule.end, exDates = rule.exDates) == rule) newRule else it }
        val future = draft.copy(dueDate = newStart, rule = futureRule)
        val newId = UUID.randomUUID().toString()
        writeTask(newTaskEntity(newId, future, seriesParentId = current.id), future, copySubtaskIds = false)
        // Completion history at/after the split moves to the new series when the dates still line up.
        val moved = db.occurrences().forTask(current.id).map { LocalDate.ofEpochDay(it.date) }.filter { it >= occurrence }.toSet()
        moveCompletions(current.id, newId.takeIf { newStart == occurrence }, moved)
    }

    private suspend fun pruneOrphanCompletions(task: TaskEntity) {
        val start = task.dueLocalDate
        val rule = task.rule
        for (o in db.occurrences().forTask(task.id)) {
            val date = LocalDate.ofEpochDay(o.date)
            val valid = start != null && rule != null && RecurrenceEngine.isOccurrence(start, rule, date)
            if (!valid && o.completed) {
                // Keep history but detach from the live series by tombstoning; it no longer maps to an occurrence.
                db.occurrences().upsert(o.copy(deleted = true, version = version(), deviceId = device()))
            }
        }
    }

    private suspend fun moveCompletions(fromTaskId: String, toTaskId: String?, dates: Set<LocalDate>) {
        for (o in db.occurrences().forTask(fromTaskId)) {
            val date = LocalDate.ofEpochDay(o.date)
            if (date !in dates) continue
            db.occurrences().upsert(o.copy(deleted = true, version = version(), deviceId = device()))
            if (toTaskId != null && o.completed) {
                db.occurrences().upsert(
                    OccurrenceEntity(OccurrenceEntity.idFor(toTaskId, o.date), toTaskId, o.date, true, o.completedAt, version(), device()),
                )
            }
        }
    }

    // ---------------------------------------------------------------- completion

    /** Completes or reopens a one-off task or a single occurrence of a recurring task. */
    suspend fun setCompleted(taskId: String, occurrence: LocalDate?, completed: Boolean) {
        val task = db.tasks().get(taskId)?.takeUnless { it.deleted } ?: return
        db.withTransaction {
            if (task.rrule != null && occurrence != null) {
                val id = OccurrenceEntity.idFor(taskId, occurrence.toEpochDay())
                val existing = db.occurrences().get(id)
                db.occurrences().upsert(
                    OccurrenceEntity(
                        id, taskId, occurrence.toEpochDay(), completed,
                        if (completed) System.currentTimeMillis() else null,
                        version(), device(),
                        seenDeleteVersion = existing?.takeIf { it.deleted }?.version ?: existing?.seenDeleteVersion ?: 0,
                    ),
                )
            } else {
                db.tasks().upsert(
                    task.copy(
                        completed = completed, completedAt = if (completed) System.currentTimeMillis() else null,
                        version = version(), deviceId = device(),
                    ),
                )
            }
            if (occurrence != null) db.alarms().deleteSnoozesFor(taskId, occurrence.toEpochDay())
        }
        onChanged(false)
    }

    suspend fun setSubtaskDone(subtaskId: String, done: Boolean) {
        val s = db.subtasks().get(subtaskId) ?: return
        db.subtasks().upsert(s.copy(done = done, version = version(), deviceId = device()))
        onChanged(false)
    }

    // ---------------------------------------------------------------- delete / undo

    suspend fun delete(taskId: String, scope: EditScope = EditScope.ALL, occurrence: LocalDate? = null): UndoToken? {
        val task = db.tasks().get(taskId)?.takeUnless { it.deleted } ?: return null
        val rule = task.rule
        val start = task.dueLocalDate
        val token = db.withTransaction {
            if (rule != null && start != null && occurrence != null && scope != EditScope.ALL) {
                val partial = UndoToken(tasks = listOf(task))
                val newRule = if (scope == EditScope.THIS_OCCURRENCE) rule.withExDate(occurrence)
                else RecurrenceEngine.split(start, rule, occurrence).first
                if (newRule == null) {
                    tombstoneTask(task)
                } else {
                    db.tasks().upsert(task.copy(rrule = newRule.format(), version = version(), deviceId = device()))
                }
                partial
            } else {
                val full = UndoToken(
                    tasks = listOf(task),
                    subtasks = db.subtasks().forTask(taskId),
                    occurrences = db.occurrences().forTask(taskId),
                )
                tombstoneTask(task)
                full
            }
        }
        onChanged(false)
        return token
    }

    private suspend fun tombstoneTask(task: TaskEntity) {
        val v = version()
        db.tasks().upsert(task.copy(deleted = true, version = v, deviceId = device()))
        db.subtasks().forTask(task.id).forEach { db.subtasks().upsert(it.copy(deleted = true, version = version(), deviceId = device())) }
        db.occurrences().forTask(task.id).forEach { db.occurrences().upsert(it.copy(deleted = true, version = version(), deviceId = device())) }
    }

    /** Restores rows captured in [token]. Records the tombstone version so sync treats this as an explicit restore. */
    suspend fun undo(token: UndoToken) {
        db.withTransaction {
            for (t in token.tasks) {
                val cur = db.tasks().get(t.id)
                db.tasks().upsert(t.copy(version = version(), deviceId = device(), deleted = false, seenDeleteVersion = cur?.version ?: 0))
            }
            for (s in token.subtasks) {
                val cur = db.subtasks().get(s.id)
                db.subtasks().upsert(s.copy(version = version(), deviceId = device(), deleted = false, seenDeleteVersion = cur?.version ?: 0))
            }
            for (o in token.occurrences) {
                val cur = db.occurrences().get(o.id)
                db.occurrences().upsert(o.copy(version = version(), deviceId = device(), deleted = false, seenDeleteVersion = cur?.version ?: 0))
            }
            for (id in token.createdTaskIds) db.tasks().get(id)?.let { tombstoneTask(it) }
        }
        onChanged(false)
    }

    // ---------------------------------------------------------------- misc task actions

    suspend fun duplicate(taskId: String): String? {
        val t = db.tasks().get(taskId)?.takeUnless { it.deleted } ?: return null
        val newId = UUID.randomUUID().toString()
        db.withTransaction {
            db.tasks().upsert(
                t.copy(
                    id = newId, title = t.title, completed = false, completedAt = null, seriesParentId = null,
                    createdAt = System.currentTimeMillis(), sortOrder = System.currentTimeMillis(),
                    version = version(), deviceId = device(), seenDeleteVersion = 0,
                ),
            )
            db.subtasks().forTask(taskId).forEach {
                db.subtasks().upsert(it.copy(id = UUID.randomUUID().toString(), taskId = newId, done = false, version = version(), deviceId = device()))
            }
        }
        onChanged(false)
        return newId
    }

    suspend fun move(taskId: String, listId: String?) {
        val t = db.tasks().get(taskId) ?: return
        db.tasks().upsert(t.copy(listId = listId, version = version(), deviceId = device()))
        onChanged(false)
    }

    suspend fun setStarred(taskId: String, starred: Boolean) {
        val t = db.tasks().get(taskId) ?: return
        db.tasks().upsert(t.copy(starred = starred, version = version(), deviceId = device()))
        onChanged(false)
    }

    /** Moves a one-off task, or a single occurrence of a recurring task, to [newDate] (null = undated). */
    suspend fun reschedule(taskId: String, occurrence: LocalDate?, newDate: LocalDate?) {
        val t = db.tasks().get(taskId) ?: return
        if (t.rrule != null && occurrence != null) {
            val draft = draftOf(t).copy(dueDate = newDate ?: occurrence, rule = null)
            update(taskId, draft, EditScope.THIS_OCCURRENCE, occurrence)
        } else {
            db.tasks().upsert(
                t.copy(
                    dueDate = newDate?.toEpochDay(),
                    dueTimeMinutes = if (newDate == null) null else t.dueTimeMinutes,
                    reminderOffsets = if (newDate == null) "" else t.reminderOffsets,
                    version = version(), deviceId = device(),
                ),
            )
            onChanged(false)
        }
    }

    suspend fun draftOf(t: TaskEntity): TaskDraft = TaskDraft(
        title = t.title, notes = t.notes, listId = t.listId, priority = t.priority, starred = t.starred,
        dueDate = t.dueLocalDate, dueTime = t.dueTimeMinutes?.let { java.time.LocalTime.of(it / 60, it % 60) },
        rule = t.rule, reminderOffsets = t.reminderOffsets.split(',').mapNotNull { it.toIntOrNull() },
        estimatedMinutes = t.estimatedMinutes,
        subtasks = db.subtasks().forTask(t.id).map { com.robertlangat.todoplanner.domain.SubtaskDraft(it.id, it.title, it.done) },
    )

    // ---------------------------------------------------------------- lists

    suspend fun createList(name: String, color: Long? = null, icon: String? = null): String {
        require(name.isNotBlank()) { "List name is required" }
        val id = UUID.randomUUID().toString()
        db.lists().upsert(ListEntity(id, name.trim(), color, icon, db.lists().maxSortOrder() + 1, version(), device()))
        onChanged(false)
        return id
    }

    suspend fun updateList(id: String, name: String, color: Long?, icon: String?) {
        val l = db.lists().get(id) ?: return
        db.lists().upsert(l.copy(name = name.trim(), color = color, icon = icon, version = version(), deviceId = device()))
        onChanged(false)
    }

    /** Deletes a list; its tasks move to the Inbox rather than being deleted. */
    suspend fun deleteList(id: String) {
        val l = db.lists().get(id) ?: return
        db.withTransaction {
            db.tasks().inList(id).forEach { db.tasks().upsert(it.copy(listId = null, version = version(), deviceId = device())) }
            db.lists().upsert(l.copy(deleted = true, version = version(), deviceId = device()))
        }
        onChanged(false)
    }

    suspend fun addStarterLists(keys: Collection<String>) {
        val existing = db.lists().getAll().map { it.name.lowercase() }.toSet()
        for (s in StarterLists.all.filter { it.key in keys }) {
            if (s.name.lowercase() !in existing) createList(s.name, s.color, s.key)
        }
        settings.setStarterListsOffered()
    }

    // ---------------------------------------------------------------- sync support

    suspend fun exportRecords(): List<SyncRecord> = SyncMapping.toRecords(
        db.lists().getAllIncludingDeleted(), db.tasks().getAllIncludingDeleted(),
        db.subtasks().getAllIncludingDeleted(), db.occurrences().getAllIncludingDeleted(),
    )

    /** Applies merged records atomically. Either all are written or none are. */
    suspend fun applyRecords(records: List<SyncRecord>, purgeKeys: Set<String> = emptySet()) {
        if (records.isEmpty()) return
        db.withTransaction {
            for (r in records) {
                observeVersion(r.version)
                val purge = r.key in purgeKeys
                when (r.type) {
                    SyncMapping.LIST -> if (purge) db.lists().purge(r.id) else SyncMapping.list(r)?.let { db.lists().upsert(it) }
                    SyncMapping.TASK -> if (purge) db.tasks().purge(r.id) else SyncMapping.task(r)?.let { db.tasks().upsert(it) }
                    SyncMapping.SUBTASK -> if (purge) db.subtasks().purge(r.id) else SyncMapping.subtask(r)?.let { db.subtasks().upsert(it) }
                    SyncMapping.OCCURRENCE -> if (purge) db.occurrences().purge(r.id) else SyncMapping.occurrence(r)?.let { db.occurrences().upsert(it) }
                }
            }
        }
        onChanged(true)
    }
}
