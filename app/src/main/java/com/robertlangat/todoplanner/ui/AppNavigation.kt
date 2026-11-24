package com.robertlangat.todoplanner.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
import com.robertlangat.todoplanner.ads.BannerAd
import com.robertlangat.todoplanner.container
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.robertlangat.todoplanner.ui.calendar.CalendarScreen
import com.robertlangat.todoplanner.ui.editor.TaskEditorScreen
import com.robertlangat.todoplanner.ui.mine.MineScreen
import com.robertlangat.todoplanner.ui.planner.PlannerScreen
import com.robertlangat.todoplanner.ui.settings.SettingsScreen
import com.robertlangat.todoplanner.ui.tasks.QuickAddSheet
import com.robertlangat.todoplanner.ui.tasks.TasksScreen
import kotlinx.coroutines.launch
import java.time.LocalDate

object Routes {
    const val TASKS = "tasks"
    const val CALENDAR = "calendar"
    const val MINE = "mine"
    const val PLANNER = "planner"
    const val SETTINGS = "settings"
    const val EDITOR = "editor?taskId={taskId}&date={date}&title={title}&listId={listId}"

    fun editor(taskId: String? = null, date: LocalDate? = null, title: String = "", listId: String? = null) =
        "editor?taskId=${taskId.orEmpty()}&date=${date?.toString().orEmpty()}&title=${Uri.encode(title)}&listId=${listId.orEmpty()}"
}

/** One-shot requests coming from notifications and the widget. */
sealed interface LaunchRequest {
    data object QuickAdd : LaunchRequest
    data class OpenTask(val taskId: String, val date: LocalDate?) : LaunchRequest
}

@Composable
fun AppRoot(vm: MainViewModel, launchRequest: LaunchRequest?, onLaunchHandled: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val data by vm.data.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var quickAdd by remember { mutableStateOf(false) }
    var calendarDate by remember { mutableStateOf(LocalDate.now()) }
    var currentList by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = context as? Activity
    val ads = context.container.ads

    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val result = snackbar.showSnackbar(m.text, m.actionLabel, withDismissAction = m.actionLabel == null,
                duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.action?.let { scope.launch { it() } }
        }
    }
    LaunchedEffect(launchRequest) {
        when (val r = launchRequest) {
            LaunchRequest.QuickAdd -> { nav.navigateTop(Routes.TASKS); quickAdd = true }
            is LaunchRequest.OpenTask -> nav.navigate(Routes.editor(r.taskId, r.date))
            null -> Unit
        }
        if (launchRequest != null) onLaunchHandled()
    }

    LaunchedEffect(Unit) {
        when (com.robertlangat.todoplanner.ScreenshotMode.scene) {
            "calendar" -> nav.navigateTop(Routes.CALENDAR)
            "mine" -> nav.navigateTop(Routes.MINE)
            "planner" -> nav.navigate(Routes.PLANNER)
            "editor" -> com.robertlangat.todoplanner.ScreenshotMode.reportId?.let { nav.navigate(Routes.editor(it)) }
        }
    }

    val topLevel = route in setOf(Routes.TASKS, Routes.CALENDAR, Routes.MINE)
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (topLevel) Column {
                BannerAd()
                NavigationBar {
                listOf(
                    Triple(Routes.TASKS, "Tasks", Icons.Filled.CheckCircle),
                    Triple(Routes.CALENDAR, "Calendar", Icons.Filled.CalendarMonth),
                    Triple(Routes.MINE, "Mine", Icons.Filled.Person),
                ).forEach { (r, label, icon) ->
                    NavigationBarItem(selected = route == r, onClick = { nav.navigateTop(r) }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
                }
            }
        },
        floatingActionButton = {
            if (route == Routes.TASKS || route == Routes.CALENDAR) {
                FloatingActionButton(onClick = { quickAdd = true }) { Icon(Icons.Filled.Add, "Add task") }
            }
        },
    ) { padding ->
        val bottomOnly = PaddingValues(bottom = padding.calculateBottomPadding())
        NavHost(nav, startDestination = Routes.TASKS, modifier = Modifier) {
            composable(Routes.TASKS) {
                TasksScreen(
                    vm, bottomOnly,
                    onOpenTask = { nav.navigate(Routes.editor(it.task.id, it.date)) },
                    onOpenPlanner = { nav.navigate(Routes.PLANNER) },
                    onCurrentListChanged = { currentList = it },
                )
            }
            composable(Routes.CALENDAR) {
                CalendarScreen(vm, bottomOnly, onOpenTask = { nav.navigate(Routes.editor(it.task.id, it.date)) }, onSelectedDateChanged = { calendarDate = it })
            }
            composable(Routes.MINE) {
                MineScreen(vm, bottomOnly, onOpenSettings = { nav.navigate(Routes.SETTINGS) }, onOpenPlanner = { nav.navigate(Routes.PLANNER) })
            }
            composable(
                Routes.EDITOR,
                arguments = listOf("taskId", "date", "title", "listId").map { navArgument(it) { type = NavType.StringType; defaultValue = "" } },
            ) {
                TaskEditorScreen(
                    data.lists, onClose = { nav.popBackStack() }, onMessage = vm::message,
                    onSaved = { nav.popBackStack(); ads.onNaturalBreak(activity) },
                )
            }
            composable(Routes.PLANNER) {
                PlannerScreen(
                    settings, onClose = { nav.popBackStack() }, onMessage = vm::message,
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onSaved = { nav.popBackStack(); ads.onNaturalBreak(activity) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(settings, onClose = { nav.popBackStack() }, onMessage = vm::message, onAddStarterLists = vm::addStarterLists)
            }
        }
    }

    if (quickAdd) {
        val prefillDate = if (route == Routes.CALENDAR) calendarDate else null
        val prefillList = if (route == Routes.TASKS) currentList else null
        QuickAddSheet(
            initialDate = prefillDate, initialListId = prefillList, lists = data.lists,
            onDismiss = { quickAdd = false },
            onAdd = { title, date, listId -> vm.quickAdd(title, date, listId) },
            onMore = { title, date, listId -> quickAdd = false; nav.navigate(Routes.editor(null, date, title, listId)) },
            onVoiceUnavailable = { vm.message("Voice input isn't available on this device") },
        )
    }
}

private fun NavHostController.navigateTop(route: String) = navigate(route) {
    popUpTo(Routes.TASKS) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
