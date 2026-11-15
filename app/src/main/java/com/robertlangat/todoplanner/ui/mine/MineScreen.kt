package com.robertlangat.todoplanner.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertlangat.todoplanner.core.progress.ProgressCalculator
import com.robertlangat.todoplanner.core.progress.ProgressTask
import com.robertlangat.todoplanner.core.progress.Ratio
import com.robertlangat.todoplanner.domain.dueLocalDate
import com.robertlangat.todoplanner.domain.rule
import com.robertlangat.todoplanner.ui.MainViewModel
import com.robertlangat.todoplanner.ui.components.ListDot
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(vm: MainViewModel, contentPadding: PaddingValues, onOpenSettings: () -> Unit, onOpenPlanner: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val stats = remember(data, today) {
        ProgressCalculator.compute(
            data.tasks.map { t ->
                ProgressTask(t.id, t.listId, t.dueLocalDate, t.rule, t.completed, data.completedDates(t.id))
            },
            today,
        )
    }

    Scaffold(modifier = Modifier.padding(contentPadding),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Mine") }) }) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Today", stats.today, Modifier.weight(1f))
                StatCard("This week", stats.thisWeek, Modifier.weight(1f))
            }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Overdue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        stats.overdue.toString(), style = MaterialTheme.typography.headlineMedium,
                        color = if (stats.overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Text("Recurring tasks count each occurrence separately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Progress by list", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val rows = listOf<Pair<String?, String>>(null to "Inbox") + data.lists.map { it.id to it.name }
                    rows.forEach { (id, name) ->
                        val r = stats.byList[id] ?: Ratio(0, 0)
                        if (r.total == 0 && id == null) return@forEach
                        Column(Modifier.clearAndSetSemantics { contentDescription = "$name: ${r.done} of ${r.total} done" }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                id?.let { data.listsById[it] }?.let { ListDot(it.color) } ?: ListDot(null)
                                Spacer(Modifier.width(8.dp))
                                Text(name, Modifier.weight(1f))
                                Text("${r.done}/${r.total}", style = MaterialTheme.typography.labelLarge)
                            }
                            LinearProgressIndicator(progress = { r.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }

            ClickableRow("Plan my day", "Turn goals or a rough list into a schedule", Icons.Filled.AutoAwesome, onOpenPlanner)
            ClickableRow(
                "Google Drive sync",
                when {
                    !settings.driveSignedIn -> "Off · tasks are stored on this device"
                    settings.lastSyncAt > 0 -> "Last synced " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(settings.lastSyncAt)) +
                        (settings.lastSyncStatus?.takeIf { it.startsWith("Last attempt") || it.contains("sign in", true) }?.let { "\n$it" } ?: "")
                    else -> settings.lastSyncStatus ?: "Not synced yet"
                },
                Icons.Filled.CloudSync, onOpenSettings,
            )
            ClickableRow("Settings", "Theme, reminders, backup, AI planner, lists", Icons.Filled.Settings, onOpenSettings)
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun ClickableRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, null) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        )
    }
}

@Composable
private fun StatCard(title: String, r: Ratio, modifier: Modifier) {
    Card(modifier.clearAndSetSemantics { contentDescription = "$title: ${r.done} of ${r.total} completed" }) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text("${r.done} / ${r.total}", style = MaterialTheme.typography.headlineMedium)
            Text("completed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { r.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}
