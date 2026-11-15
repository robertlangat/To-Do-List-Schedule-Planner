package com.robertlangat.todoplanner.wear

import android.content.Context
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.robertlangat.todoplanner.core.wear.WearPaths
import com.robertlangat.todoplanner.core.wear.WearPayload
import com.robertlangat.todoplanner.core.wear.WearTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class WatchState(
    val payload: WearPayload? = null,
    /** Completions tapped on the watch that the phone hasn't confirmed yet (key = taskId@date). */
    val pending: Set<String> = emptySet(),
    val phoneConnected: Boolean = true,
) {
    fun isDone(t: WearTask) = t.done || key(t) in pending
    companion object { fun key(t: WearTask) = "${t.taskId}@${t.date ?: "none"}" }
}

/**
 * Watch-side cache of the phone's /today data. Completions are written as DataItems, which the
 * Data Layer queues and delivers when the phone reconnects, so a tap is never lost.
 */
object WatchStore {
    private const val PREFS = "watch"
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    fun loadCache(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val payload = p.getString("payload", null)?.let(WearPayload::decode)
        val pending = p.getStringSet("pending", emptySet()).orEmpty()
        _state.value = _state.value.copy(payload = payload ?: _state.value.payload, pending = pending)
    }

    fun onPayload(context: Context, json: String) {
        val payload = WearPayload.decode(json) ?: return
        // Drop pending marks that the phone has now confirmed.
        val confirmed = (payload.today + payload.upcoming).filter { it.done }.map(WatchState::key).toSet()
        val stillPending = _state.value.pending - confirmed
        _state.value = _state.value.copy(payload = payload, pending = stillPending)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("payload", json).putStringSet("pending", stillPending).apply()
    }

    suspend fun refreshFromDataLayer(context: Context) {
        runCatching {
            val items = Wearable.getDataClient(context).dataItems.await()
            try {
                items.firstOrNull { it.uri.path == WearPaths.TODAY }?.let { item ->
                    DataMapItem.fromDataItem(item).dataMap.getString("payload")?.let { onPayload(context, it) }
                }
            } finally { items.release() }
        }
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrNull().orEmpty()
        _state.value = _state.value.copy(phoneConnected = nodes.isNotEmpty())
        // Ask the phone to republish so "Updated" is fresh.
        nodes.forEach { n -> runCatching { Wearable.getMessageClient(context).sendMessage(n.id, WearPaths.REFRESH, ByteArray(0)).await() } }
    }

    suspend fun complete(context: Context, task: WearTask) {
        val key = WatchState.key(task)
        val pending = _state.value.pending + key
        _state.value = _state.value.copy(pending = pending)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("pending", pending).apply()
        val req = PutDataMapRequest.create(WearPaths.completePath(task.taskId, task.date)).apply {
            dataMap.putLong("at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(context).putDataItem(req).await() }
    }
}
