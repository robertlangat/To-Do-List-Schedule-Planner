package com.robertlangat.todoplanner.ui.tasks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.robertlangat.todoplanner.data.db.ListEntity
import com.robertlangat.todoplanner.domain.EditScope
import com.robertlangat.todoplanner.domain.TaskOccurrence
import com.robertlangat.todoplanner.ui.MainViewModel
import com.robertlangat.todoplanner.ui.components.DatePickerModal
import com.robertlangat.todoplanner.ui.components.ListColors
import com.robertlangat.todoplanner.ui.components.ListDot
import com.robertlangat.todoplanner.ui.components.ListIcons
import com.robertlangat.todoplanner.ui.components.RowActions
import com.robertlangat.todoplanner.ui.components.formatDate
import java.time.LocalDate
import java.time.ZoneId

/** Holds which task-action dialog is open. Shared by the Tasks and Calendar screens. */
class TaskActionState {
    var moveTarget by mutableStateOf<TaskOccurrence?>(null)
    var rescheduleTarget by mutableStateOf<TaskOccurrence?>(null)
    var deleteTarget by mutableStateOf<TaskOccurrence?>(null)
}

@Composable
fun rememberRowActions(vm: MainViewModel, state: TaskActionState, onOpen: (TaskOccurrence) -> Unit): RowActions {
    val context = LocalContext.current
    return remember(vm, state) {
        RowActions(
            onToggle = { vm.toggle(it) },
            onOpen = onOpen,
            onStar = { vm.star(it) },
            onDuplicate = { vm.duplicate(it) },
            onMove = { state.moveTarget = it },
            onReschedule = { state.rescheduleTarget = it },
            onDelete = { if (it.isRecurring) state.deleteTarget = it else vm.delete(it, EditScope.ALL) },
            onExportToCalendar = { exportToDeviceCalendar(context, it) { msg -> vm.message(msg) } },
        )
    }
}

/** Uses the calendar app's insert screen, so no calendar permission is needed. */
fun exportToDeviceCalendar(context: Context, item: TaskOccurrence, onError: (String) -> Unit) {
    val date = item.date ?: return
    val zone = ZoneId.systemDefault()
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, item.task.title)
        .putExtra(CalendarContract.Events.DESCRIPTION, item.task.notes)
    val time = item.time
    if (time == null) {
        val start = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 86_400_000L)
    } else {
        val start = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + (item.task.estimatedMinutes ?: 30) * 60_000L)
    }
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { onError("No calendar app found on this device") }
}

@Composable
fun TaskActionDialogs(state: TaskActionState, vm: MainViewModel, lists: List<ListEntity>) {
    state.moveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { state.moveTarget = null },
            title = { Text("Move to list") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("Inbox") },
                        leadingContent = { Icon(Icons.Filled.Inbox, null) },
                        modifier = Modifier.clickable { vm.move(target, null); state.moveTarget = null },
                    )
                    lists.forEach { l ->
                        ListItem(
                            headlineContent = { Text(l.name) },
                            leadingContent = { Icon(ListIcons.of(l.icon), null, tint = l.color?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingContent = { if (target.task.listId == l.id) Icon(Icons.Filled.Check, "Current list") },
                            modifier = Modifier.clickable { vm.move(target, l.id); state.moveTarget = null },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { state.moveTarget = null }) { Text("Cancel") } },
        )
    }
    state.rescheduleTarget?.let { target ->
        var picking by remember { mutableStateOf(false) }
        if (picking) {
            DatePickerModal(target.date, onDismiss = { picking = false; state.rescheduleTarget = null }) { vm.reschedule(target, it) }
        } else {
            val today = LocalDate.now()
            AlertDialog(
                onDismissRequest = { state.rescheduleTarget = null },
                title = { Text("Reschedule") },
                text = {
                    Column {
                        if (target.isRecurring) Text("Only this occurrence moves; the rest of the series stays.", style = MaterialTheme.typography.bodySmall)
                        listOf("Today" to today, "Tomorrow" to today.plusDays(1), "Next week" to today.plusWeeks(1)).forEach { (label, d) ->
                            ListItem(headlineContent = { Text(label) }, supportingContent = { Text(formatDate(d, today)) },
                                modifier = Modifier.clickable { vm.reschedule(target, d); state.rescheduleTarget = null })
                        }
                        ListItem(headlineContent = { Text("Pick a date…") }, modifier = Modifier.clickable { picking = true })
                        if (!target.isRecurring && target.date != null) {
                            ListItem(headlineContent = { Text("Remove date") },
                                modifier = Modifier.clickable { vm.reschedule(target, null); state.rescheduleTarget = null })
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { state.rescheduleTarget = null }) { Text("Cancel") } },
            )
        }
    }
    state.deleteTarget?.let { target ->
        RecurringScopeDialog(
            title = "Delete repeating task",
            confirmLabel = "Delete",
            includeAll = true,
            onDismiss = { state.deleteTarget = null },
            onConfirm = { scope -> vm.delete(target, scope); state.deleteTarget = null },
        )
    }
}

