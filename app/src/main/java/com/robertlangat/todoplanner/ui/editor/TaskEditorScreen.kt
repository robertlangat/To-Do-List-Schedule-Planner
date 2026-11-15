package com.robertlangat.todoplanner.ui.editor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robertlangat.todoplanner.container
import com.robertlangat.todoplanner.core.recurrence.Frequency
import com.robertlangat.todoplanner.core.recurrence.RecurrenceEnd
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import com.robertlangat.todoplanner.data.db.ListEntity
import com.robertlangat.todoplanner.domain.EditScope
import com.robertlangat.todoplanner.domain.Priority
import com.robertlangat.todoplanner.domain.SubtaskDraft
import com.robertlangat.todoplanner.reminders.ReminderTiming
import com.robertlangat.todoplanner.ui.components.DatePickerModal
import com.robertlangat.todoplanner.ui.components.ListDot
import com.robertlangat.todoplanner.ui.components.TimePickerModal
import com.robertlangat.todoplanner.ui.components.formatDate
import com.robertlangat.todoplanner.ui.components.formatTime
import com.robertlangat.todoplanner.ui.tasks.RecurringScopeDialog
import com.robertlangat.todoplanner.ui.theme.priorityColor
import java.time.DayOfWeek
import java.time.LocalDate

fun offsetLabel(minutes: Int): String = when {
    minutes == 0 -> "At time of task"
    minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} day${if (minutes == 1440) "" else "s"} before"
    minutes % 60 == 0 -> "${minutes / 60} hour${if (minutes == 60) "" else "s"} before"
    else -> "$minutes min before"
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TaskEditorScreen(lists: List<ListEntity>, onClose: () -> Unit, onMessage: (String) -> Unit, vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val d = state.draft
    val context = LocalContext.current
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var repeatMenu by remember { mutableStateOf(false) }
    var customRepeat by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    var customReminder by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var askScope by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onMessage("Notifications are off, so reminders won't appear. Your task is still saved.")
    }
    fun addReminder(offset: Int) {
        vm.addReminder(offset)
        if (Build.VERSION.SDK_INT >= 33 && !context.container.reminders.notificationsAllowed()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.done) {
        if (state.done) { state.error?.let(onMessage); onClose() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back without saving") } },
                title = { Text(if (state.isNew) "New task" else "Edit task") },
                actions = {
                    IconButton(onClick = { vm.edit { it.copy(starred = !it.starred) } }) {
                        Icon(if (d.starred) Icons.Filled.Star else Icons.Filled.StarBorder, if (d.starred) "Unstar" else "Star",
                            tint = if (d.starred) Color(0xFFF9A825) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { if (!state.isNew && state.isRecurring && state.occurrence != null && state.changed) askScope = true else vm.save() }) {
                        Text("Save")
                    }
                },
            )
        },
    ) { inner ->
        if (!state.loaded) return@Scaffold
        Column(
            Modifier.padding(inner).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = d.title, onValueChange = { v -> vm.edit { it.copy(title = v) } },
                label = { Text("Title") }, isError = state.error != null,
                supportingText = if (state.error != null) ({ Text(state.error ?: "") }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!state.isNew) {
                OutlinedButton(onClick = { vm.setCompleted(!state.completed) }) {
                    val suffix = if (state.isRecurring) " (this occurrence)" else ""
                    Text((if (state.completed) "Reopen" else "Mark complete") + suffix)
                }
            }
            OutlinedTextField(
                value = d.notes, onValueChange = { v -> vm.edit { it.copy(notes = v) } }, label = { Text("Notes (optional)") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )

            // List
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("List", Modifier.width(88.dp), style = MaterialTheme.typography.labelLarge)
                Column {
                    AssistChip(
                        onClick = { listMenu = true },
                        label = { Text(d.listId?.let { id -> lists.firstOrNull { it.id == id }?.name } ?: "Inbox") },
                        leadingIcon = { lists.firstOrNull { it.id == d.listId }?.let { ListDot(it.color) } },
                    )
                    DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                        DropdownMenuItem(text = { Text("Inbox") }, onClick = { vm.edit { it.copy(listId = null) }; listMenu = false })
                        lists.forEach { l ->
                            DropdownMenuItem(text = { Text(l.name) }, leadingIcon = { ListDot(l.color) }, onClick = { vm.edit { it.copy(listId = l.id) }; listMenu = false })
                        }
                    }
                }
            }

            // Priority (text + marks so it doesn't rely on color)
            Text("Priority", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Priority.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = d.priority == p.value,
                        onClick = { vm.edit { it.copy(priority = p.value) } },
                        shape = SegmentedButtonDefaults.itemShape(i, Priority.entries.size),
                        label = {
                            Text(
                                if (p == Priority.NONE) "None" else "${p.marks} ${p.label}",
                                color = if (p == Priority.NONE) MaterialTheme.colorScheme.onSurface else priorityColor(p.value),
                            )
                        },
                    )
                }
            }
            HorizontalDivider()

            // Date and time
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.CalendarMonth, null)
                AssistChip(onClick = { pickDate = true }, label = { Text(d.dueDate?.let { formatDate(it) } ?: "No date") })
                if (d.dueDate != null) {
                    AssistChip(
                        onClick = { pickTime = true }, label = { Text(d.dueTime?.let(::formatTime) ?: "All day") },
                        leadingIcon = { Icon(Icons.Filled.Schedule, null) },
                    )
                    if (d.dueTime != null) IconButton(onClick = { vm.edit { it.copy(dueTime = null) } }) { Icon(Icons.Filled.Close, "Make all-day") }
                    else IconButton(onClick = { vm.edit { it.copy(dueDate = null, dueTime = null, rule = null, reminderOffsets = emptyList()) } }) {
                        Icon(Icons.Filled.Close, "Remove date")
                    }
                }
            }
            if (d.dueDate == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val today = LocalDate.now()
                    FilterChip(false, onClick = { vm.edit { it.copy(dueDate = today) } }, label = { Text("Today") })
                    FilterChip(false, onClick = { vm.edit { it.copy(dueDate = today.plusDays(1)) } }, label = { Text("Tomorrow") })
                }
            }

            // Repeat
            if (d.dueDate != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Repeat, null)
                    Column {
                        AssistChip(onClick = { repeatMenu = true }, label = { Text(d.rule?.describe(d.dueDate) ?: "Doesn't repeat") })
                        DropdownMenu(expanded = repeatMenu, onDismissRequest = { repeatMenu = false }) {
                            fun pick(r: RecurrenceRule?) { vm.edit { it.copy(rule = r) }; repeatMenu = false }
                            DropdownMenuItem(text = { Text("Doesn't repeat") }, onClick = { pick(null) })
                            DropdownMenuItem(text = { Text("Daily") }, onClick = { pick(RecurrenceRule(Frequency.DAILY)) })
                            DropdownMenuItem(text = { Text("Every weekday (Mon–Fri)") }, onClick = { pick(RecurrenceRule(Frequency.WEEKDAYS)) })
                            DropdownMenuItem(text = { Text("Weekly on ${d.dueDate.dayOfWeek.name.lowercase().replaceFirstChar(Char::titlecase)}") }, onClick = { pick(RecurrenceRule(Frequency.WEEKLY)) })
                            DropdownMenuItem(text = { Text("Monthly on day ${d.dueDate.dayOfMonth}") }, onClick = { pick(RecurrenceRule(Frequency.MONTHLY)) })
                            DropdownMenuItem(text = { Text("Yearly (birthdays, anniversaries)") }, onClick = { pick(RecurrenceRule(Frequency.YEARLY)) })
                            DropdownMenuItem(text = { Text("Custom…") }, onClick = { repeatMenu = false; customRepeat = true })
                        }
                    }
                }
                if (d.rule != null && !state.isNew && state.isRecurring) {
                    Text("Editing a repeating task: you'll choose whether changes apply to this occurrence or this and future ones.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Reminders
            if (d.dueDate != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Notifications, null)
                    Column {
                        AssistChip(onClick = { reminderMenu = true }, label = { Text("Add reminder") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                        DropdownMenu(expanded = reminderMenu, onDismissRequest = { reminderMenu = false }) {
                            listOf(0, 5, 15, 30, 60, 120, 1440, 2880, 10080).forEach { m ->
                                DropdownMenuItem(text = { Text(offsetLabel(m)) }, onClick = { addReminder(m); reminderMenu = false })
                            }
                            DropdownMenuItem(text = { Text("Custom…") }, onClick = { reminderMenu = false; customReminder = true })
                        }
                    }
                }
                if (d.reminderOffsets.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.reminderOffsets.forEach { m ->
                            InputChip(
                                selected = false, onClick = { vm.removeReminder(m) }, label = { Text(offsetLabel(m)) },
                                trailingIcon = { Icon(Icons.Filled.Close, "Remove reminder ${offsetLabel(m)}") },
                            )
                        }
                    }
                    if (d.dueTime == null) Text("All-day task: reminders are based on your all-day reminder time (Settings).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (context.container.reminders.timing() == ReminderTiming.APPROXIMATE) {
                        Text("Reminders on this device are approximate (may arrive a few minutes late). You can allow exact alarms in Settings → Reminders.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider()

            OutlinedTextField(
                value = d.estimatedMinutes?.toString() ?: "",
                onValueChange = { v -> vm.edit { it.copy(estimatedMinutes = v.filter(Char::isDigit).take(4).toIntOrNull()) } },
                label = { Text("Estimated duration (minutes, optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            // Subtasks
            Text("Subtasks", style = MaterialTheme.typography.titleSmall)
            d.subtasks.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s.done, onCheckedChange = { vm.updateSubtask(i, s.copy(done = it)) },
                        modifier = Modifier.semantics { contentDescription = "Subtask ${s.title} done" })
                    OutlinedTextField(
                        value = s.title, onValueChange = { vm.updateSubtask(i, s.copy(title = it)) },
                        placeholder = { Text("Subtask") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.removeSubtask(i) }) { Icon(Icons.Filled.Close, "Remove subtask") }
                }
            }
            TextButton(onClick = { vm.addSubtask() }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Add subtask") }
            Spacer(Modifier.height(48.dp))
        }
    }

    if (pickDate) DatePickerModal(d.dueDate, onDismiss = { pickDate = false }) { date -> vm.edit { it.copy(dueDate = date) } }
    if (pickTime) TimePickerModal(d.dueTime, onDismiss = { pickTime = false }) { t -> vm.edit { it.copy(dueTime = t) } }
    if (customRepeat && d.dueDate != null) CustomRepeatDialog(d.rule, d.dueDate, onDismiss = { customRepeat = false }) { r -> vm.edit { it.copy(rule = r) }; customRepeat = false }
    if (customReminder) CustomReminderDialog(onDismiss = { customReminder = false }) { addReminder(it); customReminder = false }
    if (askScope) {
        RecurringScopeDialog(
            title = "Save changes to repeating task", confirmLabel = "Save", includeAll = true,
            onDismiss = { askScope = false }, onConfirm = { s -> askScope = false; vm.save(s) },
        )
    }
}

@Composable
private fun CustomReminderDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var amount by remember { mutableStateOf("10") }
    var unit by remember { mutableStateOf(1) } // 1 min, 60 hours, 1440 days
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom reminder") },
        text = {
            Column {
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit).take(4) }, label = { Text("How long before") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row { listOf(1 to "minutes", 60 to "hours", 1440 to "days").forEach { (u, l) ->
                    FilterChip(unit == u, { unit = u }, label = { Text(l) }, modifier = Modifier.padding(end = 6.dp)) } }
            }
        },
        confirmButton = { TextButton(enabled = amount.toIntOrNull() != null, onClick = { onPick((amount.toInt() * unit).coerceAtMost(60 * 24 * 60)) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomRepeatDialog(initial: RecurrenceRule?, start: LocalDate, onDismiss: () -> Unit, onSave: (RecurrenceRule) -> Unit) {
    var interval by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var freq by remember { mutableStateOf(initial?.frequency?.takeIf { it != Frequency.WEEKDAYS } ?: Frequency.WEEKLY) }
    var days by remember { mutableStateOf(initial?.byDays?.ifEmpty { null } ?: setOf(start.dayOfWeek)) }
    var endKind by remember { mutableStateOf(when (initial?.end) { is RecurrenceEnd.Until -> 1; is RecurrenceEnd.Count -> 2; else -> 0 }) }
    var until by remember { mutableStateOf((initial?.end as? RecurrenceEnd.Until)?.date ?: start.plusMonths(3)) }
    var count by remember { mutableStateOf(((initial?.end as? RecurrenceEnd.Count)?.count ?: 10).toString()) }
    var pickUntil by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom repeat") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every ")
                    OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(3) }, singleLine = true, modifier = Modifier.width(72.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(Frequency.DAILY to "days", Frequency.WEEKLY to "weeks", Frequency.MONTHLY to "months", Frequency.YEARLY to "years").forEach { (f, l) ->
                        FilterChip(freq == f, { freq = f }, label = { Text(l) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                if (freq == Frequency.WEEKLY) {
                    Text("On", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            val label = day.name.take(3).lowercase().replaceFirstChar(Char::titlecase)
                            FilterChip(day in days, { days = if (day in days && days.size > 1) days - day else days + day }, label = { Text(label) })
                        }
                    }
                }
                if (freq == Frequency.MONTHLY && start.dayOfMonth > 28) {
                    Text("In shorter months this falls on the last day of the month.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Ends", style = MaterialTheme.typography.labelLarge)
                listOf("Never", "On date", "After a number of times").forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().clickable { endKind = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(endKind == i, { endKind = i })
                        Text(l)
                        if (i == 1 && endKind == 1) TextButton(onClick = { pickUntil = true }) { Text(formatDate(until)) }
                        if (i == 2 && endKind == 2) OutlinedTextField(count, { count = it.filter(Char::isDigit).take(4) }, singleLine = true,
                            modifier = Modifier.width(80.dp).padding(start = 8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val end = when (endKind) {
                    1 -> RecurrenceEnd.Until(maxOf(until, start))
                    2 -> RecurrenceEnd.Count((count.toIntOrNull() ?: 1).coerceAtLeast(1))
                    else -> RecurrenceEnd.Never
                }
                onSave(RecurrenceRule(freq, (interval.toIntOrNull() ?: 1).coerceIn(1, 999), if (freq == Frequency.WEEKLY) days else emptySet(), end, initial?.exDates ?: emptySet()))
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickUntil) DatePickerModal(until, onDismiss = { pickUntil = false }) { until = it }
}
