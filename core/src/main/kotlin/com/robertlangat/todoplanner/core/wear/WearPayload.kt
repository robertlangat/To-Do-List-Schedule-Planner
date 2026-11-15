package com.robertlangat.todoplanner.core.wear

import com.robertlangat.todoplanner.core.json.Json
import com.robertlangat.todoplanner.core.json.asList
import com.robertlangat.todoplanner.core.json.asObject
import com.robertlangat.todoplanner.core.json.bool
import com.robertlangat.todoplanner.core.json.int
import com.robertlangat.todoplanner.core.json.long
import com.robertlangat.todoplanner.core.json.str

/** Data Layer paths shared by the phone app and the Wear OS app. */
object WearPaths {
    /** Phone -> watch DataItem with today's tasks and upcoming deadlines. */
    const val TODAY = "/today"
    /** Watch -> phone DataItem per completion: /complete/<taskId>/<date or none>. Queued while offline. */
    const val COMPLETE_PREFIX = "/complete"
    /** Watch -> phone message asking for a fresh /today. */
    const val REFRESH = "/refresh"

    fun completePath(taskId: String, date: String?) = "$COMPLETE_PREFIX/$taskId/${date ?: "none"}"
}

data class WearTask(
    val taskId: String,
    /** ISO date of the occurrence, or null for undated tasks. */
    val date: String?,
    val title: String,
    /** Display time like "18:00", or null for all-day. */
    val time: String?,
    val done: Boolean,
    val priority: Int,
    val overdue: Boolean,
)

data class WearPayload(val updatedAt: Long, val today: List<WearTask>, val upcoming: List<WearTask>) {
    fun encode(): String = Json.stringify(
        mapOf("v" to 1, "updatedAt" to updatedAt, "today" to today.map(::enc), "upcoming" to upcoming.map(::enc)),
    )

    companion object {
        private fun enc(t: WearTask) = mapOf(
            "id" to t.taskId, "date" to t.date, "title" to t.title, "time" to t.time,
            "done" to t.done, "priority" to t.priority, "overdue" to t.overdue,
        )

        private fun dec(raw: Any?): WearTask? {
            val o = raw.asObject() ?: return null
            return WearTask(
                o.str("id") ?: return null, o.str("date"), o.str("title") ?: "", o.str("time"),
                o.bool("done") ?: false, o.int("priority") ?: 0, o.bool("overdue") ?: false,
            )
        }

        fun decode(text: String): WearPayload? = runCatching {
            val o = Json.parse(text).asObject() ?: return null
            WearPayload(
                o.long("updatedAt") ?: 0L,
                o["today"].asList().orEmpty().mapNotNull(::dec),
                o["upcoming"].asList().orEmpty().mapNotNull(::dec),
            )
        }.getOrNull()
    }
}
