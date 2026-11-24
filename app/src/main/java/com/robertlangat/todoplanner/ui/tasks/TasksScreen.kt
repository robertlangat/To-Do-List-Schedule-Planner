package com.robertlangat.todoplanner.ui.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertlangat.todoplanner.data.StarterLists
import com.robertlangat.todoplanner.domain.TaskOccurrence
import com.robertlangat.todoplanner.ui.MainViewModel
import com.robertlangat.todoplanner.ui.components.ListDot
import com.robertlangat.todoplanner.ui.components.PriorityBadge
import com.robertlangat.todoplanner.ui.components.TaskRow
import com.robertlangat.todoplanner.ui.components.formatDate
import com.robertlangat.todoplanner.ui.components.formatTime
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    vm: MainViewModel,
    contentPadding: PaddingValues,
    onOpenTask: (TaskOccurrence) -> Unit,
    onOpenPlanner: () -> Unit,
    /** Reports the list being viewed so the add button can prefill it. */
    onCurrentListChanged: (String?) -> Unit,
) {
    val data by vm.data.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()

    var view by remember { mutableStateOf<TaskView>(TaskView.Today) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filters by remember { mutableStateOf(Filters()) }
    var showFilters by remember { mutableStateOf(false) }
    var focusMode by rememberSaveable(settings.focusModeDefault) {
        mutableStateOf(settings.focusModeDefault || com.robertlangat.todoplanner.ScreenshotMode.scene == "focus")
    }
    var newList by remember { mutableStateOf(false) }
    var editList by remember { mutableStateOf<String?>(null) }
    val actionState = remember { TaskActionState() }
    val actions = rememberRowActions(vm, actionState, onOpenTask)

    // Keep the view valid if its list is deleted (possibly from another device).
    (view as? TaskView.InList)?.let { v -> if (data.listsById[v.listId] == null) view = TaskView.Today }
    androidx.compose.runtime.LaunchedEffect(view) { onCurrentListChanged((view as? TaskView.InList)?.listId) }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            if (searching) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                    title = {
                        TextField(
                            value = query, onValueChange = { query = it }, placeholder = { Text("Search tasks, notes, subtasks") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        )
                    },
                    actions = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") } },
                )
            } else {
                TopAppBar(
                    title = { Text(if (focusMode) "Focus" else view.title) },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                        IconButton(onClick = { showFilters = true }) {
                            BadgedBox(badge = { if (filters.active) Badge() }) { Icon(Icons.Filled.FilterList, if (filters.active) "Filters (active)" else "Filters") }
                        }
                        IconButton(onClick = onOpenPlanner) { Icon(Icons.Filled.AutoAwesome, "Plan my day") }
                        IconButton(onClick = { focusMode = !focusMode }) {
                            Icon(if (focusMode) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.CenterFocusStrong, if (focusMode) "Show all tasks" else "Focus view")
                        }
                    },
                )
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (!settings.starterListsOffered && data.lists.isEmpty()) {
                StarterListsCard(onAdd = { vm.addStarterLists(it) }, onSkip = { vm.skipStarterLists() })
            }
            if (focusMode && !searching) {
                FocusView(TaskViews.focus(data, today), today, onToggle = vm::toggle, onOpen = onOpenTask,
                    onTomorrow = { vm.reschedule(it, today.plusDays(1)) }, onReschedule = { actionState.rescheduleTarget = it })
            } else {
                if (!searching) {
                    ViewChips(
                        current = view, lists = data.lists,
                        onSelect = { view = it },
                        onNewList = { newList = true },
                        onEditList = { editList = it },
                    )
                }
                val sections = TaskViews.build(view, data, today, query, filters)
                if (sections.all { it.items.isEmpty() }) {
                    EmptyState(view, query, filters.active, Modifier.weight(1f))
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                        sections.forEach { s ->
                            if (s.title != null && s.items.isNotEmpty()) item(key = "h-${s.title}") {
                                Text(
                                    s.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                                )
                            }
                            items(s.items, key = { "${s.title}-${it.key}" }) { item ->
                                TaskRow(item, actions, today, showDate = view != TaskView.Today || searching || item.isOverdue(today))
                                HorizontalDivider(Modifier.padding(start = 56.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) FiltersSheet(filters, data.lists, onChange = { filters = it }, onDismiss = { showFilters = false })
    if (newList) {
        ListEditorDialog(onDismiss = { newList = false }, onSave = { name, color, icon ->
            vm.createList(name, color, icon) { id -> view = TaskView.InList(id, name.trim()) }
            newList = false
        })
    }
    val editingId = editList
    val editingList = editingId?.let { data.listsById[it] }
    if (editingId != null && editingList != null) {
        ListEditorDialog(
            initial = editingList, onDismiss = { editList = null },
            onSave = { name, color, icon -> vm.updateList(editingId, name, color, icon); view = TaskView.InList(editingId, name.trim()); editList = null },
            onDelete = { vm.deleteList(editingId); view = TaskView.Inbox; editList = null },
        )
    }
    TaskActionDialogs(actionState, vm, data.lists)
}

@Composable
private fun ViewChips(
    current: TaskView,
    lists: List<com.robertlangat.todoplanner.data.db.ListEntity>,
    onSelect: (TaskView) -> Unit,
    onNewList: () -> Unit,
    onEditList: (String) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(TaskView.Inbox, TaskView.Today, TaskView.Upcoming, TaskView.Starred, TaskView.Completed).forEach { v ->
            FilterChip(selected = current == v, onClick = { onSelect(v) }, label = { Text(v.title) })
        }
        lists.forEach { l ->
            val selected = (current as? TaskView.InList)?.listId == l.id
            FilterChip(
                selected = selected,
                onClick = { if (selected) onEditList(l.id) else onSelect(TaskView.InList(l.id, l.name)) },
                label = { Text(l.name) },
                leadingIcon = { ListDot(l.color) },
                trailingIcon = if (selected) ({ Icon(Icons.Filled.Edit, "Edit list", Modifier.size(16.dp)) }) else null,
            )
        }
        InputChip(selected = false, onClick = onNewList, label = { Text("New List") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
    }
}

@Composable
private fun EmptyState(view: TaskView, query: String, filtered: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            when {
                query.isNotBlank() -> "No tasks match “$query”."
                filtered -> "No tasks match these filters."
                view == TaskView.Today -> "Nothing due today. Tap + to add a task."
                view == TaskView.Completed -> "Completed tasks will appear here."
                view == TaskView.Starred -> "Star important tasks to see them here."
                else -> "No tasks yet. Tap + to add one."
            },
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StarterListsCard(onAdd: (Set<String>) -> Unit, onSkip: () -> Unit) {
    var selected by remember { mutableStateOf(StarterLists.all.map { it.key }.toSet()) }
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Start with some lists?", style = MaterialTheme.typography.titleMedium)
            Text("Pick any you'd like. You can add, rename or delete lists any time.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StarterLists.all.forEach { s ->
                    FilterChip(
                        selected = s.key in selected,
                        onClick = { selected = if (s.key in selected) selected - s.key else selected + s.key },
                        label = { Text(s.name) }, leadingIcon = { ListDot(s.color) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAdd(selected) }, enabled = selected.isNotEmpty()) { Text("Add ${selected.size} lists") }
                TextButton(onClick = onSkip) { Text("No thanks") }
            }
        }
    }
}

/** A calmer view: three priority tasks, large checkboxes, one-tap rescheduling. */
@Composable
private fun FocusView(
    items: List<TaskOccurrence>, today: LocalDate,
    onToggle: (TaskOccurrence) -> Unit, onOpen: (TaskOccurrence) -> Unit,
    onTomorrow: (TaskOccurrence) -> Unit, onReschedule: (TaskOccurrence) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your top ${items.size.coerceAtLeast(1)} for today", style = MaterialTheme.typography.titleMedium)
        if (items.isEmpty()) {
            Text("You're all caught up. Add a task or switch back to the full list.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items.forEach { item ->
            Card(onClick = { onOpen(item) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = item.completed, onCheckedChange = { onToggle(item) },
                        modifier = Modifier.size(56.dp).semantics {
                            contentDescription = "Complete ${item.task.title}"
                            stateDescription = if (item.completed) "Completed" else "Not completed"
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PriorityBadge(item.priority)
                            Spacer(Modifier.size(6.dp))
                            Text(
                                item.task.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                                textDecoration = if (item.completed) TextDecoration.LineThrough else null,
                            )
                        }
                        val meta = listOfNotNull(item.date?.let { formatDate(it, today) }, item.time?.let(::formatTime)).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, color = if (item.isOverdue(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { onTomorrow(item) }) { Text("Tomorrow") }
                            TextButton(onClick = { onReschedule(item) }) { Text("Reschedule…") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersSheet(
    filters: Filters, lists: List<com.robertlangat.todoplanner.data.db.ListEntity>,
    onChange: (Filters) -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)
            Text("Status", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusFilter.entries.forEach { s -> FilterChip(selected = filters.status == s, onClick = { onChange(filters.copy(status = s)) }, label = { Text(s.label) }) }
            }
            Text("Date", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateFilter.entries.forEach { d -> FilterChip(selected = filters.date == d, onClick = { onChange(filters.copy(date = d)) }, label = { Text(d.label) }) }
            }
            Text("Priority", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3 to "High !!!", 2 to "Medium !!", 1 to "Low !", 0 to "None").forEach { (p, label) ->
                    FilterChip(
                        selected = p in filters.priorities,
                        onClick = { onChange(filters.copy(priorities = if (p in filters.priorities) filters.priorities - p else filters.priorities + p)) },
                        label = { Text(label) },
                    )
                }
            }
            Text("List", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filters.listId == null, onClick = { onChange(filters.copy(listId = null)) }, label = { Text("Any") })
                FilterChip(selected = filters.listId == "", onClick = { onChange(filters.copy(listId = "")) }, label = { Text("Inbox") })
                lists.forEach { l ->
                    FilterChip(selected = filters.listId == l.id, onClick = { onChange(filters.copy(listId = l.id)) }, label = { Text(l.name) }, leadingIcon = { ListDot(l.color) })
                }
            }
            TextButton(onClick = { onChange(Filters()) }, enabled = filters.active) { Text("Clear filters") }
        }
    }
}
