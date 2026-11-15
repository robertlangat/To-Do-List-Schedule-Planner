package com.robertlangat.todoplanner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ListEntity::class, TaskEntity::class, SubtaskEntity::class, OccurrenceEntity::class,
        ScheduledAlarmEntity::class, SnoozeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lists(): ListDao
    abstract fun tasks(): TaskDao
    abstract fun subtasks(): SubtaskDao
    abstract fun occurrences(): OccurrenceDao
    abstract fun alarms(): AlarmDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "todoplanner.db")
                // Add explicit Migration objects when bumping the version; never destroy user data.
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
}
