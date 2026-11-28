package io.github.stevenzektyramadhan.isle.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import io.github.stevenzektyramadhan.isle.IsleApp
import io.github.stevenzektyramadhan.isle.model.PlayerCommand
import io.github.stevenzektyramadhan.isle.model.PlayerState
import io.github.stevenzektyramadhan.isle.player.PlayerEngine

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
    private val playerViewModel: PlayerViewModel by viewModels {
        PlayerViewModel.Factory((application as IsleApp).playerEngine)
    }
    private val appUpdateViewModel: AppUpdateViewModel by viewModels {
        AppUpdateViewModel.Factory(packageManager.getPackageInfo(packageName, 0).versionName.orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val playerState by playerViewModel.state.collectAsStateWithLifecycle()
            var notificationPrompted by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(playerState.isLoading, playerState.isPlaying) {
                if ((playerState.isLoading || playerState.isPlaying) && !notificationPrompted) {
                    notificationPrompted = true
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            val appUpdate = appUpdateViewModel
            var input by rememberSaveable { mutableStateOf("") }
            var playerOpen by rememberSaveable { mutableStateOf(false) }
            var settingsOpen by rememberSaveable { mutableStateOf(false) }
            var queueOpen by rememberSaveable { mutableStateOf(false) }
            var submitCount by rememberSaveable { mutableIntStateOf(0) }
            var dismissedStatusKey by rememberSaveable { mutableStateOf("") }
            val statusKey = "${playerState.statusMessage.orEmpty()}#$submitCount"
            BackHandler(queueOpen || playerOpen || settingsOpen) {
                when {
                    queueOpen -> queueOpen = false
                    playerOpen -> playerOpen = false
                    settingsOpen -> settingsOpen = false
                }
            }
            IsleTheme {
                if (settingsOpen) {
                    SettingsScreen(
                        playerState = playerState,
                        appUpdate = appUpdate,
                        onBack = { settingsOpen = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (playerOpen && playerState.currentTrack != null) {
                    PlayerScreen(
                        state = playerState,
                        onCommand = playerViewModel::dispatch,
                        onClose = { playerOpen = false },
                        onOpenQueue = { queueOpen = true },
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    )
                } else {
                HomeScreen(
                    state = HomeUiState(
                        input = input,
                        isLoading = playerState.isLoading,
                        statusMessage = playerState.statusMessage,
                        status = playerState.status,
                        statusDismissed = dismissedStatusKey == statusKey,
                        player = playerState,
                    ),
                    onInputChange = { input = it },
                    onSubmit = { submitCount++; dismissedStatusKey = ""; playerViewModel.submit(input) },
                    onTogglePlayback = playerViewModel::togglePlayback,
                    onNext = { playerViewModel.dispatch(PlayerCommand.Next) },
                    onOpenPlayer = { playerOpen = true },
                    onOpenQueue = { queueOpen = true },
                    onOpenSettings = { settingsOpen = true },
                    updateNotice = { AppUpdateNotice(appUpdate, this@MainActivity, Modifier.fillMaxWidth()) },
                    onDismissStatus = { dismissedStatusKey = statusKey },
                )
                }
                if (queueOpen) QueueSheet(playerState, playerViewModel::dispatch) { queueOpen = false }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        playerViewModel.setVisible(true)
    }

    override fun onResume() {
        super.onResume()
        playerViewModel.onForegroundResumed()
    }

    override fun onStop() {
        playerViewModel.setVisible(false)
        super.onStop()
    }
}

class PlayerViewModel(private val engine: PlayerEngine) : ViewModel() {
    val state = engine.state
    fun dispatch(command: PlayerCommand) = engine.dispatch(command)
    fun submit(input: String) {
        engine.dispatch(PlayerCommand.SubmitInput(input))
    }

    fun togglePlayback() {
        engine.dispatch(PlayerCommand.TogglePlayPause)
    }

    fun setVisible(visible: Boolean) = engine.setVisible(visible)
    fun onForegroundResumed() = engine.onForegroundResumed()

    class Factory(private val engine: PlayerEngine) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlayerViewModel::class.java))
            return PlayerViewModel(engine) as T
        }
    }
}
