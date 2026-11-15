package com.robertlangat.todoplanner.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robertlangat.todoplanner.domain.Priority
import com.robertlangat.todoplanner.domain.TaskOccurrence
import com.robertlangat.todoplanner.ui.theme.priorityColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object ListIcons {
    val all: List<Pair<String, ImageVector>> = listOf(
        "personal" to Icons.Filled.Person, "work" to Icons.Filled.Work, "study" to Icons.Filled.School,
        "fitness" to Icons.Filled.FitnessCenter, "travel" to Icons.Filled.Flight, "wishlist" to Icons.Filled.CardGiftcard,
        "family" to Icons.Filled.Cake, "home" to Icons.Filled.Home, "shopping" to Icons.Filled.ShoppingCart,
    )
    fun of(key: String?): ImageVector = all.firstOrNull { it.first == key }?.second ?: Icons.AutoMirrored.Filled.List
}

val ListColors = listOf(
    0xFF5C6BC0, 0xFF00897B, 0xFF8E24AA, 0xFFE53935, 0xFF039BE5, 0xFFF4511E, 0xFFD81B60, 0xFF7CB342, 0xFF6D4C41,
)


/** Priority marker: colored flag + "!!!" text so it reads without color, with a spoken label. */
@Composable
fun PriorityBadge(priority: Priority, modifier: Modifier = Modifier) {
    if (priority == Priority.NONE) return
    val color = priorityColor(priority.value)
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp)
            .semantics { contentDescription = "${priority.label} priority" },
    ) {
        Text(priority.marks, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ListDot(color: Long?, size: Int = 10) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(color?.let { Color(it) } ?: MaterialTheme.colorScheme.outline),
    )
}

data class RowActions(
    val onToggle: (TaskOccurrence) -> Unit,
    val onOpen: (TaskOccurrence) -> Unit,
    val onStar: (TaskOccurrence) -> Unit,
    val onDuplicate: (TaskOccurrence) -> Unit,
    val onMove: (TaskOccurrence) -> Unit,
    val onReschedule: (TaskOccurrence) -> Unit,
    val onDelete: (TaskOccurrence) -> Unit,
    val onExportToCalendar: (TaskOccurrence) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(item: TaskOccurrence, actions: RowActions, today: LocalDate, showDate: Boolean = true, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = { actions.onOpen(item) }, onLongClick = { menu = true }, onClickLabel = "Open task")
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = item.completed,
            onCheckedChange = { actions.onToggle(item) },
            modifier = Modifier.semantics {
                contentDescription = if (item.completed) "Reopen ${item.task.title}" else "Complete ${item.task.title}"
                stateDescription = if (item.completed) "Completed" else "Not completed"
            },
        )
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityBadge(item.priority)
                if (item.priority != Priority.NONE) Spacer(Modifier.width(6.dp))
                Text(
                    item.task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (item.completed) TextDecoration.LineThrough else null,
                    color = if (item.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            val meta = buildList {
                if (showDate) item.date?.let { add(formatDate(it, today)) }
                item.time?.let { add(formatTime(it)) } ?: run { if (item.date != null && showDate) add("All day") }
                if (item.subtasksTotal > 0) add("${item.subtasksDone}/${item.subtasksTotal} subtasks")
                item.list?.let { add(it.name) }
            }
            if (meta.isNotEmpty() || item.isRecurring || item.task.reminderOffsets.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.list?.let { ListDot(it.color, 8) }
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isOverdue(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (item.isOverdue(today)) Text("Overdue", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    if (item.isRecurring) Icon(Icons.Filled.Repeat, "Repeats", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.task.reminderOffsets.isNotEmpty()) Icon(Icons.Outlined.Notifications, "Has reminder", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        IconButton(onClick = { actions.onStar(item) }) {
            Icon(
                if (item.task.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (item.task.starred) "Unstar" else "Star",
                tint = if (item.task.starred) Color(0xFFF9A825) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More actions for ${item.task.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Reschedule") }, onClick = { menu = false; actions.onReschedule(item) })
                DropdownMenuItem(text = { Text("Move to list") }, onClick = { menu = false; actions.onMove(item) })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; actions.onDuplicate(item) })
                if (item.date != null) DropdownMenuItem(text = { Text("Add to device calendar") }, onClick = { menu = false; actions.onExportToCalendar(item) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; actions.onDelete(item) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerModal(initial: LocalDate?, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerModal(initial: LocalTime?, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val t = initial ?: LocalTime.now().plusHours(1).withMinute(0)
    val state = rememberTimePickerState(t.hour, t.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}
