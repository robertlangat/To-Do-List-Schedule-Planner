package com.robertlangat.todoplanner.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.robertlangat.todoplanner.MainActivity
import com.robertlangat.todoplanner.container
import com.robertlangat.todoplanner.domain.Priority
import com.robertlangat.todoplanner.domain.TaskOccurrence
import com.robertlangat.todoplanner.domain.sortOccurrences
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class WidgetRow(val taskId: String, val date: LocalDate?, val title: String, val meta: String, val done: Boolean)

/** Today's tasks (plus overdue one-offs) with direct completion and a quick-add button. */
class TodayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(110.dp, 80.dp), DpSize(180.dp, 110.dp), DpSize(250.dp, 200.dp), DpSize(320.dp, 300.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = loadRows(context)
        provideContent { GlanceTheme { Content(rows) } }
    }

    companion object {
        suspend fun loadRows(context: Context): List<WidgetRow> {
            val data = context.container.repository.snapshot()
            val today = LocalDate.now()
            val todays = data.occurrencesBetween(today, today)
            val overdue = data.tasks.filter { it.rrule == null && !it.completed && (it.dueDate ?: Long.MAX_VALUE) < today.toEpochDay() }
                .map { data.occurrence(it, LocalDate.ofEpochDay(it.dueDate!!)) }
            return sortOccurrences(overdue + todays).map { it.toRow(today) }
        }

        private fun TaskOccurrence.toRow(today: LocalDate): WidgetRow {
            val parts = buildList {
                if (date != null && date < today) add("Overdue")
                time?.let { add(it.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
                if (priority != Priority.NONE) add(priority.label + " " + priority.marks)
            }
            return WidgetRow(task.id, date, task.title, parts.joinToString(" · "), completed)
        }
    }
}

val TaskIdKey = ActionParameters.Key<String>("taskId")
val DateKey = ActionParameters.Key<String>("date")
val DoneKey = ActionParameters.Key<Boolean>("done")

@Composable
private fun Content(rows: List<WidgetRow>) {
    val context = LocalContext.current
    val compact = LocalSize.current.height < 100.dp
    val open = rows.count { !it.done }
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp).padding(10.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (open == 0) "Today" else "Today · $open left",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
            )
            Box(
                modifier = GlanceModifier.size(40.dp).cornerRadius(20.dp).background(GlanceTheme.colors.primary)
                    .clickable(
                        actionStartActivity(
                            Intent(context, MainActivity::class.java)
                                .setAction(MainActivity.ACTION_QUICK_ADD)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        ),
                    )
                    .semantics { contentDescription = "Add task" },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onPrimary))
            }
        }
        if (!compact) Spacer(GlanceModifier.height(6.dp))
        if (rows.isEmpty()) {
            Text("Nothing due today 🎉", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant), modifier = GlanceModifier.padding(top = 8.dp))
        } else {
            LazyColumn {
                items(rows, itemId = { (it.taskId + it.date).hashCode().toLong() }) { row ->
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckBox(
                            checked = row.done,
                            onCheckedChange = actionRunCallback<ToggleTaskAction>(
                                actionParametersOf(TaskIdKey to row.taskId, DateKey to (row.date?.toString() ?: ""), DoneKey to !row.done),
                            ),
                            text = "",
                            modifier = GlanceModifier.semantics { contentDescription = (if (row.done) "Reopen " else "Complete ") + row.title },
                        )
                        Spacer(GlanceModifier.width(4.dp))
                        Column(
                            modifier = GlanceModifier.defaultWeight().clickable(
                                actionStartActivity(
                                    Intent(context, MainActivity::class.java)
                                        .setAction(MainActivity.ACTION_OPEN_TASK)
                                        .putExtra(MainActivity.EXTRA_TASK_ID, row.taskId)
                                        .putExtra(MainActivity.EXTRA_DATE, row.date?.toString())
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                ),
                            ),
                        ) {
                            Text(
                                row.title, maxLines = 2,
                                style = TextStyle(
                                    color = if (row.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                                    fontSize = 14.sp,
                                ),
                            )
                            if (row.meta.isNotEmpty() && !compact) {
                                Text(row.meta, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[TaskIdKey] ?: return
        val date = parameters[DateKey]?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
        val done = parameters[DoneKey] ?: true
        // The repository refreshes all widgets, Wear and reminders after the write.
        context.container.repository.setCompleted(taskId, date, done)
        TodayWidget().update(context, glanceId)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
