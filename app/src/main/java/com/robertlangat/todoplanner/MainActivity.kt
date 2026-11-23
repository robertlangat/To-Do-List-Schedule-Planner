package com.robertlangat.todoplanner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertlangat.todoplanner.ui.AppRoot
import com.robertlangat.todoplanner.ui.LaunchRequest
import com.robertlangat.todoplanner.ui.MainViewModel
import com.robertlangat.todoplanner.ui.theme.TodoTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_QUICK_ADD = "com.robertlangat.todoplanner.QUICK_ADD"
        const val ACTION_OPEN_TASK = "com.robertlangat.todoplanner.OPEN_TASK"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_DATE = "date"
    }

    private val vm: MainViewModel by viewModels()
    private var launchRequest by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) launchRequest = parse(intent)
        container.ads.gatherConsent(this)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            TodoTheme(settings.themeMode, settings.accentIndex) {
                AppRoot(vm, launchRequest, onLaunchHandled = { launchRequest = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequest = parse(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshToday()
    }

    private fun parse(intent: Intent?): LaunchRequest? = when (intent?.action) {
        ACTION_QUICK_ADD -> LaunchRequest.QuickAdd
        ACTION_OPEN_TASK -> intent.getStringExtra(EXTRA_TASK_ID)?.let { id ->
            LaunchRequest.OpenTask(id, intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
        }
        else -> null
    }
}
