package com.robertlangat.todoplanner.data

import com.robertlangat.todoplanner.core.json.bool
import com.robertlangat.todoplanner.core.json.int
import com.robertlangat.todoplanner.core.json.long
import com.robertlangat.todoplanner.core.json.str
import com.robertlangat.todoplanner.core.sync.SyncRecord
import com.robertlangat.todoplanner.data.db.ListEntity
import com.robertlangat.todoplanner.data.db.OccurrenceEntity
import com.robertlangat.todoplanner.data.db.SubtaskEntity
import com.robertlangat.todoplanner.data.db.TaskEntity

/** Converts Room rows to/from the generic records used by sync and file backups. */
object SyncMapping {
    const val LIST = "list"
    const val TASK = "task"
    const val SUBTASK = "subtask"
    const val OCCURRENCE = "occurrence"

    fun toRecords(
        lists: List<ListEntity>, tasks: List<TaskEntity>, subtasks: List<SubtaskEntity>, occurrences: List<OccurrenceEntity>,
    ): List<SyncRecord> = buildList {
        lists.forEach {
            add(SyncRecord(LIST, it.id, it.version, it.deviceId, it.deleted,
                mapOf("name" to it.name, "color" to it.color, "icon" to it.icon, "sortOrder" to it.sortOrder), it.seenDeleteVersion))
        }
        tasks.forEach {
            add(SyncRecord(TASK, it.id, it.version, it.deviceId, it.deleted, mapOf(
                "listId" to it.listId, "title" to it.title, "notes" to it.notes, "priority" to it.priority,
                "starred" to it.starred, "dueDate" to it.dueDate, "dueTimeMinutes" to it.dueTimeMinutes,
                "rrule" to it.rrule, "completed" to it.completed, "completedAt" to it.completedAt,
                "reminderOffsets" to it.reminderOffsets, "estimatedMinutes" to it.estimatedMinutes,
                "seriesParentId" to it.seriesParentId, "createdAt" to it.createdAt, "sortOrder" to it.sortOrder,
            ), it.seenDeleteVersion))
        }
        subtasks.forEach {
            add(SyncRecord(SUBTASK, it.id, it.version, it.deviceId, it.deleted,
                mapOf("taskId" to it.taskId, "title" to it.title, "done" to it.done, "sortOrder" to it.sortOrder), it.seenDeleteVersion))
        }
        occurrences.forEach {
            add(SyncRecord(OCCURRENCE, it.id, it.version, it.deviceId, it.deleted,
                mapOf("taskId" to it.taskId, "date" to it.date, "completed" to it.completed, "completedAt" to it.completedAt),
                it.seenDeleteVersion))
        }
    }

    fun list(r: SyncRecord): ListEntity? {
        with(r.data) {
        return ListEntity(r.id, str("name") ?: return null, long("color"), str("icon"), int("sortOrder") ?: 0,
            r.version, r.deviceId, r.deleted, r.seenDeleteVersion)
        }
    }

    fun task(r: SyncRecord): TaskEntity? {
        with(r.data) {
        return TaskEntity(
            id = r.id, listId = str("listId"), title = str("title") ?: return null, notes = str("notes") ?: "",
            priority = int("priority") ?: 0, starred = bool("starred") ?: false, dueDate = long("dueDate"),
            dueTimeMinutes = int("dueTimeMinutes"), rrule = str("rrule"), completed = bool("completed") ?: false,
            completedAt = long("completedAt"), reminderOffsets = str("reminderOffsets") ?: "",
            estimatedMinutes = int("estimatedMinutes"), seriesParentId = str("seriesParentId"),
            createdAt = long("createdAt") ?: 0L, sortOrder = long("sortOrder") ?: 0L,
            version = r.version, deviceId = r.deviceId, deleted = r.deleted, seenDeleteVersion = r.seenDeleteVersion,
        )
        }
    }

    fun subtask(r: SyncRecord): SubtaskEntity? {
        with(r.data) {
        return SubtaskEntity(r.id, str("taskId") ?: return null, str("title") ?: "", bool("done") ?: false, int("sortOrder") ?: 0,
            r.version, r.deviceId, r.deleted, r.seenDeleteVersion)
        }
    }

    fun occurrence(r: SyncRecord): OccurrenceEntity? {
        with(r.data) {
        return OccurrenceEntity(r.id, str("taskId") ?: return null, long("date") ?: return null, bool("completed") ?: false,
            long("completedAt"), r.version, r.deviceId, r.deleted, r.seenDeleteVersion)
        }
    }
}
