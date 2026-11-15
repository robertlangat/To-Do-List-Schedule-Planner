package com.robertlangat.todoplanner.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertlangat.todoplanner.domain.TaskOccurrence
import com.robertlangat.todoplanner.domain.sortOccurrences
import com.robertlangat.todoplanner.ui.MainViewModel
import com.robertlangat.todoplanner.ui.components.TaskRow
import com.robertlangat.todoplanner.ui.components.formatDate
import com.robertlangat.todoplanner.ui.tasks.TaskActionDialogs
import com.robertlangat.todoplanner.ui.tasks.TaskActionState
import com.robertlangat.todoplanner.ui.tasks.rememberRowActions
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class CalendarMode(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    vm: MainViewModel,
    contentPadding: PaddingValues,
    onOpenTask: (TaskOccurrence) -> Unit,
    onSelectedDateChanged: (LocalDate) -> Unit,
) {
    val data by vm.data.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(CalendarMode.MONTH) }
    var selectedEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    fun select(d: LocalDate) { selectedEpoch = d.toEpochDay() }
    LaunchedEffect(selected) { onSelectedDateChanged(selected) }

    val actionState = remember { TaskActionState() }
    val actions = rememberRowActions(vm, actionState, onOpenTask)

    val (rangeStart, rangeEnd) = when (mode) {
        CalendarMode.DAY -> selected to selected
        CalendarMode.WEEK -> weekStart(selected).let { it to it.plusDays(6) }
        CalendarMode.MONTH -> YearMonth.from(selected).let { weekStart(it.atDay(1)) to weekStart(it.atDay(1)).plusDays(41) }
    }
    val byDate = remember(data, rangeStart, rangeEnd) { data.occurrencesBetween(rangeStart, rangeEnd).groupBy { it.date!! } }
    val dayItems = sortOccurrences(byDate[selected].orEmpty())

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (mode) {
                            CalendarMode.MONTH -> selected.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                            CalendarMode.WEEK -> "Week of " + weekStart(selected).format(DateTimeFormatter.ofPattern("d MMM"))
                            CalendarMode.DAY -> selected.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                        },
                    )
                },
                actions = {
                    IconButton(onClick = {
                        select(when (mode) { CalendarMode.DAY -> selected.minusDays(1); CalendarMode.WEEK -> selected.minusWeeks(1); CalendarMode.MONTH -> selected.minusMonths(1) })
                    }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous ${mode.label.lowercase()}") }
                    IconButton(onClick = { select(today) }) { Icon(Icons.Filled.Today, "Go to today") }
                    IconButton(onClick = {
                        select(when (mode) { CalendarMode.DAY -> selected.plusDays(1); CalendarMode.WEEK -> selected.plusWeeks(1); CalendarMode.MONTH -> selected.plusMonths(1) })
                    }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next ${mode.label.lowercase()}") }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                CalendarMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(m.label) }
                }
            }
            when (mode) {
                CalendarMode.MONTH -> MonthGrid(selected, today, byDate, ::select)
                CalendarMode.WEEK -> WeekStrip(selected, today, byDate, ::select)
                CalendarMode.DAY -> Unit
            }
            HorizontalDivider()
            if (mode == CalendarMode.DAY) {
                DayTimeline(dayItems, today, actions)
            } else {
                Text(
                    formatDate(selected, today) + " · ${dayItems.size} task${if (dayItems.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp).semantics { heading() },
                )
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (dayItems.isEmpty()) item { Text("No tasks. Tap + to add one on this day.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(dayItems, key = { it.key }) { TaskRow(it, actions, today, showDate = false) }
                }
            }
        }
    }
    TaskActionDialogs(actionState, vm, data.lists)
}

private fun weekStart(d: LocalDate) = d.minusDays((d.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

private val dayHeaders = DayOfWeek.entries.map { it.name.take(1) }

@Composable
private fun MonthGrid(selected: LocalDate, today: LocalDate, byDate: Map<LocalDate, List<TaskOccurrence>>, onSelect: (LocalDate) -> Unit) {
    val month = YearMonth.from(selected)
    val first = weekStart(month.atDay(1))
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row { dayHeaders.forEach { Text(it, Modifier.weight(1f).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        for (week in 0 until 6) {
            Row {
                for (dow in 0 until 7) {
                    val date = first.plusDays((week * 7 + dow).toLong())
                    val items = byDate[date].orEmpty()
                    DayCell(date, items, inMonth = date.month == month.month, isToday = date == today, isSelected = date == selected,
                        modifier = Modifier.weight(1f).aspectRatio(1f), onClick = { onSelect(date) })
                }
            }
        }
    }
}

@Composable
private fun WeekStrip(selected: LocalDate, today: LocalDate, byDate: Map<LocalDate, List<TaskOccurrence>>, onSelect: (LocalDate) -> Unit) {
    val start = weekStart(selected)
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row { dayHeaders.forEach { Text(it, Modifier.weight(1f).padding(4.dp), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        Row {
            for (i in 0 until 7) {
                val date = start.plusDays(i.toLong())
                DayCell(date, byDate[date].orEmpty(), true, date == today, date == selected, Modifier.weight(1f).heightIn(min = 64.dp)) { onSelect(date) }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate, items: List<TaskOccurrence>, inMonth: Boolean, isToday: Boolean, isSelected: Boolean,
    modifier: Modifier, onClick: () -> Unit,
) {
    val open = items.count { !it.completed }
    val label = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) +
        if (items.isEmpty()) ", no tasks" else ", ${items.size} task${if (items.size == 1) "" else "s"}, $open open"
    Box(
        modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; selected = isSelected },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                date.dayOfMonth.toString(),
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    inMonth -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.outline
                },
            )
            if (items.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    items.take(3).forEach { o ->
                        Box(Modifier.size(5.dp).clip(CircleShape).background(
                            if (o.completed) MaterialTheme.colorScheme.outline else o.list?.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
                        ))
                    }
                    if (items.size > 3) Text("+${items.size - 3}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun DayTimeline(items: List<TaskOccurrence>, today: LocalDate, actions: com.robertlangat.todoplanner.ui.components.RowActions) {
    val allDay = items.filter { it.time == null }
    val byHour = items.filter { it.time != null }.groupBy { it.time!!.hour }
    val firstHour = minOf(7, byHour.keys.minOrNull() ?: 7)
    val lastHour = maxOf(21, byHour.keys.maxOrNull() ?: 21)
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Text("All day", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp).semantics { heading() })
        }
        if (allDay.isEmpty()) item { Text("No all-day tasks", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(allDay, key = { "ad-" + it.key }) { TaskRow(it, actions, today, showDate = false) }
        item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
        for (h in firstHour..lastHour) {
            item(key = "h$h") {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        java.time.LocalTime.of(h, 0).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                        Modifier.width(64.dp).padding(top = 14.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        byHour[h].orEmpty().forEach { TaskRow(it, actions, today, showDate = false) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
