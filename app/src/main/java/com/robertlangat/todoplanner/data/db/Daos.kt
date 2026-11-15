package com.robertlangat.todoplanner.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {
    @Query("SELECT * FROM lists WHERE deleted = 0 ORDER BY sortOrder, name")
    fun observe(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE deleted = 0 ORDER BY sortOrder, name")
    suspend fun getAll(): List<ListEntity>

    @Query("SELECT * FROM lists")
    suspend fun getAllIncludingDeleted(): List<ListEntity>

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun get(id: String): ListEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM lists")
    suspend fun maxSortOrder(): Int

    @Upsert suspend fun upsert(list: ListEntity)
    @Upsert suspend fun upsertAll(lists: List<ListEntity>)

    @Query("DELETE FROM lists WHERE id = :id") suspend fun purge(id: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE deleted = 0")
    fun observe(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE deleted = 0")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks")
    suspend fun getAllIncludingDeleted(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id AND deleted = 0")
    fun observeOne(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE listId = :listId AND deleted = 0")
    suspend fun inList(listId: String): List<TaskEntity>

    @Upsert suspend fun upsert(task: TaskEntity)
    @Upsert suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE id = :id") suspend fun purge(id: String)

    @Query(
        "SELECT MAX(v) FROM (SELECT MAX(version) v FROM tasks UNION ALL SELECT MAX(version) FROM lists " +
            "UNION ALL SELECT MAX(version) FROM subtasks UNION ALL SELECT MAX(version) FROM occurrences)",
    )
    suspend fun maxVersion(): Long?
}

@Dao
interface SubtaskDao {
    @Query("SELECT * FROM subtasks WHERE deleted = 0 ORDER BY sortOrder")
    fun observe(): Flow<List<SubtaskEntity>>

    @Query("SELECT * FROM subtasks WHERE taskId = :taskId AND deleted = 0 ORDER BY sortOrder")
    suspend fun forTask(taskId: String): List<SubtaskEntity>

    @Query("SELECT * FROM subtasks")
    suspend fun getAllIncludingDeleted(): List<SubtaskEntity>

    @Query("SELECT * FROM subtasks WHERE id = :id")
    suspend fun get(id: String): SubtaskEntity?

    @Upsert suspend fun upsert(subtask: SubtaskEntity)
    @Upsert suspend fun upsertAll(subtasks: List<SubtaskEntity>)

    @Query("DELETE FROM subtasks WHERE id = :id") suspend fun purge(id: String)
}

@Dao
interface OccurrenceDao {
    @Query("SELECT * FROM occurrences WHERE deleted = 0")
    fun observe(): Flow<List<OccurrenceEntity>>

    @Query("SELECT * FROM occurrences WHERE deleted = 0")
    suspend fun getAll(): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrences WHERE taskId = :taskId AND deleted = 0")
    suspend fun forTask(taskId: String): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrences")
    suspend fun getAllIncludingDeleted(): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrences WHERE id = :id")
    suspend fun get(id: String): OccurrenceEntity?

    @Upsert suspend fun upsert(o: OccurrenceEntity)
    @Upsert suspend fun upsertAll(o: List<OccurrenceEntity>)

    @Query("DELETE FROM occurrences WHERE id = :id") suspend fun purge(id: String)
}

@Dao
interface AlarmDao {
    @Query("SELECT * FROM scheduled_alarms") suspend fun getAll(): List<ScheduledAlarmEntity>
    @Query("SELECT * FROM scheduled_alarms WHERE `key` = :key") suspend fun get(key: String): ScheduledAlarmEntity?
    @Upsert suspend fun upsert(a: ScheduledAlarmEntity)
    @Query("DELETE FROM scheduled_alarms WHERE `key` = :key") suspend fun delete(key: String)
    @Query("DELETE FROM scheduled_alarms") suspend fun clear()
    @Query("UPDATE scheduled_alarms SET delivered = 1 WHERE `key` = :key") suspend fun markDelivered(key: String)
    @Query("SELECT `key` FROM scheduled_alarms WHERE delivered = 1") suspend fun deliveredKeys(): List<String>

    @Query("SELECT * FROM snoozes") suspend fun snoozes(): List<SnoozeEntity>
    @Upsert suspend fun upsertSnooze(s: SnoozeEntity)
    @Query("DELETE FROM snoozes WHERE `key` = :key") suspend fun deleteSnooze(key: String)
    @Query("DELETE FROM snoozes WHERE taskId = :taskId AND occurrenceDate = :date") suspend fun deleteSnoozesFor(taskId: String, date: Long)
    @Query("DELETE FROM snoozes WHERE until < :before") suspend fun purgeSnoozes(before: Long)
}
