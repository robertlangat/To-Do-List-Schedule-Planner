package com.robertlangat.todoplanner.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.robertlangat.todoplanner.container
import com.robertlangat.todoplanner.domain.EditScope
import com.robertlangat.todoplanner.domain.SubtaskDraft
import com.robertlangat.todoplanner.domain.TaskDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class EditorState(
    val loaded: Boolean = false,
    val taskId: String? = null,
    /** The occurrence that was tapped, for recurring tasks. */
    val occurrence: LocalDate? = null,
    val draft: TaskDraft = TaskDraft(title = ""),
    val original: TaskDraft? = null,
    val completed: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val isNew: Boolean get() = taskId == null
    val isRecurring: Boolean get() = original?.rule != null
    val changed: Boolean get() = original == null || draft != original
}

/**
 * Nav args: taskId, date (occurrence or prefill date, ISO), title, listId (prefills for new tasks).
 */
class EditorViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = app.container.repository
    private val settings = app.container.settings
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    init {
        val taskId = saved.get<String>("taskId")?.takeIf { it.isNotEmpty() }
        val date = saved.get<String>("date")?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val title = saved.get<String>("title").orEmpty()
        val listId = saved.get<String>("listId")?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            if (taskId == null) {
                _state.value = EditorState(loaded = true, draft = TaskDraft(title = title, dueDate = date, listId = listId))
            } else {
                val t = repo.task(taskId)
                if (t == null) {
                    _state.value = EditorState(loaded = true, error = "This task no longer exists.", done = true)
                } else {
                    val d = repo.draftOf(t)
                    val completed = if (t.rrule != null && date != null) repo.snapshot().completedDates(t.id).contains(date) else t.completed
                    _state.value = EditorState(true, taskId, date.takeIf { t.rrule != null }, d, d, completed)
                }
            }
        }
    }

    fun edit(transform: (TaskDraft) -> TaskDraft) = _state.update { it.copy(draft = transform(it.draft), error = null) }

    fun addSubtask() = edit { it.copy(subtasks = it.subtasks + SubtaskDraft(title = "")) }
    fun updateSubtask(index: Int, s: SubtaskDraft) = edit { d -> d.copy(subtasks = d.subtasks.toMutableList().also { it[index] = s }) }
    fun removeSubtask(index: Int) = edit { d -> d.copy(subtasks = d.subtasks.toMutableList().also { it.removeAt(index) }) }

    fun addReminder(offset: Int) = edit { it.copy(reminderOffsets = (it.reminderOffsets + offset).distinct().sorted()) }
    fun removeReminder(offset: Int) = edit { it.copy(reminderOffsets = it.reminderOffsets - offset) }

    suspend fun defaultReminderOffset(): Int = settings.current().defaultReminderOffset

    /** Saves. For an existing recurring task the caller must pass the scope chosen by the user. */
    fun save(scope: EditScope = EditScope.ALL) {
        val s = _state.value
        if (s.draft.title.isBlank()) { _state.update { it.copy(error = "Give the task a title") }; return }
        viewModelScope.launch {
            try {
                if (s.isNew) repo.create(s.draft) else repo.update(s.taskId!!, s.draft, scope, s.occurrence)
                _state.update { it.copy(done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't save") }
            }
        }
    }

    fun setCompleted(done: Boolean) {
        val s = _state.value
        val id = s.taskId ?: return
        viewModelScope.launch {
            repo.setCompleted(id, s.occurrence ?: s.original?.dueDate, done)
            _state.update { it.copy(completed = done) }
        }
    }
}
