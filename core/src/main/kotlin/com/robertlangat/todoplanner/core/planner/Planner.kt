package com.robertlangat.todoplanner.core.planner

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class PlanPriority(val weight: Int) { NONE(0), LOW(1), MEDIUM(2), HIGH(3) }

enum class PlanSource(val label: String) {
    OFFLINE("Offline planner"),
    AI("AI service"),
}

/** A thing the user wants done. Produced by the rough-text parser, the AI, or existing undated tasks. */
data class PlannerTask(
    val id: String,
    val title: String,
    val durationMinutes: Int = 30,
    val priority: PlanPriority = PlanPriority.NONE,
    val deadline: LocalDateTime? = null,
    val subtasks: List<String> = emptyList(),
    val notes: String? = null,
)

data class TimeWindow(val start: LocalDateTime, val end: LocalDateTime) {
    init { require(!end.isBefore(start)) { "window end before start" } }
    val minutes: Long get() = Duration.between(start, end).toMinutes()
    fun overlaps(other: TimeWindow) = start < other.end && other.start < end

    companion object {
        fun on(date: LocalDate, from: LocalTime, to: LocalTime) =
            TimeWindow(date.atTime(from), if (to <= from) date.plusDays(1).atTime(to) else date.atTime(to))
    }
}

/** An existing scheduled task, used to avoid and flag overlaps. */
data class BusyBlock(val title: String, val window: TimeWindow)

enum class DraftFlag { OVERLAPS_EXISTING, OVERLAPS_DRAFT, AFTER_DEADLINE, OUTSIDE_AVAILABILITY, UNSCHEDULED }

data class DraftItem(
    val task: PlannerTask,
    val slot: TimeWindow?,
    val flags: Set<DraftFlag> = emptySet(),
    val conflictsWith: List<String> = emptyList(),
    val accepted: Boolean = true,
)

data class PlanDraft(
    val source: PlanSource,
    val items: List<DraftItem>,
    val summary: String,
    val warnings: List<String> = emptyList(),
    val modelName: String? = null,
)

data class PlanRequest(
    val tasks: List<PlannerTask>,
    val availability: List<TimeWindow>,
    val busy: List<BusyBlock>,
    val bufferMinutes: Int = 5,
)

/**
 * Deterministic, offline scheduler. Order: earliest deadline first, then higher priority, then
 * shorter tasks (so quick wins aren't starved). Each task goes into the earliest free gap inside
 * the user's availability that avoids existing tasks; tasks that don't fit are returned
 * unscheduled rather than silently dropped.
 */
object OfflinePlanner {

    fun plan(request: PlanRequest): PlanDraft {
        val ordered = request.tasks.sortedWith(
            compareBy<PlannerTask>({ it.deadline ?: LocalDateTime.MAX })
                .thenByDescending { it.priority.weight }
                .thenBy { it.durationMinutes },
        )
        val occupied = request.busy.map { it.window }.toMutableList()
        val windows = request.availability.sortedBy { it.start }
        val items = mutableListOf<DraftItem>()

        for (task in ordered) {
            val needed = task.durationMinutes.toLong().coerceAtLeast(5)
            val slot = findSlot(windows, occupied, needed, request.bufferMinutes.toLong())
            if (slot == null) {
                items += DraftItem(task, null, setOf(DraftFlag.UNSCHEDULED))
                continue
            }
            occupied += TimeWindow(slot.start, slot.end.plusMinutes(request.bufferMinutes.toLong()))
            val flags = mutableSetOf<DraftFlag>()
            if (task.deadline != null && slot.end > task.deadline) flags += DraftFlag.AFTER_DEADLINE
            items += DraftItem(task, slot, flags)
        }
        val scheduled = items.count { it.slot != null }
        val unscheduled = items.size - scheduled
        val totalMinutes = items.filter { it.slot != null }.sumOf { it.task.durationMinutes }
        val warnings = buildList {
            if (request.availability.isEmpty()) add("No available time was given, so nothing could be scheduled.")
            if (unscheduled > 0) add("$unscheduled task(s) didn't fit in your available time.")
            if (items.any { DraftFlag.AFTER_DEADLINE in it.flags }) add("Some tasks are scheduled after their deadline.")
        }
        val summary = "Scheduled $scheduled of ${items.size} tasks ($totalMinutes min)" +
            if (unscheduled > 0) "; $unscheduled left unscheduled." else "."
        return PlanDraft(PlanSource.OFFLINE, sortForDisplay(items), summary, warnings)
    }

    private fun findSlot(windows: List<TimeWindow>, occupied: List<TimeWindow>, minutes: Long, buffer: Long): TimeWindow? {
        for (w in windows) {
            var cursor = w.start
            val blockers = occupied.filter { it.overlaps(w) }.sortedBy { it.start }
            for (b in blockers + TimeWindow(w.end, w.end)) {
                val gapEnd = minOf(b.start, w.end)
                if (Duration.between(cursor, gapEnd).toMinutes() >= minutes) {
                    return TimeWindow(cursor, cursor.plusMinutes(minutes))
                }
                if (b.end > cursor) cursor = maxOf(cursor, b.end.plusMinutes(if (b.start == b.end) 0 else buffer))
                if (cursor >= w.end) break
            }
        }
        return null
    }

    fun sortForDisplay(items: List<DraftItem>) =
        items.sortedWith(compareBy<DraftItem>({ it.slot == null }, { it.slot?.start }))

    /**
     * Re-checks a draft after the user edits times (or after an AI response). Flags overlaps with
     * existing tasks, with other accepted draft items, and slots outside the stated availability.
     */
    fun recheck(items: List<DraftItem>, busy: List<BusyBlock>, availability: List<TimeWindow>): List<DraftItem> =
        items.map { item ->
            val slot = item.slot ?: return@map item.copy(flags = setOf(DraftFlag.UNSCHEDULED), conflictsWith = emptyList())
            val flags = mutableSetOf<DraftFlag>()
            val conflicts = mutableListOf<String>()
            busy.filter { it.window.overlaps(slot) }.forEach { flags += DraftFlag.OVERLAPS_EXISTING; conflicts += it.title }
            items.filter { it !== item && it.accepted && it.slot?.overlaps(slot) == true }
                .forEach { flags += DraftFlag.OVERLAPS_DRAFT; conflicts += it.task.title }
            if (availability.isNotEmpty() && availability.none { !slot.start.isBefore(it.start) && !slot.end.isAfter(it.end) }) {
                flags += DraftFlag.OUTSIDE_AVAILABILITY
            }
            if (item.task.deadline != null && slot.end > item.task.deadline) flags += DraftFlag.AFTER_DEADLINE
            item.copy(flags = flags, conflictsWith = conflicts.distinct())
        }
}
