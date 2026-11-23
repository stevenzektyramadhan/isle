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
import io.github.stevenzektyramadhan.isle.Timing
import io.github.stevenzektyramadhan.isle.data.ExtractorRepository
import io.github.stevenzektyramadhan.isle.data.ResolvedStream
import io.github.stevenzektyramadhan.isle.data.settings.DataStoreSettings
import io.github.stevenzektyramadhan.isle.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerEngine internal constructor(private val context: Context) {
    init { check(Looper.myLooper() == Looper.getMainLooper()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val extractor = ExtractorRepository(context, scope)
    private val player = ExoPlayer.Builder(context)
        .setLooper(Looper.getMainLooper())
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()
    val sessionPlayer: Player = SessionPlayer(player, ::dispatch)
    private var controller: ListenableFuture<MediaController>? = null
    private var connectionGeneration = 0L
    private var visible = false
    private var mediaGeneration = 0L
    private var mediaSequence = 0L
    private var readyStarted: Long? = null
    private var progressJob: Job? = null
    private val recovery = PlaybackRecovery()
    private val coordinator: PlaybackCoordinator = PlaybackCoordinator(extractor, scope, object : PlaybackOutput {
        override suspend fun connect() {
            Timing.android.measure(Timing.Stage.SERVICE_CONNECTION) {
                if (controller == null && !visible) throw java.util.concurrent.ExecutionException(IllegalStateException("Foreground required"))
                awaitSession()
            }
        }
        override fun install(track: TrackItem, stream: ResolvedStream, generation: Long) = installStream(track, stream, generation)
        override fun play(ready: Boolean) { android.util.Log.i("IsleTrace", "output play=$ready"); player.playWhenReady = ready }
        override fun stop() { android.util.Log.i("IsleTrace", "output stop"); finishReadyTiming(Timing.Outcome.CANCELLED); player.stop(); player.clearMediaItems() }
        override fun seekTo(seconds: Long) { player.seekTo(seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000) }
        override fun setVolume(volume: Float) { player.volume = volume }
    }, settings = DataStoreSettings(context))
    val state: StateFlow<PlayerState> = coordinator.state
    fun noteStatus(message: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        coordinator.noteStatus(message)
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                coordinator.playingChanged(isPlaying)
                progressJob?.cancel()
                updateProgress()
                if (isPlaying) progressJob = scope.launch {
                    while (isActive) { delay(500); updateProgress() }
                }
            }
            override fun onEvents(player: Player, events: Player.Events) { updateProgress() }
            override fun onVolumeChanged(volume: Float) {
                if (volume != state.value.volume) dispatch(PlayerCommand.SetVolume(volume))
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                android.util.Log.i("IsleTrace", "intent=$playWhenReady reason=$reason")
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) coordinator.playIntentChanged(playWhenReady)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                android.util.Log.i("IsleTrace", "state=$playbackState")
                if (playbackState == Player.STATE_READY) { finishReadyTiming(Timing.Outcome.SUCCESS); recovery.ready(player.currentMediaItem?.mediaId); coordinator.ready(mediaGeneration) }
                if (playbackState == Player.STATE_ENDED) coordinator.ended(mediaGeneration)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player.playerError !== error) return
                finishReadyTiming(Timing.Outcome.ERROR)
                val status = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val mediaId = player.currentMediaItem?.mediaId
                coordinator.playerError(mediaGeneration, mediaId) { recovery.retry(mediaGeneration, mediaId, status) }
            }
        })
        scope.launch { coordinator.availability(extractor.isAvailable()) }
    }

    private fun updateProgress() {
        coordinator.progress(player.currentPosition / 1000, player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000))
    }

    fun dispatch(command: PlayerCommand) {
        android.util.Log.i("IsleTrace", "command=${command::class.simpleName}")
        check(Looper.myLooper() == Looper.getMainLooper())
        coordinator.dispatch(command)
        if (command == PlayerCommand.Stop) disconnect()
    }

    private fun installStream(track: TrackItem, resolved: ResolvedStream, current: Long) {
        finishReadyTiming(Timing.Outcome.CANCELLED)
        val factory = DefaultHttpDataSource.Factory()
        resolved.stream.userAgent?.let { factory.setUserAgent(it) }
        val mediaId = (++mediaSequence).toString()
        val item = MediaItem.Builder().setMediaId(mediaId).setUri(resolved.stream.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build()).build()
        mediaGeneration = current
        recovery.start(track, current, mediaId, resolved.finalStrategy)
        player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(item))
        readyStarted = Timing.android.start()
        player.prepare()
    }

    private fun finishReadyTiming(outcome: Timing.Outcome) {
        readyStarted?.let { Timing.android.record(Timing.Stage.PLAYER_READY, outcome, it) }
        readyStarted = null
    }

    private suspend fun awaitSession() = suspendCancellableCoroutine<Unit> { continuation ->
        val attempt = connectionGeneration
        val connection = controller ?: MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(disconnected: MediaController) {
                    if (attempt == connectionGeneration) {
                        disconnect()
                        player.pause()
                        coordinator.unavailable("Playback service disconnected")
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
    }

    private fun disconnect() {
        connectionGeneration++
        val old = controller
        controller = null
        if (old != null) MediaController.releaseFuture(old)
    }

    fun foregroundStartFailed() {
        check(Looper.myLooper() == Looper.getMainLooper())
        coordinator.unavailable("Background playback unavailable; open Isle and try again")
    }

    fun release() { check(Looper.myLooper() == Looper.getMainLooper()); finishReadyTiming(Timing.Outcome.CANCELLED); coordinator.release(); scope.cancel(); disconnect(); player.release() }
}
