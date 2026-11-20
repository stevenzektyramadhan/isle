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
import io.github.stevenzektyramadhan.isle.IsleApp
import io.github.stevenzektyramadhan.isle.model.PlayerCommand
import io.github.stevenzektyramadhan.isle.player.PlayerEngine

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
    private val playerViewModel: PlayerViewModel by viewModels {
        PlayerViewModel.Factory((application as IsleApp).playerEngine)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val playerState by playerViewModel.state.collectAsStateWithLifecycle()
            var input by rememberSaveable { mutableStateOf("") }
            IsleTheme {
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
                )
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
