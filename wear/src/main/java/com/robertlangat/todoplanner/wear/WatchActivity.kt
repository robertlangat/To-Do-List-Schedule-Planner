package com.robertlangat.todoplanner.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.robertlangat.todoplanner.core.wear.WearTask
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchStore.loadCache(this)
        setContent { MaterialTheme { WatchApp() } }
    }
}

private fun priorityMarks(p: Int) = when (p) { 3 -> "!!! "; 2 -> "!! "; 1 -> "! "; else -> "" }

@Composable
fun WatchApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by WatchStore.state.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    LaunchedEffect(Unit) { WatchStore.refreshFromDataLayer(context) }

    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Today") } }
            val payload = state.payload
            if (payload == null) {
                item { Text("Open the phone app to load tasks", textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp)) }
            } else {
                val today = payload.today
                if (today.isEmpty()) item { Text("Nothing due today", textAlign = TextAlign.Center) }
                items(today, key = { "t-" + WatchState.key(it) }) { t -> TaskToggle(t, state.isDone(t)) { scope.launch { WatchStore.complete(context, t) } } }
                if (payload.upcoming.isNotEmpty()) {
                    item { ListHeader { Text("Upcoming deadlines") } }
                    items(payload.upcoming, key = { "u-" + WatchState.key(it) }) { t ->
                        val time = t.time
                        Chip(
                            onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(),
                            label = { Text(priorityMarks(t.priority) + t.title, maxLines = 2) },
                            secondaryLabel = if (time != null) ({ Text(time) }) else null,
                            colors = ChipDefaults.secondaryChipColors(),
                        )
                    }
                }
                item {
                    val updated = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(payload.updatedAt))
                    val status = buildList {
                        add("Updated $updated")
                        if (!state.phoneConnected) add("phone not connected")
                        if (state.pending.isNotEmpty()) add("${state.pending.size} waiting to sync")
                    }.joinToString(" · ")
                    Text(
                        status,
                        style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item {
                Chip(onClick = { scope.launch { WatchStore.refreshFromDataLayer(context) } }, label = { Text("Refresh") },
                    colors = ChipDefaults.secondaryChipColors())
            }
        }
    }
}

@Composable
private fun TaskToggle(t: WearTask, done: Boolean, onComplete: () -> Unit) {
    val meta = listOfNotNull(if (t.overdue) "Overdue" else null, t.time).joinToString(" · ")
    ToggleChip(
        checked = done,
        onCheckedChange = { if (!done) onComplete() },
        enabled = !done,
        label = {
            Text(priorityMarks(t.priority) + t.title, maxLines = 2, textDecoration = if (done) TextDecoration.LineThrough else null)
        },
        secondaryLabel = if (meta.isNotEmpty()) ({ Text(meta) }) else null,
        toggleControl = { androidx.wear.compose.material.Icon(imageVector = ToggleChipDefaults.checkboxIcon(done), contentDescription = null) },
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = (if (done) "Completed: " else "Complete ") + t.title
            stateDescription = if (done) "Done" else "Not done"
        },
    )
}
