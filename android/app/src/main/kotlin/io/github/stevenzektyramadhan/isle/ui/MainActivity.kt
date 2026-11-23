package io.github.stevenzektyramadhan.isle.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.stevenzektyramadhan.isle.update.ManualUpdateResult
import io.github.stevenzektyramadhan.isle.update.AppUpdateResult
import io.github.stevenzektyramadhan.isle.update.YtDlpUpdater
import io.github.stevenzektyramadhan.isle.IsleApp
import io.github.stevenzektyramadhan.isle.model.PlayerCommand
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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val playerState by playerViewModel.state.collectAsStateWithLifecycle()
            val appUpdate = appUpdateViewModel
            var input by rememberSaveable { mutableStateOf("") }
            var playerOpen by rememberSaveable { mutableStateOf(false) }
            var settingsOpen by rememberSaveable { mutableStateOf(false) }
            IsleTheme {
                if (settingsOpen) {
                    val updater = remember { YtDlpUpdater(applicationContext) }
                    val scope = rememberCoroutineScope()
                    var checking by remember { mutableStateOf(false) }
                    var result by remember { mutableStateOf<ManualUpdateResult?>(null) }
                    BackHandler { settingsOpen = false }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            TextButton(onClick = { settingsOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                            Text("Settings", style = MaterialTheme.typography.headlineSmall)
                            Text("App version ${packageManager.getPackageInfo(packageName, 0).versionName}")
                            Button(onClick = { appUpdate.check(true) }, enabled = !appUpdate.checking, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (appUpdate.checking) "Checking Isle update..." else "Check for Isle app update")
                            }
                            AppUpdateNotice(appUpdate, this@MainActivity)
                            if (appUpdate.manual && !appUpdate.checking) {
                                when (appUpdate.result) {
                                    AppUpdateResult.Current -> Text("Isle is up to date")
                                    AppUpdateResult.Error -> Text("Could not check for Isle updates. Check your connection and try again.", color = MaterialTheme.colorScheme.error)
                                    else -> Unit
                                }
                            }
                            Button(onClick = {
                                checking = true
                                result = null
                                scope.launch {
                                    try { result = updater.checkManually() } finally { checking = false }
                                }
                            }, enabled = !checking, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (checking) "Checking..." else "Check for yt-dlp update")
                            }
                            when (result) {
                                ManualUpdateResult.SUCCESS -> Text("yt-dlp update check completed")
                                ManualUpdateResult.FAILURE -> Text("yt-dlp update failed", color = MaterialTheme.colorScheme.error)
                                ManualUpdateResult.INTEGRATION_REQUIRED -> Text("Update unavailable until extractor coordination is enabled", color = MaterialTheme.colorScheme.error)
                                null -> Unit
                            }
                        }
                    }
                } else if (playerOpen && playerState.currentTrack != null) {
                    PlayerScreen(
                        state = playerState,
                        onCommand = playerViewModel::dispatch,
                        onClose = { playerOpen = false },
                    )
                } else {
                HomeScreen(
                    state = HomeUiState(
                        input = input,
                        isLoading = playerState.isLoading,
                        errorMessage = playerState.statusMessage.takeUnless { playerState.isLoading },
                        currentSong = playerState.currentTrack?.let { CurrentSongUi(it.title, it.artist) },
                        isPlaying = playerState.isPlaying,
                    ),
                    onInputChange = { input = it },
                    onSubmit = { playerViewModel.submit(input) },
                    onTogglePlayback = playerViewModel::togglePlayback,
                    onOpenPlayer = { playerOpen = true },
                    onOpenSettings = { settingsOpen = true },
                    updateNotice = { AppUpdateNotice(appUpdate, this@MainActivity) },
                )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        playerViewModel.setVisible(true)
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

    class Factory(private val engine: PlayerEngine) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlayerViewModel::class.java))
            return PlayerViewModel(engine) as T
        }
    }
}
