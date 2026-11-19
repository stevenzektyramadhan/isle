package io.github.stevenzektyramadhan.isle.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.stevenzektyramadhan.isle.data.ExtractorRepository
import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import io.github.stevenzektyramadhan.isle.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PlayerEngine(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val extractor = ExtractorRepository(context, scope)
    private val player = ExoPlayer.Builder(context).build()
    private val mutableState = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { mutableState.update { it.copy(isPlaying = isPlaying) } }
            override fun onPlayerError(error: PlaybackException) { mutableState.update { it.copy(isLoading = false, statusMessage = "Playback failed") } }
        })
        scope.launch { val available = extractor.isAvailable(); mutableState.update { it.copy(ytdlpAvailable = available, statusMessage = if (available) it.statusMessage else "yt-dlp is unavailable") } }
    }

    fun dispatch(command: PlayerCommand) {
        check(Looper.myLooper() == Looper.getMainLooper())
        when (command) {
            is PlayerCommand.SubmitInput -> submit(command.input)
            PlayerCommand.TogglePlayPause -> if (player.isPlaying) player.pause() else player.play()
            PlayerCommand.Stop -> { generation++; job?.cancel(); player.stop(); player.clearMediaItems(); mutableState.update { it.copy(currentTrack = null, isPlaying = false, isLoading = false, statusMessage = null) } }
        }
    }

    private fun submit(input: String) {
        val current = ++generation
        job?.cancel()
        mutableState.update { it.copy(isLoading = true, statusMessage = "Searching ${input.trim()}…") }
        job = scope.launch {
            try {
                val track = extractor.resolveInput(input)
                val stream = extractor.resolveStream(track)
                if (current != generation) return@launch
                val factory = DefaultHttpDataSource.Factory()
                stream.userAgent?.let { factory.setUserAgent(it) }
                player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(stream.url)))
                player.prepare()
                player.play()
                mutableState.update { it.copy(currentTrack = track, isLoading = false, statusMessage = null) }
            } catch (error: ExtractorError) {
                if (current == generation) mutableState.update { it.copy(isLoading = false, statusMessage = error.message) }
            }
        }
    }

    fun leaveForeground() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation++
        job?.cancel()
        player.pause()
        mutableState.update { it.copy(isLoading = false, statusMessage = null) }
    }

    fun release() { check(Looper.myLooper() == Looper.getMainLooper()); job?.cancel(); scope.cancel(); player.release() }
}