@Composable
fun RecurringScopeDialog(title: String, confirmLabel: String, includeAll: Boolean, onDismiss: () -> Unit, onConfirm: (EditScope) -> Unit) {
    var scope by rememberSaveable { mutableStateOf(EditScope.THIS_OCCURRENCE) }
    val options = buildList {
        add(EditScope.THIS_OCCURRENCE to "This occurrence")
        add(EditScope.THIS_AND_FUTURE to "This and future occurrences")
        if (includeAll) add(EditScope.ALL to "All occurrences")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { scope = value }.padding(vertical = 8.dp).semantics { selected = scope == value },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = scope == value, onClick = { scope = value })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(scope) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Step 2 of "New List": name (required), optional color and icon. Also used for editing. */
@Composable
fun ListEditorDialog(
    initial: ListEntity? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, color: Long?, icon: String?) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var color by rememberSaveable { mutableStateOf(initial?.color) }
    var icon by rememberSaveable { mutableStateOf(initial?.icon) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New list" else "Edit list") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) }, label = { Text("List name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name, color, icon) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(12.dp))
                Text("Color (optional)", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ListColors.forEach { c ->
                        val selected = color == c
                        Box(
                            Modifier.size(40.dp).clickable { color = if (selected) null else c }
                                .semantics { contentDescription = "Color option"; this.selected = selected },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(if (selected) 34.dp else 28.dp).background(Color(c), CircleShape)
                                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                            )
                            if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Text("Icon (optional)", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    ListIcons.all.forEach { (key, vector) ->
                        val selected = icon == key
                        IconButton(onClick = { icon = if (selected) null else key }, modifier = Modifier.semantics { this.selected = selected }) {
                            Icon(vector, "$key icon", tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete list", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, color, icon) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Launches the system speech recognizer (no RECORD_AUDIO permission needed by this app). */
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit, onUnavailable: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onResult)
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a task")
        try { launcher.launch(intent) } catch (_: ActivityNotFoundException) { onUnavailable() }
    }
}

/** Title-only quick capture. Date and list are optional chips; "More" opens the full editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    initialDate: LocalDate?,
    initialListId: String?,
    lists: List<ListEntity>,
    onDismiss: () -> Unit,
    onAdd: (title: String, date: LocalDate?, listId: String?) -> Unit,
    onMore: (title: String, date: LocalDate?, listId: String?) -> Unit,
    onVoiceUnavailable: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var date by remember { mutableStateOf(initialDate) }
    var listId by remember { mutableStateOf(initialListId) }
    var pickDate by remember { mutableStateOf(false) }
    var pickList by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val voice = rememberVoiceInput(onResult = { title = if (title.isBlank()) it else "$title $it" }, onUnavailable = onVoiceUnavailable)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() { if (title.isNotBlank()) { onAdd(title, date, listId); title = "" } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, placeholder = { Text("What do you need to do?") },
                    modifier = Modifier.weight(1f).focusRequester(focus), singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                IconButton(onClick = voice) { Icon(Icons.Filled.Mic, "Speak a task") }
                IconButton(onClick = { submit() }, enabled = title.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Add task") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = LocalDate.now()
                FilterChip(selected = date == today, onClick = { date = if (date == today) null else today }, label = { Text("Today") })
                FilterChip(selected = date == today.plusDays(1), onClick = { date = if (date == today.plusDays(1)) null else today.plusDays(1) }, label = { Text("Tomorrow") })
                AssistChip(
                    onClick = { pickDate = true },
                    label = { Text(date?.takeIf { it != today && it != today.plusDays(1) }?.let { formatDate(it, today) } ?: "Date") },
                    leadingIcon = { Icon(Icons.Filled.CalendarMonth, null) },
                )
                AssistChip(
                    onClick = { pickList = true },
                    label = { Text(listId?.let { id -> lists.firstOrNull { it.id == id }?.name } ?: "Inbox") },
                    leadingIcon = { lists.firstOrNull { it.id == listId }?.let { ListDot(it.color) } ?: Icon(Icons.Filled.Inbox, null) },
                )
                AssistChip(onClick = { onMore(title, date, listId) }, label = { Text("More options") }, leadingIcon = { Icon(Icons.Filled.Tune, null) })
            }
        }
    }
    if (pickDate) DatePickerModal(date, onDismiss = { pickDate = false }) { date = it }
    if (pickList) {
        AlertDialog(
            onDismissRequest = { pickList = false },
            title = { Text("List") },
            text = {
                Column {
                    ListItem(headlineContent = { Text("Inbox") }, modifier = Modifier.clickable { listId = null; pickList = false })
                    lists.forEach { l ->
                        ListItem(headlineContent = { Text(l.name) }, leadingContent = { ListDot(l.color) },
                            modifier = Modifier.clickable { listId = l.id; pickList = false })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickList = false }) { Text("Cancel") } },
        )
    }
}
