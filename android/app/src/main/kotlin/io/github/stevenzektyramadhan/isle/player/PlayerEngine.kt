package io.github.stevenzektyramadhan.isle.player

import android.content.Context
import android.content.ComponentName
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.stevenzektyramadhan.isle.data.ExtractorRepository
import io.github.stevenzektyramadhan.isle.data.ResolvedStream
import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import io.github.stevenzektyramadhan.isle.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val extractor = ExtractorRepository(context, scope)
    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()
    val sessionPlayer: Player = SessionPlayer(player, ::dispatch)
    private var controller: ListenableFuture<MediaController>? = null
    private var connectionGeneration = 0L
    private var visible = false
    private val mutableState = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var mediaGeneration = 0L
    private var mediaSequence = 0L
    private val recovery = PlaybackRecovery()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { mutableState.update { it.copy(isPlaying = isPlaying) } }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) recovery.ready(player.currentMediaItem?.mediaId)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player.playerError !== error || mediaGeneration != generation) return
                val status = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val track = recovery.retry(generation, player.currentMediaItem?.mediaId, status)
                if (track == null) { reportUnavailable("Playback failed"); return }
                val current = generation
                mutableState.update { it.copy(isLoading = true, statusMessage = "Retrying playback…") }
                job?.cancel()
                job = scope.launch {
                    try {
                        val stream = extractor.resolveStream(track, finalOnly = true)
                        if (current != generation) return@launch
                        if (!visible) { reportUnavailable("Open Isle to start playback"); return@launch }
                        installStream(track, stream, current)
                    } catch (error: ExtractorError) {
                        if (current == generation) reportUnavailable("Playback failed")
                    }
                }
            }
        })
        scope.launch { val available = extractor.isAvailable(); mutableState.update { it.copy(ytdlpAvailable = available, statusMessage = if (available) it.statusMessage else "yt-dlp is unavailable") } }
    }

    fun dispatch(command: PlayerCommand) {
        check(Looper.myLooper() == Looper.getMainLooper())
        when (command) {
            is PlayerCommand.SubmitInput -> submit(command.input)
            PlayerCommand.TogglePlayPause -> if (player.playWhenReady) player.pause() else resumePlayback()
            PlayerCommand.Stop -> { generation++; job?.cancel(); player.stop(); player.clearMediaItems(); disconnect(); mutableState.update { it.copy(currentTrack = null, isPlaying = false, isLoading = false, statusMessage = null) } }
            PlayerCommand.Next, PlayerCommand.Previous -> Unit
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
                awaitSession()
                if (current != generation) return@launch
                if (!visible) { reportUnavailable("Open Isle to start playback"); return@launch }
                installStream(track, stream, current)
            } catch (error: ExtractorError) {
                if (current == generation) mutableState.update { it.copy(isLoading = false, statusMessage = error.message) }
            } catch (error: java.util.concurrent.ExecutionException) {
                if (current == generation) { disconnect(); reportUnavailable("Playback service unavailable") }
            }
        }
    }

    private fun installStream(track: TrackItem, resolved: ResolvedStream, current: Long) {
        val factory = DefaultHttpDataSource.Factory()
        resolved.stream.userAgent?.let { factory.setUserAgent(it) }
        val mediaId = (++mediaSequence).toString()
        val item = MediaItem.Builder().setMediaId(mediaId).setUri(resolved.stream.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build()).build()
        mediaGeneration = current
        recovery.start(track, current, mediaId, resolved.finalStrategy)
        player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(item))
        player.prepare()
        player.play()
        mutableState.update { it.copy(currentTrack = track, isLoading = false, statusMessage = null) }
    }

    private suspend fun awaitSession() = suspendCancellableCoroutine<Unit> { continuation ->
        val attempt = connectionGeneration
        val connection = controller ?: MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(disconnected: MediaController) {
                    if (attempt == connectionGeneration) {
                        disconnect()
                        player.pause()
                        reportUnavailable("Playback service disconnected")
                    }
                }
            }).buildAsync().also { controller = it }
        connection.addListener({
            if (continuation.isActive) {
                try {
                    if (connection.get().isConnected) continuation.resume(Unit)
                    else continuation.resumeWithException(java.util.concurrent.ExecutionException(IllegalStateException("Disconnected")))
                }
                catch (error: java.util.concurrent.ExecutionException) { continuation.resumeWithException(error) }
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    fun setVisible(isVisible: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        visible = isVisible
        if (!isVisible) {
            if (job?.isActive == true) {
                generation++
                job?.cancel()
                reportUnavailable("Open Isle to start playback")
            }
            disconnect()
        }
    }

    private fun disconnect() {
        connectionGeneration++
        val old = controller
        controller = null
        if (old != null) MediaController.releaseFuture(old)
    }

    private fun reportUnavailable(message: String) {
        mutableState.update { it.copy(isLoading = false, statusMessage = message) }
    }

    private fun resumePlayback() {
        if (!visible) { reportUnavailable("Open Isle to start playback"); return }
        val current = ++generation
        job?.cancel()
        job = scope.launch {
            try {
                awaitSession()
                if (current == generation && visible) player.play()
            } catch (error: java.util.concurrent.ExecutionException) {
                if (current == generation) { disconnect(); reportUnavailable("Playback service unavailable") }
            }
        }
    }

    fun foregroundStartFailed() {
        player.pause()
        reportUnavailable("Background playback unavailable; open Isle and try again")
    }

    fun release() { check(Looper.myLooper() == Looper.getMainLooper()); job?.cancel(); scope.cancel(); disconnect(); player.release() }
}
