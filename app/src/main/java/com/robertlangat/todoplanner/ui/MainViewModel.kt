package com.robertlangat.todoplanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robertlangat.todoplanner.container
import com.robertlangat.todoplanner.data.Settings
import com.robertlangat.todoplanner.domain.AppData
import com.robertlangat.todoplanner.domain.EditScope
import com.robertlangat.todoplanner.domain.TaskOccurrence
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A snackbar message, optionally with an action such as Undo. */
data class UiMessage(val text: String, val actionLabel: String? = null, val action: (suspend () -> Unit)? = null)

/** Shared state and task actions for the top-level screens. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val repository get() = c.repository

    val data: StateFlow<AppData> = c.repository.data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppData())
    val settings: StateFlow<Settings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    init {
        // Keep "today" correct if the app stays open past midnight.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val now = LocalDate.now()
                if (now != _today.value) _today.value = now
            }
        }
    }

    fun refreshToday() { _today.value = LocalDate.now() }

    fun message(text: String) { _messages.tryEmit(UiMessage(text)) }

    private fun launchSafely(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { _messages.tryEmit(UiMessage(e.message ?: "Something went wrong")) }
    }

    fun toggle(item: TaskOccurrence) = launchSafely {
        val nowDone = !item.completed
        repository.setCompleted(item.task.id, item.date, nowDone)
        _messages.tryEmit(
            UiMessage(if (nowDone) "Completed “${item.task.title}”" else "Reopened “${item.task.title}”", "Undo") {
                repository.setCompleted(item.task.id, item.date, item.completed)
            },
        )
    }

    fun delete(item: TaskOccurrence, scope: EditScope) = launchSafely {
        val token = repository.delete(item.task.id, scope, item.date) ?: return@launchSafely
        _messages.tryEmit(UiMessage("Deleted “${item.task.title}”", "Undo") { repository.undo(token) })
    }

    fun star(item: TaskOccurrence) = launchSafely { repository.setStarred(item.task.id, !item.task.starred) }

    fun duplicate(item: TaskOccurrence) = launchSafely {
        val id = repository.duplicate(item.task.id) ?: return@launchSafely
        _messages.tryEmit(UiMessage("Duplicated “${item.task.title}”", "Undo") {
            repository.delete(id)
        })
    }

    fun move(item: TaskOccurrence, listId: String?) = launchSafely {
        val previous = item.task.listId
        repository.move(item.task.id, listId)
        val name = listId?.let { data.value.listsById[it]?.name } ?: "Inbox"
        _messages.tryEmit(UiMessage("Moved to $name", "Undo") { repository.move(item.task.id, previous) })
    }

    fun reschedule(item: TaskOccurrence, newDate: LocalDate?) = launchSafely {
        repository.reschedule(item.task.id, item.date, newDate)
        _messages.tryEmit(UiMessage(if (newDate == null) "Removed date" else "Rescheduled"))
    }

    fun quickAdd(title: String, date: LocalDate?, listId: String?) = launchSafely {
        if (title.isBlank()) return@launchSafely
        val id = repository.quickAdd(title, date, listId)
        _messages.tryEmit(UiMessage("Added “${title.trim()}”", "Undo") { repository.delete(id) })
    }

    fun createList(name: String, color: Long?, icon: String?, onCreated: (String) -> Unit = {}) = launchSafely {
        onCreated(repository.createList(name, color, icon))
    }

    fun updateList(id: String, name: String, color: Long?, icon: String?) = launchSafely { repository.updateList(id, name, color, icon) }

    fun deleteList(id: String) = launchSafely {
        repository.deleteList(id)
        _messages.tryEmit(UiMessage("List deleted. Its tasks moved to Inbox."))
    }

    fun addStarterLists(keys: Set<String>) = launchSafely { repository.addStarterLists(keys) }

    fun skipStarterLists() = launchSafely { c.settings.setStarterListsOffered() }

    fun setSubtaskDone(id: String, done: Boolean) = launchSafely { repository.setSubtaskDone(id, done) }
}
