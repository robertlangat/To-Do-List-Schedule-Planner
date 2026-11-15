package com.robertlangat.todoplanner.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.robertlangat.todoplanner.core.wear.WearPaths

/** Receives /today updates from the phone, even when the watch app isn't open. */
class WatchListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == WearPaths.TODAY) {
                DataMapItem.fromDataItem(e.dataItem).dataMap.getString("payload")?.let { WatchStore.onPayload(applicationContext, it) }
            }
        }
    }
}
