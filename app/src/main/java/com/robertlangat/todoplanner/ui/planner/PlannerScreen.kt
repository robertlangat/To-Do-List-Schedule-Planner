package com.robertlangat.todoplanner.ui.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robertlangat.todoplanner.core.planner.DraftFlag
import com.robertlangat.todoplanner.core.planner.DraftItem
import com.robertlangat.todoplanner.core.planner.PlanSource
import com.robertlangat.todoplanner.core.planner.TimeWindow
import com.robertlangat.todoplanner.data.Settings
import com.robertlangat.todoplanner.ui.components.DatePickerModal
import com.robertlangat.todoplanner.ui.components.TimePickerModal
import com.robertlangat.todoplanner.ui.components.formatDate
import com.robertlangat.todoplanner.ui.components.formatTime
import com.robertlangat.todoplanner.ui.tasks.rememberVoiceInput

private fun flagText(f: DraftFlag) = when (f) {
    DraftFlag.OVERLAPS_EXISTING -> "Overlaps a scheduled task"
    DraftFlag.OVERLAPS_DRAFT -> "Overlaps another suggestion"
    DraftFlag.AFTER_DEADLINE -> "After its deadline"
    DraftFlag.OUTSIDE_AVAILABILITY -> "Outside your available time"
    DraftFlag.UNSCHEDULED -> "Didn't fit - no time slot"
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlannerScreen(settings: Settings, onClose: () -> Unit, onMessage: (String) -> Unit, onOpenSettings: () -> Unit, vm: PlannerViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var pickDate by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var editTime by remember { mutableStateOf<Int?>(null) }
    val voice = rememberVoiceInput(onResult = { t -> vm.update { it.copy(input = (it.input + "\n" + t).trim()) } },
        onUnavailable = { onMessage("Voice input isn't available on this device") })

    LaunchedEffect(s.saved) { s.saved?.let { onMessage("Saved $it task${if (it == 1) "" else "s"} to your schedule"); vm.consumeSaved(); onClose() } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Plan my day") },
            )
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.draft == null) {
                OutlinedTextField(
                    value = s.input, onValueChange = { v -> vm.update { it.copy(input = v, error = null) } },
                    label = { Text("Goal, rough task list, or your availability") },
                    placeholder = { Text("I have two hours after work. Help me revise for an exam and plan tomorrow's errands.") },
                    minLines = 4, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    trailingIcon = { IconButton(onClick = voice) { Icon(Icons.Filled.Mic, "Speak") } },
                )
                Text("Available time", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { pickDate = true }, label = { Text(formatDate(s.date)) })
                    AssistChip(onClick = { pickStart = true }, label = { Text("From " + formatTime(s.start)) })
                    AssistChip(onClick = { pickEnd = true }, label = { Text("To " + formatTime(s.end)) })
                }
                Text("Phrases like “two hours after work” fill these in automatically.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Also plan my undated open tasks", s.includeUndated) { v -> vm.update { it.copy(includeUndated = v) } }

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = s.mode == PlannerMode.OFFLINE, onClick = { vm.update { it.copy(mode = PlannerMode.OFFLINE) } },
                        shape = SegmentedButtonDefaults.itemShape(0, 2), icon = { Icon(Icons.Filled.CloudOff, null, Modifier.size(18.dp)) }) { Text("Offline planner") }
                    SegmentedButton(selected = s.mode == PlannerMode.AI, onClick = { vm.update { it.copy(mode = PlannerMode.AI) } },
                        shape = SegmentedButtonDefaults.itemShape(1, 2), icon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) }) { Text("AI planner") }
                }
                if (s.mode == PlannerMode.AI) {
                    if (!settings.aiEnabled || settings.aiBackendUrl.isBlank()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("The AI planner is off. Turn it on and set a backend URL in Settings → AI planner. The offline planner always works.")
                                TextButton(onClick = onOpenSettings) { Text("Open settings") }
                            }
                        }
                    } else {
                        SwitchRow("Share titles of my scheduled tasks (otherwise sent as “Busy”)", s.shareBusyTitles) { v -> vm.update { it.copy(shareBusyTitles = v) } }
                        Text("You'll see exactly what will be sent before anything leaves your phone.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !s.loading && (s.mode == PlannerMode.OFFLINE || (settings.aiEnabled && settings.aiBackendUrl.isNotBlank())),
                        onClick = { if (s.mode == PlannerMode.OFFLINE) vm.planOffline() else vm.prepareAi() },
                    ) { Text("Create draft plan") }
                    if (s.loading) CircularProgressIndicator(Modifier.size(24.dp))
                    if (s.error != null && s.mode == PlannerMode.AI) OutlinedButton(onClick = { vm.planOffline() }) { Text("Use offline planner") }
                }
            } else {
                DraftView(s, onEdit = vm::editItem, onEditTime = { editTime = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.save() }, enabled = s.draft!!.items.any { it.accepted }) {
                        Text("Save ${s.draft!!.items.count { it.accepted }} to schedule")
                    }
                    OutlinedButton(onClick = { vm.discardDraft() }) { Text("Back to input") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickDate) DatePickerModal(s.date, onDismiss = { pickDate = false }) { d -> vm.update { it.copy(date = d) } }
    if (pickStart) TimePickerModal(s.start, onDismiss = { pickStart = false }) { t -> vm.update { it.copy(start = t) } }
    if (pickEnd) TimePickerModal(s.end, onDismiss = { pickEnd = false }) { t -> vm.update { it.copy(end = t) } }
    val editIndex = editTime
    val editItem = editIndex?.let { s.draft?.items?.getOrNull(it) }
    if (editIndex != null && editItem != null) {
        TimePickerModal(editItem.slot?.start?.toLocalTime() ?: s.start, onDismiss = { editTime = null }) { t ->
            val start = (editItem.slot?.start?.toLocalDate() ?: s.date).atTime(t)
            vm.editItem(editIndex) { it.copy(slot = TimeWindow(start, start.plusMinutes(it.task.durationMinutes.toLong()))) }
        }
    }
    s.pendingAiPayload?.let { payload ->
        AlertDialog(
            onDismissRequest = vm::cancelAi,
            title = { Text("Send to AI planner?") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Only this will be sent to ${settings.aiBackendUrl}:")
                    Spacer(Modifier.height(8.dp))
                    Text(payload, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::sendAi) { Text("Send") } },
            dismissButton = { TextButton(onClick = vm::cancelAi) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DraftView(s: PlannerState, onEdit: (Int, (DraftItem) -> DraftItem) -> Unit, onEditTime: (Int) -> Unit) {
    val draft = s.draft ?: return
    // Always make the origin of the plan explicit.
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (draft.source == PlanSource.AI) Icons.Filled.AutoAwesome else Icons.Filled.CloudOff, null)
            Spacer(Modifier.size(8.dp))
            Column {
                Text(
                    "Draft from the " + draft.source.label + (draft.modelName?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(draft.summary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    draft.warnings.forEach { w ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(w, style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("Review each suggestion. Untick anything you don't want; tap a time to change it.", style = MaterialTheme.typography.bodySmall)
    draft.items.forEachIndexed { i, item ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.accepted, onCheckedChange = { v -> onEdit(i) { it.copy(accepted = v) } },
                        modifier = Modifier.semantics { contentDescription = (if (item.accepted) "Keep " else "Skip ") + item.task.title })
                    OutlinedTextField(
                        value = item.task.title, onValueChange = { v -> onEdit(i) { it.copy(task = it.task.copy(title = v)) } },
                        singleLine = true, modifier = Modifier.weight(1f), label = { Text("Task") },
                    )
                }
                Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { onEditTime(i) },
                        label = { Text(item.slot?.let { formatTime(it.start.toLocalTime()) + "–" + formatTime(it.end.toLocalTime()) } ?: "Pick a time") },
                    )
                    OutlinedTextField(
                        value = item.task.durationMinutes.toString(),
                        onValueChange = { v ->
                            val m = v.filter(Char::isDigit).take(3).toIntOrNull() ?: return@OutlinedTextField
                            onEdit(i) { d -> d.copy(task = d.task.copy(durationMinutes = m.coerceIn(5, 720)), slot = d.slot?.let { TimeWindow(it.start, it.start.plusMinutes(m.coerceIn(5, 720).toLong())) }) }
                        },
                        label = { Text("min") }, singleLine = true, modifier = Modifier.size(width = 96.dp, height = 64.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(item.task.priority.name.lowercase().replaceFirstChar(Char::titlecase).takeIf { item.task.priority.weight > 0 }?.let { "$it priority" } ?: "",
                        style = MaterialTheme.typography.labelMedium)
                }
                if (item.task.subtasks.isNotEmpty()) {
                    Column(Modifier.padding(start = 56.dp, top = 4.dp)) { item.task.subtasks.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
                }
                if (item.flags.isNotEmpty()) {
                    FlowRow(Modifier.padding(start = 56.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.flags.forEach { f ->
                            Text(
                                "⚠ " + flagText(f) + if (f == DraftFlag.OVERLAPS_EXISTING || f == DraftFlag.OVERLAPS_DRAFT) item.conflictsWith.joinToString(prefix = ": ") else "",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (item.task.id.startsWith("existing:")) Text("Existing task - saving reschedules it", Modifier.padding(start = 56.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
