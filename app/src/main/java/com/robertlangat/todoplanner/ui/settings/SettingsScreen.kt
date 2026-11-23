package com.robertlangat.todoplanner.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertlangat.todoplanner.BuildConfig
import com.robertlangat.todoplanner.container
import com.robertlangat.todoplanner.data.ReminderSound
import com.robertlangat.todoplanner.data.Settings
import com.robertlangat.todoplanner.data.StarterLists
import com.robertlangat.todoplanner.data.ThemeMode
import com.robertlangat.todoplanner.reminders.NotificationChannels
import com.robertlangat.todoplanner.reminders.ReminderTiming
import com.robertlangat.todoplanner.sync.DriveAuth
import com.robertlangat.todoplanner.sync.DriveClient
import com.robertlangat.todoplanner.sync.SyncManager
import com.robertlangat.todoplanner.sync.SyncWorker
import com.robertlangat.todoplanner.ui.components.ListDot
import com.robertlangat.todoplanner.ui.components.TimePickerModal
import com.robertlangat.todoplanner.ui.components.formatTime
import com.robertlangat.todoplanner.ui.theme.Accents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: Settings, onClose: () -> Unit, onMessage: (String) -> Unit, onAddStarterLists: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var timing by remember { mutableStateOf(c.reminders.timing()) }
    var notificationsOn by remember { mutableStateOf(c.reminders.notificationsAllowed()) }
    var busy by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var pickAllDay by remember { mutableStateOf(false) }
    var starterDialog by remember { mutableStateOf(false) }
    var aiUrl by remember(settings.aiBackendUrl) { mutableStateOf(settings.aiBackendUrl) }
    var pendingMode by remember { mutableStateOf(SyncManager.Mode.SYNC) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val newTiming = c.reminders.timing()
        if (newTiming != timing) scope.launch { c.reminders.reconcile(rebuildAll = true) }
        timing = newTiming
        notificationsOn = c.reminders.notificationsAllowed()
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsOn = granted
        if (!granted) onMessage("Notifications denied. Tasks still work; reminders won't appear until you allow them.")
    }

    fun runSync(mode: SyncManager.Mode, token: String? = null) {
        busy = true
        scope.launch {
            val r = c.sync.run(mode, token)
            busy = false
            when (r) {
                is SyncManager.Result.Success -> onMessage(r.message)
                is SyncManager.Result.Failure -> onMessage(r.message)
                SyncManager.Result.NeedsSignIn -> onMessage("Google access expired. Please sign in again.")
            }
        }
    }

    suspend fun onAuthorized(token: String, mode: SyncManager.Mode) {
        val email = withContext(Dispatchers.IO) { DriveClient(token).accountEmail() }
        c.settings.setDriveAccount(true, email)
        SyncWorker.schedulePeriodic(context)
        runSync(mode, token)
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val token = if (result.resultCode == Activity.RESULT_OK) c.driveAuth.tokenFromResult(result.data) else null
        if (token == null) { busy = false; onMessage("Google sign-in was cancelled. Nothing changed.") }
        else scope.launch { onAuthorized(token, pendingMode) }
    }

    fun signInThen(mode: SyncManager.Mode) {
        pendingMode = mode
        busy = true
        scope.launch {
            when (val o = c.driveAuth.authorize(context)) {
                is DriveAuth.Outcome.Authorized -> onAuthorized(o.accessToken, mode)
                is DriveAuth.Outcome.NeedsUserConsent -> consent.launch(IntentSenderRequest.Builder(o.pendingIntent.intentSender).build())
                is DriveAuth.Outcome.Failed -> { busy = false; onMessage("Google sign-in isn't available: ${o.message}") }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { onMessage(runCatching { c.sync.exportTo(uri) }.getOrElse { "Export failed: ${it.message}" }) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { onMessage(runCatching { c.sync.importFrom(uri) }.getOrElse { "Import failed: ${it.message}. Your tasks are unchanged." }) }
    }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("Settings") },
        )
    }) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Section("Appearance")
            ThemeMode.entries.forEach { m ->
                RadioRow(m.name.lowercase().replaceFirstChar(Char::titlecase).let { if (m == ThemeMode.SYSTEM) "Follow system" else it }, settings.themeMode == m) {
                    scope.launch { c.settings.setTheme(m) }
                }
            }
            Text("Accent color", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Accents.forEachIndexed { i, a ->
                    if (i == 0 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@forEachIndexed
                    val selected = settings.accentIndex == i
                    Box(
                        Modifier.size(48.dp).clickable { scope.launch { c.settings.setAccent(i) } }
                            .semantics { contentDescription = "${a.name} accent"; this.selected = selected },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(36.dp).background(a.light, CircleShape)
                            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier))
                        if (selected) Icon(Icons.Filled.Check, null, tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }

            Section("Tasks")
            SwitchRow("Open in Focus view (3 priority tasks)", settings.focusModeDefault) { v -> scope.launch { c.settings.setFocusDefault(v) } }
            OutlinedButton(onClick = { starterDialog = true }) { Text("Add starter lists…") }

            Section("Reminders")
            SwitchRow("Reminders", settings.remindersEnabled) { v ->
                scope.launch { c.settings.setRemindersEnabled(v); c.reminders.reconcile() }
                if (v && Build.VERSION.SDK_INT >= 33 && !notificationsOn) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            SwitchRow("Fire reminders on this device", settings.remindersOnThisDevice,
                "Turn off on devices where you don't want synced reminders to ring (e.g. a tablet).") { v ->
                scope.launch { c.settings.setRemindersOnThisDevice(v); c.reminders.reconcile() }
            }
            if (!notificationsOn) {
                Text("Notifications are blocked for this app, so reminders can't appear.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Allow notifications") }
            }
            Text(
                if (timing == ReminderTiming.EXACT) "Timing: exact - reminders arrive on time."
                else "Timing: approximate - Android may deliver reminders a few minutes late because exact alarms aren't allowed for this app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (timing == ReminderTiming.APPROXIMATE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }) { Text("Allow exact reminders") }
            }
            Text("Sound", style = MaterialTheme.typography.labelLarge)
            listOf(ReminderSound.DEFAULT to "Default notification sound", ReminderSound.SILENT to "Silent", ReminderSound.ALARM to "Alarm sound").forEach { (snd, label) ->
                RadioRow(label, settings.reminderSound == snd) { scope.launch { c.settings.setReminderSound(snd) } }
            }
            TextButton(onClick = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, NotificationChannels.idFor(settings.reminderSound)),
                )
            }) { Text("Choose a custom sound for this option…") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("All-day task reminder time", Modifier.weight(1f))
                TextButton(onClick = { pickAllDay = true }) { Text(formatTime(settings.allDayReminderTime)) }
            }
            Text("Snooze length", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5, 10, 15, 30, 60).forEach { m ->
                    FilterChip(settings.snoozeMinutes == m, { scope.launch { c.settings.setSnoozeMinutes(m) } }, label = { Text("$m min") })
                }
            }

            Section("Google Drive sync")
            if (!settings.driveSignedIn) {
                Text("Optional. Back up and sync your tasks to a private app folder in your Google Drive. The app can't see your other Drive files.",
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { signInThen(SyncManager.Mode.SYNC) }, enabled = !busy) { Text("Sign in with Google") }
            } else {
                Text("Signed in" + (settings.driveAccount?.let { " as $it" } ?: ""))
                Text(
                    (if (settings.lastSyncAt > 0) "Last sync: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(settings.lastSyncAt)) else "Not synced yet") +
                        (settings.lastSyncStatus?.let { "\n$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                SwitchRow("Sync automatically", settings.autoSync) { v ->
                    scope.launch { c.settings.setAutoSync(v); if (v) SyncWorker.schedulePeriodic(context) else SyncWorker.cancelAll(context) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { runSync(SyncManager.Mode.BACKUP) }, enabled = !busy) { Text("Back up now") }
                    OutlinedButton(onClick = { confirmRestore = true }, enabled = !busy) { Text("Restore") }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                }
                TextButton(onClick = {
                    scope.launch { c.driveAuth.signOut(); SyncWorker.cancelAll(context); onMessage("Signed out. Your tasks stay on this device.") }
                }) { Text("Sign out") }
            }

            Section("Backup file")
            Text("Save or load a backup file using Android's file picker (no storage permission needed).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("things-to-do-next-backup-${LocalDate.now()}.json") }) { Text("Export") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
            }

            Section("AI planner")
            SwitchRow("Enable AI planner", settings.aiEnabled,
                "Uses your configured backend. You approve exactly what is sent each time. The offline planner always works.") { v ->
                scope.launch { c.settings.setAiEnabled(v) }
            }
            OutlinedTextField(
                value = aiUrl, onValueChange = { aiUrl = it.trim() }, label = { Text("Backend URL (https://…)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { scope.launch { c.settings.setAiBackendUrl(aiUrl); onMessage("AI backend saved") } }),
            )
            if (aiUrl != settings.aiBackendUrl) TextButton(onClick = { scope.launch { c.settings.setAiBackendUrl(aiUrl); onMessage("AI backend saved") } }) { Text("Save URL") }

            Section("Ads and privacy")
            Text("This app is free and shows ads from Google AdMob. Your tasks are never sent to advertisers.",
                style = MaterialTheme.typography.bodySmall)
            val privacyRequired by c.ads.privacyOptionsRequired.collectAsStateWithLifecycle()
            if (privacyRequired) {
                OutlinedButton(onClick = { (context as? Activity)?.let { c.ads.showPrivacyOptions(it) } }) { Text("Ad privacy options") }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickAllDay) TimePickerModal(settings.allDayReminderTime, onDismiss = { pickAllDay = false }) { t: LocalTime ->
        scope.launch { c.settings.setAllDayReminderMinutes(t.hour * 60 + t.minute); c.reminders.reconcile() }
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore from Google Drive?") },
            text = { Text("Tasks from your Drive copy are merged into this device. Nothing on this device is lost, but tasks deleted elsewhere will be removed here. Reminders are rebuilt afterwards.") },
            confirmButton = { TextButton(onClick = { confirmRestore = false; runSync(SyncManager.Mode.RESTORE) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
    if (starterDialog) {
        var picked by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { starterDialog = false },
            title = { Text("Starter lists") },
            text = {
                Column {
                    StarterLists.all.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { picked = if (s.key in picked) picked - s.key else picked + s.key }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(s.key in picked, { picked = if (s.key in picked) picked - s.key else picked + s.key })
                            ListDot(s.color); Spacer(Modifier.size(8.dp)); Text(s.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = { onAddStarterLists(picked); starterDialog = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { starterDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp).semantics { heading() })
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { this.selected = selected }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}
