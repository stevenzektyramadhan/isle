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
import io.github.stevenzektyramadhan.isle.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
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
    private var mediaGeneration = 0L
    private var mediaSequence = 0L
    private var readyStarted: Long? = null
    private val recovery = PlaybackRecovery()
    private val coordinator: PlaybackCoordinator = PlaybackCoordinator(extractor, scope, object : PlaybackOutput {
        override suspend fun connect() {
            Timing.android.measure(Timing.Stage.SERVICE_CONNECTION) {
                if (controller == null && !visible) throw java.util.concurrent.ExecutionException(IllegalStateException("Foreground required"))
                awaitSession()
            }
        }
        override fun install(track: TrackItem, stream: ResolvedStream, generation: Long) = installStream(track, stream, generation)
        override fun play(ready: Boolean) { player.playWhenReady = ready }
        override fun stop() { finishReadyTiming(Timing.Outcome.CANCELLED); player.stop(); player.clearMediaItems() }
    })
    val state: StateFlow<PlayerState> = coordinator.state
    fun noteStatus(message: String) = coordinator.noteStatus(message)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { coordinator.playingChanged(isPlaying) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) coordinator.playIntentChanged(playWhenReady)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) { finishReadyTiming(Timing.Outcome.SUCCESS); recovery.ready(player.currentMediaItem?.mediaId); coordinator.ready(mediaGeneration) }
                if (playbackState == Player.STATE_ENDED) coordinator.ended(mediaGeneration)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player.playerError !== error) return
                finishReadyTiming(Timing.Outcome.ERROR)
                val status = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val track = recovery.retry(mediaGeneration, player.currentMediaItem?.mediaId, status)
                if (track == null) coordinator.activationFailed(mediaGeneration)
                else coordinator.retry(track, mediaGeneration)
            }
        })
        scope.launch { coordinator.availability(extractor.isAvailable()) }
    }

    fun dispatch(command: PlayerCommand) {
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
        coordinator.unavailable("Background playback unavailable; open Isle and try again")
    }

    fun release() { check(Looper.myLooper() == Looper.getMainLooper()); finishReadyTiming(Timing.Outcome.CANCELLED); coordinator.release(); scope.cancel(); disconnect(); player.release() }
}
