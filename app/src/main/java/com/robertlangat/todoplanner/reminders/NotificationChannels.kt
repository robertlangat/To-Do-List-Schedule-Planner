package com.robertlangat.todoplanner.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.robertlangat.todoplanner.R
import com.robertlangat.todoplanner.data.ReminderSound

/**
 * Android fixes a notification's sound per channel, so "configurable sound" is implemented as one
 * channel per sound option. Users can further customise each channel in system settings.
 */
object NotificationChannels {
    const val DEFAULT = "reminders_default"
    const val SILENT = "reminders_silent"
    const val ALARM = "reminders_alarm"

    fun idFor(sound: ReminderSound) = when (sound) {
        ReminderSound.DEFAULT -> DEFAULT
        ReminderSound.SILENT -> SILENT
        ReminderSound.ALARM -> ALARM
    }

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val default = NotificationChannel(DEFAULT, context.getString(R.string.channel_reminders_default), NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Task reminders with the default notification sound"
            enableVibration(true)
        }
        val silent = NotificationChannel(SILENT, context.getString(R.string.channel_reminders_silent), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Task reminders without sound"
            setSound(null, null)
            enableVibration(false)
        }
        val alarm = NotificationChannel(ALARM, context.getString(R.string.channel_reminders_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Task reminders using your alarm sound"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
        }
        nm.createNotificationChannels(listOf(default, silent, alarm))
    }
}
