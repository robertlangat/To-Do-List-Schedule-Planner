package com.robertlangat.todoplanner.ui.planner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robertlangat.todoplanner.container
import com.robertlangat.todoplanner.core.json.Json
import com.robertlangat.todoplanner.core.planner.BusyBlock
import com.robertlangat.todoplanner.core.planner.DraftItem
import com.robertlangat.todoplanner.core.planner.OfflinePlanner
import com.robertlangat.todoplanner.core.planner.PlanDraft
import com.robertlangat.todoplanner.core.planner.PlanPriority
import com.robertlangat.todoplanner.core.planner.PlanRequest
import com.robertlangat.todoplanner.core.planner.PlanSource
import com.robertlangat.todoplanner.core.planner.PlannerTask
import com.robertlangat.todoplanner.core.planner.RoughInputParser
import com.robertlangat.todoplanner.core.planner.TimeWindow
import com.robertlangat.todoplanner.domain.SubtaskDraft
import com.robertlangat.todoplanner.domain.TaskDraft
import com.robertlangat.todoplanner.domain.dueLocalDate
import com.robertlangat.todoplanner.domain.dueLocalTime
import com.robertlangat.todoplanner.planner.AiPlannerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

enum class PlannerMode { OFFLINE, AI }

data class PlannerState(
    val input: String = "",
    val date: LocalDate = LocalDate.now(),
    val start: LocalTime = LocalTime.of(17, 30),
    val end: LocalTime = LocalTime.of(19, 30),
    val includeUndated: Boolean = true,
    val shareBusyTitles: Boolean = false,
    val mode: PlannerMode = PlannerMode.OFFLINE,
    val loading: Boolean = false,
    val draft: PlanDraft? = null,
    val error: String? = null,
    /** JSON shown to the user for approval before anything is sent to the AI service. */
    val pendingAiPayload: String? = null,
    val saved: Int? = null,
)

class PlannerViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _state = MutableStateFlow(PlannerState())
    val state: StateFlow<PlannerState> = _state.asStateFlow()
    private var pendingPayload: Map<String, Any?>? = null

    fun update(transform: (PlannerState) -> PlannerState) = _state.update(transform)

    private suspend fun busyBlocks(date: LocalDate): List<BusyBlock> {
        val data = c.repository.snapshot()
        return data.occurrencesBetween(date, date).filter { !it.completed && it.time != null }.map {
            val start = date.atTime(it.time!!)
            BusyBlock(it.task.title, TimeWindow(start, start.plusMinutes((it.task.estimatedMinutes ?: 30).toLong())))
        }
    }

    private fun availability(s: PlannerState) = listOf(TimeWindow.on(s.date, s.start, s.end))

    /** Applies "I have two hours after work"-style hints to the date/time fields. */
    private fun applyHint(s: PlannerState): PlannerState {
        val hint = RoughInputParser.parse(s.input, LocalDate.now()).availability ?: return s
        val start = hint.start ?: s.start
        val end = hint.minutes?.let { start.plusMinutes(it.toLong()) } ?: s.end
        return s.copy(date = hint.date, start = start, end = if (end > start) end else LocalTime.of(23, 59))
    }

    fun planOffline() {
        viewModelScope.launch {
            val s = applyHint(_state.value)
            val parsed = RoughInputParser.parse(s.input, LocalDate.now()).tasks
            val existing = if (s.includeUndated) {
                val data = c.repository.snapshot()
                data.tasks.filter { t ->
                    t.rrule == null && !t.completed && (t.dueDate == null || (t.dueLocalDate == s.date && t.dueTimeMinutes == null))
                }.map { t ->
                    PlannerTask(
                        id = "existing:${t.id}", title = t.title,
                        durationMinutes = t.estimatedMinutes ?: RoughInputParser.defaultDuration(t.title),
                        priority = PlanPriority.entries.getOrElse(t.priority) { PlanPriority.NONE },
                        deadline = t.dueLocalDate?.atTime(23, 59), notes = t.notes.ifBlank { null },
                    )
                }
            } else emptyList()
            if (parsed.isEmpty() && existing.isEmpty()) {
                _state.value = s.copy(error = "Type a goal or a few tasks first (one per line works well).", draft = null)
                return@launch
            }
            val draft = OfflinePlanner.plan(PlanRequest(parsed + existing, availability(s), busyBlocks(s.date)))
            _state.value = s.copy(draft = draft, error = null, mode = PlannerMode.OFFLINE)
        }
    }

    /** Step 1 of AI planning: build the payload and ask the user to approve it. */
    fun prepareAi() {
        viewModelScope.launch {
            val s = applyHint(_state.value)
            if (s.input.isBlank()) { _state.value = s.copy(error = "Describe your goal or paste your tasks first."); return@launch }
            val payload = c.ai.buildPayload(s.input, s.date, availability(s), busyBlocks(s.date), s.shareBusyTitles)
            pendingPayload = payload
            _state.value = s.copy(pendingAiPayload = Json.stringify(payload), error = null)
        }
    }

    fun cancelAi() { pendingPayload = null; _state.update { it.copy(pendingAiPayload = null) } }

    /** Step 2: the user approved the exact payload. */
    fun sendAi() {
        val payload = pendingPayload ?: return
        val s = _state.value.copy(pendingAiPayload = null, loading = true)
        _state.value = s
        viewModelScope.launch {
            val settings = c.settings.current()
            val busy = busyBlocks(s.date)
            when (val r = c.ai.plan(settings.aiBackendUrl, payload, s.date, busy, availability(s))) {
                is AiPlannerClient.Outcome.Ok -> _state.update { it.copy(loading = false, draft = r.draft, mode = PlannerMode.AI, error = null) }
                is AiPlannerClient.Outcome.Error -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun editItem(index: Int, transform: (DraftItem) -> DraftItem) {
        viewModelScope.launch {
            val s = _state.value
            val draft = s.draft ?: return@launch
            val items = draft.items.toMutableList().also { it[index] = transform(it[index]) }
            val checked = OfflinePlanner.recheck(items, busyBlocks(s.date), availability(s))
            _state.update { it.copy(draft = draft.copy(items = checked)) }
        }
    }

    fun discardDraft() = _state.update { it.copy(draft = null) }

    fun save() {
        val s = _state.value
        val draft = s.draft ?: return
        viewModelScope.launch {
            var count = 0
            for (item in draft.items.filter { it.accepted }) {
                val date = item.slot?.start?.toLocalDate() ?: s.date
                val time = item.slot?.start?.toLocalTime()
                val id = item.task.id
                if (id.startsWith("existing:")) {
                    val t = c.repository.task(id.removePrefix("existing:")) ?: continue
                    val base = c.repository.draftOf(t)
                    c.repository.update(t.id, base.copy(dueDate = date, dueTime = time ?: t.dueLocalTime, estimatedMinutes = item.task.durationMinutes))
                } else {
                    c.repository.create(
                        TaskDraft(
                            title = item.task.title,
                            notes = listOfNotNull(item.task.notes, "Suggested by the AI planner.".takeIf { draft.source == PlanSource.AI })
                                .joinToString("\n\n"),
                            priority = item.task.priority.weight, dueDate = date, dueTime = time,
                            estimatedMinutes = item.task.durationMinutes,
                            subtasks = item.task.subtasks.map { SubtaskDraft(title = it) },
                        ),
                    )
                }
                count++
            }
            _state.update { it.copy(draft = null, saved = count, input = "") }
        }
    }

    fun consumeSaved() = _state.update { it.copy(saved = null) }
}
