package com.robertlangat.todoplanner.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.robertlangat.todoplanner.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime
import java.util.UUID

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ReminderSound { DEFAULT, SILENT, ALARM }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentIndex: Int = 0,
    val focusModeDefault: Boolean = false,
    val remindersEnabled: Boolean = true,
    /** Per-device: whether reminders fire on *this* device when data is synced. */
    val remindersOnThisDevice: Boolean = true,
    val reminderSound: ReminderSound = ReminderSound.DEFAULT,
    val allDayReminderMinutes: Int = 9 * 60,
    val snoozeMinutes: Int = 10,
    val defaultReminderOffset: Int = 0,
    val aiEnabled: Boolean = false,
    val aiBackendUrl: String = BuildConfig.AI_BACKEND_URL,
    val driveSignedIn: Boolean = false,
    val driveAccount: String? = null,
    val autoSync: Boolean = true,
    val lastSyncAt: Long = 0,
    val lastSyncStatus: String? = null,
    val starterListsOffered: Boolean = false,
) {
    val allDayReminderTime: LocalTime get() = LocalTime.of(allDayReminderMinutes / 60, allDayReminderMinutes % 60)
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val accent = intPreferencesKey("accent")
        val focus = booleanPreferencesKey("focus_default")
        val remindersEnabled = booleanPreferencesKey("reminders_enabled")
        val remindersHere = booleanPreferencesKey("reminders_this_device")
        val sound = stringPreferencesKey("reminder_sound")
        val allDay = intPreferencesKey("all_day_minutes")
        val snooze = intPreferencesKey("snooze_minutes")
        val defaultOffset = intPreferencesKey("default_offset")
        val aiEnabled = booleanPreferencesKey("ai_enabled")
        val aiUrl = stringPreferencesKey("ai_url")
        val driveSignedIn = booleanPreferencesKey("drive_signed_in")
        val driveAccount = stringPreferencesKey("drive_account")
        val autoSync = booleanPreferencesKey("auto_sync")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncStatus = stringPreferencesKey("last_sync_status")
        val starterOffered = booleanPreferencesKey("starter_offered")
        val deviceId = stringPreferencesKey("device_id")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings() = Settings(
        themeMode = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        accentIndex = this[K.accent] ?: 0,
        focusModeDefault = this[K.focus] ?: false,
        remindersEnabled = this[K.remindersEnabled] ?: true,
        remindersOnThisDevice = this[K.remindersHere] ?: true,
        reminderSound = this[K.sound]?.let { runCatching { ReminderSound.valueOf(it) }.getOrNull() } ?: ReminderSound.DEFAULT,
        allDayReminderMinutes = this[K.allDay] ?: (9 * 60),
        snoozeMinutes = this[K.snooze] ?: 10,
        defaultReminderOffset = this[K.defaultOffset] ?: 0,
        aiEnabled = this[K.aiEnabled] ?: false,
        aiBackendUrl = this[K.aiUrl] ?: BuildConfig.AI_BACKEND_URL,
        driveSignedIn = this[K.driveSignedIn] ?: false,
        driveAccount = this[K.driveAccount],
        autoSync = this[K.autoSync] ?: true,
        lastSyncAt = this[K.lastSyncAt] ?: 0,
        lastSyncStatus = this[K.lastSyncStatus],
        starterListsOffered = this[K.starterOffered] ?: false,
    )

    suspend fun deviceId(): String {
        context.dataStore.data.first()[K.deviceId]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { if (it[K.deviceId] == null) it[K.deviceId] = id }
        return context.dataStore.data.first()[K.deviceId]!!
    }

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[K.theme] = mode.name }
    suspend fun setAccent(index: Int) = context.dataStore.edit { it[K.accent] = index }
    suspend fun setFocusDefault(v: Boolean) = context.dataStore.edit { it[K.focus] = v }
    suspend fun setRemindersEnabled(v: Boolean) = context.dataStore.edit { it[K.remindersEnabled] = v }
    suspend fun setRemindersOnThisDevice(v: Boolean) = context.dataStore.edit { it[K.remindersHere] = v }
    suspend fun setReminderSound(v: ReminderSound) = context.dataStore.edit { it[K.sound] = v.name }
    suspend fun setAllDayReminderMinutes(v: Int) = context.dataStore.edit { it[K.allDay] = v.coerceIn(0, 24 * 60 - 1) }
    suspend fun setSnoozeMinutes(v: Int) = context.dataStore.edit { it[K.snooze] = v.coerceIn(1, 24 * 60) }
    suspend fun setDefaultReminderOffset(v: Int) = context.dataStore.edit { it[K.defaultOffset] = v }
    suspend fun setAiEnabled(v: Boolean) = context.dataStore.edit { it[K.aiEnabled] = v }
    suspend fun setAiBackendUrl(v: String) = context.dataStore.edit { it[K.aiUrl] = v.trim() }
    suspend fun setAutoSync(v: Boolean) = context.dataStore.edit { it[K.autoSync] = v }
    suspend fun setStarterListsOffered() = context.dataStore.edit { it[K.starterOffered] = true }

    suspend fun setDriveAccount(signedIn: Boolean, account: String?) = context.dataStore.edit {
        it[K.driveSignedIn] = signedIn
        if (account != null) it[K.driveAccount] = account else it.remove(K.driveAccount)
    }

    suspend fun setSyncResult(at: Long?, status: String) = context.dataStore.edit {
        if (at != null) it[K.lastSyncAt] = at
        it[K.lastSyncStatus] = status
    }
}
