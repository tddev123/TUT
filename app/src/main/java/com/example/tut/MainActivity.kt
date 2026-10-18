package com.example.tut

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.tut.ui.DataScreen
import com.example.tut.ui.LocalMicPermission
import com.example.tut.ui.MainScreen
import com.example.tut.ui.NewDataScreen
import com.example.tut.ui.ModelScreen
import com.example.tut.ui.PredictScreen
import com.example.tut.ui.SettingsScreen
import com.example.tut.ui.SkillRecordScreen
import com.example.tut.ui.SkillsScreen
import com.example.tut.ui.TutColors
import com.example.tut.ui.TutTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Main : Screen
    data object Settings : Screen
    data class NewData(val sessionId: Long) : Screen
    data object Data : Screen
    data object SkillRecord : Screen
    data object Skills : Screen
    data object Predict : Screen
    data object Model : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TutApp
        setContent {
            TutTheme {
                Box(Modifier.fillMaxSize().background(TutColors.Background).safeDrawingPadding()) {
                    TutNavigation(app)
                }
            }
        }
    }
}

@Composable
private fun TutNavigation(app: TutApp) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Main)) }
    val scope = rememberCoroutineScope()
    val mode by app.mode.collectAsState()
    val settings by app.settings.settings.collectAsState()
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    val context = LocalContext.current
    var onMicGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onMicGranted?.invoke()
        else Toast.makeText(context, "Microphone permission is needed to record", Toast.LENGTH_LONG).show()
        onMicGranted = null
    }
    val requestMic: (() -> Unit) -> Unit = { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            onMicGranted = action
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    CompositionLocalProvider(LocalMicPermission provides requestMic) {
        when (val screen = stack.last()) {
            Screen.Main -> MainScreen(
                mode = mode,
                logoHoldMs = settings.logoHoldMs.toLong(),
                onToggleMode = app::toggleMode,
                onSettings = { push(Screen.Settings) },
                onNewData = { startedAt ->
                    if (mode == DbMode.SKILLS) push(Screen.SkillRecord)
                    else scope.launch {
                        val id = withContext(Dispatchers.IO) { app.repo.createSession(startedAt) }
                        push(Screen.NewData(id))
                    }
                },
                onDatabase = { push(if (mode == DbMode.SKILLS) Screen.Skills else Screen.Data) },
            )
            Screen.Settings -> SettingsScreen(app, onBack = ::pop)
            is Screen.NewData -> NewDataScreen(app, screen.sessionId, onExit = ::pop)
            Screen.Data -> DataScreen(
                app, onBack = ::pop, onSettings = { push(Screen.Settings) },
                onResume = { id -> requestMic { push(Screen.NewData(id)) } },
                onPredict = { push(Screen.Predict) },
                onModel = { push(Screen.Model) },
            )
            Screen.Predict -> PredictScreen(app, onBack = ::pop)
            Screen.Model -> ModelScreen(app, onBack = ::pop)
            Screen.SkillRecord -> SkillRecordScreen(app, onExit = ::pop)
            Screen.Skills -> SkillsScreen(app, onBack = ::pop, onSettings = { push(Screen.Settings) })
        }
    }
    // The main page is the end of the line for going back: let the system close the app there.
    BackHandler(enabled = false) {}
}
