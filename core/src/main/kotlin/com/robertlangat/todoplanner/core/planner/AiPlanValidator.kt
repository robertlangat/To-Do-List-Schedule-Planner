package com.robertlangat.todoplanner.core.planner

import com.robertlangat.todoplanner.core.json.Json
import com.robertlangat.todoplanner.core.json.asList
import com.robertlangat.todoplanner.core.json.asObject
import com.robertlangat.todoplanner.core.json.int
import com.robertlangat.todoplanner.core.json.str
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Validates the structured response from the AI backend before anything is shown to the user.
 * Unknown fields are ignored, bad items are dropped with a warning, and values are clamped to sane
 * ranges. If nothing usable remains the whole response is rejected.
 *
 * Expected shape:
 * { "summary": "...", "model": "...", "tasks": [ { "title": "...", "notes": "...",
 *   "durationMinutes": 45, "priority": "high|medium|low|none", "start": "2026-11-16T18:00",
 *   "deadline": "2026-11-17T23:59", "subtasks": ["..."] } ] }
 */
object AiPlanValidator {
    const val MAX_TASKS = 30
    const val MAX_SUBTASKS = 20
    const val MAX_TITLE = 200

    class InvalidResponse(message: String) : Exception(message)

    fun validate(
        rawJson: String,
        planDate: LocalDate,
        busy: List<BusyBlock>,
        availability: List<TimeWindow>,
    ): PlanDraft {
        val root = runCatching { Json.parse(rawJson).asObject() }.getOrNull()
            ?: throw InvalidResponse("The AI service returned something that isn't valid JSON.")
        val rawTasks = root["tasks"].asList() ?: throw InvalidResponse("The AI response had no task list.")
        val warnings = mutableListOf<String>()
        if (rawTasks.size > MAX_TASKS) warnings += "Only the first $MAX_TASKS suggestions are shown."

        val items = rawTasks.take(MAX_TASKS).mapIndexedNotNull { i, raw ->
            val o = raw.asObject()
            val title = o?.str("title")?.trim()?.take(MAX_TITLE)
            if (o == null || title.isNullOrEmpty()) {
                warnings += "Suggestion ${i + 1} was skipped because it had no title."
                return@mapIndexedNotNull null
            }
            val requested = o.int("durationMinutes")
            val duration = (requested ?: RoughInputParser.defaultDuration(title)).coerceIn(5, 12 * 60)
            if (requested != null && requested != duration) warnings += "\"$title\": duration adjusted to $duration min."
            val priority = when (o.str("priority")?.lowercase()) {
                "high", "urgent" -> PlanPriority.HIGH
                "medium", "med", "normal" -> PlanPriority.MEDIUM
                "low" -> PlanPriority.LOW
                else -> PlanPriority.NONE
            }
            val start = o.str("start")?.let { parseDateTime(it, planDate) }
            if (o.str("start") != null && start == null) warnings += "\"$title\": couldn't read the suggested time, left unscheduled."
            val deadline = o.str("deadline")?.let { parseDateTime(it, planDate) }
            val subtasks = (o["subtasks"].asList() ?: emptyList())
                .mapNotNull { (it as? String)?.trim()?.take(MAX_TITLE)?.takeIf(String::isNotEmpty) }
                .take(MAX_SUBTASKS)
            val task = PlannerTask(
                id = "ai-$i", title = title, durationMinutes = duration, priority = priority,
                deadline = deadline, subtasks = subtasks, notes = o.str("notes")?.trim()?.take(2000),
            )
            DraftItem(task, start?.let { TimeWindow(it, it.plusMinutes(duration.toLong())) })
        }
        if (items.isEmpty()) throw InvalidResponse("The AI response didn't contain any usable tasks.")
        val checked = OfflinePlanner.recheck(items, busy, availability)
        if (checked.any { DraftFlag.OVERLAPS_EXISTING in it.flags || DraftFlag.OVERLAPS_DRAFT in it.flags }) {
            warnings += "Some suggested times overlap other tasks. They're marked below."
        }
        return PlanDraft(
            source = PlanSource.AI,
            items = OfflinePlanner.sortForDisplay(checked),
            summary = root.str("summary")?.trim()?.take(500) ?: "AI-suggested plan",
            warnings = warnings,
            modelName = root.str("model")?.take(80),
        )
    }

    private fun parseDateTime(s: String, planDate: LocalDate): LocalDateTime? = runCatching {
        LocalDateTime.parse(s.trim().removeSuffix("Z").take(19).let { if (it.length == 16) "$it:00" else it })
    }.recoverCatching {
        planDate.atTime(LocalTime.parse(s.trim()))
    }.getOrNull()
}
