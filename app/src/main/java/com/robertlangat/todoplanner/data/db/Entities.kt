package com.robertlangat.todoplanner.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Every synced row carries: a stable UUID id, a hybrid-logical-clock `version`, the `deviceId`
 * that wrote it, a `deleted` tombstone flag, and `seenDeleteVersion` (see core SyncMerger).
 * Tombstones stay in the table so deletions propagate; queries filter `deleted = 0`.
 */

@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** ARGB color, or null for the theme default. */
    val color: Long?,
    /** Key into ListIcons (e.g. "work"), or null. */
    val icon: String?,
    val sortOrder: Int,
    val version: Long,
    val deviceId: String,
    val deleted: Boolean = false,
    val seenDeleteVersion: Long = 0,
)

@Entity(tableName = "tasks", indices = [Index("listId"), Index("dueDate"), Index("deleted")])
data class TaskEntity(
    @PrimaryKey val id: String,
    /** Null = Inbox. */
    val listId: String?,
    val title: String,
    val notes: String = "",
    /** 0 none, 1 low, 2 medium, 3 high. */
    val priority: Int = 0,
    val starred: Boolean = false,
    /** Due date (one-off) or series start (recurring), as epoch day. Null = undated. */
    val dueDate: Long? = null,
    /** Minutes after midnight. Null with a dueDate = all-day. */
    val dueTimeMinutes: Int? = null,
    /** Recurrence rule (core RecurrenceRule.format()); null = one-off. */
    val rrule: String? = null,
    /** One-off completion. Recurring tasks use OccurrenceEntity rows instead. */
    val completed: Boolean = false,
    val completedAt: Long? = null,
    /** Comma-separated minutes-before offsets, e.g. "0,30". Empty = no reminders. */
    val reminderOffsets: String = "",
    val estimatedMinutes: Int? = null,
    /** For tasks split off a series ("this occurrence" / "this and future"). */
    val seriesParentId: String? = null,
    val createdAt: Long,
    val sortOrder: Long = 0,
    val version: Long,
    val deviceId: String,
    val deleted: Boolean = false,
    val seenDeleteVersion: Long = 0,
)

@Entity(tableName = "subtasks", indices = [Index("taskId")])
data class SubtaskEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val title: String,
    val done: Boolean = false,
    val sortOrder: Int = 0,
    val version: Long,
    val deviceId: String,
    val deleted: Boolean = false,
    val seenDeleteVersion: Long = 0,
)

/** Completion history for one occurrence of a recurring task. id = "taskId@yyyy-mm-dd". */
@Entity(tableName = "occurrences", indices = [Index("taskId")])
data class OccurrenceEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val date: Long,
    val completed: Boolean,
    val completedAt: Long?,
    val version: Long,
    val deviceId: String,
    val deleted: Boolean = false,
    val seenDeleteVersion: Long = 0,
) {
    companion object { fun idFor(taskId: String, epochDay: Long) = "$taskId@${java.time.LocalDate.ofEpochDay(epochDay)}" }
}

/** Local-only: alarms this device has handed to AlarmManager. Never synced. */
@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarmEntity(
    @PrimaryKey val key: String,
    val taskId: String,
    val occurrenceDate: Long,
    val triggerAt: Long,
    val delivered: Boolean = false,
)

/** Local-only: snoozed reminders on this device. */
@Entity(tableName = "snoozes")
data class SnoozeEntity(
    @PrimaryKey val key: String,
    val taskId: String,
    val occurrenceDate: Long,
    val until: Long,
)
