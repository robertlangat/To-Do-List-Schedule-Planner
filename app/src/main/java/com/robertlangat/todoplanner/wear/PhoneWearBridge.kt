package com.robertlangat.todoplanner.wear

import android.content.Context
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.robertlangat.todoplanner.container
import com.robertlangat.todoplanner.core.wear.WearPaths
import com.robertlangat.todoplanner.core.wear.WearPayload
import com.robertlangat.todoplanner.core.wear.WearTask
import com.robertlangat.todoplanner.data.TaskRepository
import com.robertlangat.todoplanner.domain.TaskOccurrence
import com.robertlangat.todoplanner.domain.sortOccurrences
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Publishes today's tasks + upcoming deadlines to paired watches via the Wearable Data Layer. */
class PhoneWearBridge(private val context: Context, private val repository: TaskRepository) {

    suspend fun publish() {
        // Skip quietly on devices without Google Play services / no Wear OS pairing.
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrNull()
        if (nodes == null) return
        val data = repository.snapshot()
        val today = LocalDate.now()
        val todays = sortOccurrences(
            data.occurrencesBetween(today, today) +
                data.tasks.filter { it.rrule == null && !it.completed && (it.dueDate ?: Long.MAX_VALUE) < today.toEpochDay() }
                    .map { data.occurrence(it, LocalDate.ofEpochDay(it.dueDate!!)) },
        )
        val upcoming = sortOccurrences(data.occurrencesBetween(today.plusDays(1), today.plusDays(7)).filter { !it.completed }).take(15)
        val payload = WearPayload(System.currentTimeMillis(), todays.map { it.toWear(today) }, upcoming.map { it.toWear(today) })
        val req = PutDataMapRequest.create(WearPaths.TODAY).apply {
            dataMap.putString("payload", payload.encode())
            dataMap.putLong("updatedAt", payload.updatedAt) // guarantees the item changes on every publish
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(context).putDataItem(req).await() }
    }

    private fun TaskOccurrence.toWear(today: LocalDate) = WearTask(
        taskId = task.id,
        date = date?.toString(),
        title = task.title,
        time = time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) ?: date?.takeIf { it != today }?.format(DateTimeFormatter.ofPattern("EEE d MMM")),
        done = completed,
        priority = task.priority,
        overdue = isOverdue(today),
    )
}

/** Receives completions from the watch (as queued DataItems, so they survive disconnection). */
class PhoneWearListenerService : WearableListenerService() {
    // Work runs in the application-wide scope so it isn't cancelled when this service is destroyed.
    private val scope get() = applicationContext.container.scope

    override fun onDataChanged(events: DataEventBuffer) {
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(WearPaths.COMPLETE_PREFIX) == true }
            .map { it.dataItem.uri to it.dataItem.uri.pathSegments }
        if (items.isEmpty()) return
        scope.launch {
            val c = applicationContext.container
            for ((uri, segments) in items) {
                // /complete/<taskId>/<date|none>
                val taskId = segments.getOrNull(1) ?: continue
                val date = segments.getOrNull(2)?.takeIf { it != "none" }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                c.repository.setCompleted(taskId, date, true)
                runCatching { Wearable.getDataClient(applicationContext).deleteDataItems(uri).await() }
            }
            c.wear.publish()
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WearPaths.REFRESH) scope.launch { applicationContext.container.wear.publish() }
    }
}
