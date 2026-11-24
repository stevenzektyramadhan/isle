package io.github.stevenzektyramadhan.isle.player

import io.github.stevenzektyramadhan.isle.data.ExtractorRepository
import io.github.stevenzektyramadhan.isle.Timing
import io.github.stevenzektyramadhan.isle.data.ResolvedStream
import io.github.stevenzektyramadhan.isle.data.settings.SettingsStore
import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import io.github.stevenzektyramadhan.isle.domain.QueueManager
import io.github.stevenzektyramadhan.isle.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Collections

internal interface PlaybackOutput {
    suspend fun connect()
    fun install(track: TrackItem, stream: ResolvedStream, generation: Long)
    fun play(ready: Boolean)
    fun stop()
    fun seekTo(seconds: Long) = Unit
    fun setVolume(volume: Float) = Unit
}

/** Main-thread confined orchestration shared by app commands and media-session commands. */
internal class PlaybackCoordinator(
    private val extractor: ExtractorRepository,
    private val scope: CoroutineScope,
    private val output: PlaybackOutput,
    settings: SettingsStore? = null,
    private val timing: Timing = Timing.android,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val queue = QueueManager()
    private val preparation = NextTrackPreparation<ResolvedStream>(nowMillis)
    private var job: Job? = null
    private var prefetch: Job? = null
    private var playWhenReady = false
    private var intentVersion = 0L
    private var origin: QueueManager.Origin = QueueManager.Origin.Manual
    var generation = 0L
        private set
    private var installedGeneration = -1L
    private var retainedInstalledGeneration: Long? = null
    private var consumedErrorMediaId: String? = null
    private data class Activation(val track: TrackItem, val origin: QueueManager.Origin)
    private var pendingActivation: Activation? = null
    private var pendingInputGeneration: Long? = null
    private var pendingEndGeneration: Long? = null
    private val mutableState = MutableStateFlow(PlayerState())
    val state = mutableState.asStateFlow()
    private val preferences = settings?.let { store ->
        PlayerPreferences(store, scope, object : PlayerPreferences.Target {
            override fun restoreVolume(volume: Float) = applyVolume(volume)
            override fun restoreAutoplay(enabled: Boolean) = applyAutoplay(enabled)
            override fun preferencesFailed(message: String) { mutableState.update { it.copy(preferencesError = message) } }
        })
    }

    fun dispatch(command: PlayerCommand) {
        when (command) {
            is PlayerCommand.SubmitInput -> submit(command.input)
            PlayerCommand.Next -> advance(QueueManager.Origin.Manual)
            PlayerCommand.Previous -> {
                queue.resetErrors()
                if (queue.history.isNotEmpty()) {
                    val current = invalidate()
                    val track = queue.previous() ?: return
                    playWhenReady = true
                    launchActivation(track, QueueManager.Origin.Manual, current)
                }
            }
            PlayerCommand.Stop -> { invalidate(); queue.stop(); halt(null) }
            PlayerCommand.TogglePlayPause -> setPlaying(!playWhenReady)
            PlayerCommand.Play -> setPlaying(true)
            PlayerCommand.Pause -> setPlaying(false)
            PlayerCommand.ClearQueue -> {
                queue.clearQueue(); invalidatePreparation()
                if (queue.current == null && state.value.isLoading) { invalidate(); halt(null) }
                else publish(state.value.isLoading)
            }
            PlayerCommand.ToggleAutoplay -> {
                applyAutoplay(!state.value.autoplayEnabled)
                preferences?.autoplay(state.value.autoplayEnabled)
            }
            is PlayerCommand.SetVolume -> if (command.volume.isFinite()) {
                applyVolume(command.volume.coerceIn(0f, 1f))
                preferences?.volume(state.value.volume)
            }
            is PlayerCommand.SeekTo -> {
                val duration = state.value.durationSecs ?: state.value.currentTrack?.durationSecs
                if (acceptsInstalled(installedGeneration) && duration != null && duration > 0) {
                    val seconds = command.seconds.coerceIn(0, duration)
                    output.seekTo(seconds)
                    mutableState.update { it.copy(elapsedSecs = seconds) }
                }
            }
        }
    }

    private fun applyVolume(volume: Float) {
        mutableState.update { it.copy(volume = volume) }
        output.setVolume(volume)
    }

    private fun applyAutoplay(enabled: Boolean) {
        mutableState.update { it.copy(autoplayEnabled = enabled) }
        if (!enabled && queue.current == null && state.value.isLoading) { invalidate(); halt("Autoplay disabled") }
    }

    fun progress(positionSecs: Long, durationSecs: Long?) {
        if (!acceptsInstalled(installedGeneration)) return
        val duration = durationSecs?.takeIf { it > 0 } ?: state.value.currentTrack?.durationSecs?.takeIf { it > 0 }
        mutableState.update { it.copy(elapsedSecs = positionSecs.coerceIn(0, duration ?: Long.MAX_VALUE), durationSecs = duration) }
    }

    private fun submit(input: String) {
        val interrupted = pendingActivation
        val retained = installedGeneration.takeIf { acceptsInstalled(it) }
        val pendingEnd = pendingEndGeneration
        val current = invalidate()
        pendingActivation = interrupted
        retainedInstalledGeneration = retained
        pendingInputGeneration = current
        pendingEndGeneration = pendingEnd
        queue.resetErrors()
        val previousIntent = playWhenReady
        val submittedIntentVersion = intentVersion
        playWhenReady = true
        publish(true, "Searching…")
        job = scope.launch {
            try {
                val tracks = extractor.resolveInputs(input)
                if (current != generation) return@launch
                pendingInputGeneration = null
                pendingEndGeneration = null
                val track = queue.acceptInput(tracks) ?: return@launch
                publish(true)
                activate(track, QueueManager.Origin.Manual, current)
            } catch (error: ExtractorError) {
                if (current == generation) {
                    pendingInputGeneration = null
                    if (intentVersion == submittedIntentVersion) playWhenReady = previousIntent
                    if (interrupted != null && queue.current == interrupted.track) {
                        launchActivation(interrupted.track, interrupted.origin, current)
                        return@launch
                    }
                    publish(false, error.message); prepareNext()
                    pendingEndGeneration?.let { endedGeneration ->
                        pendingEndGeneration = null
                        ended(endedGeneration)
                    }
                }
            }
        }
    }

    private fun advance(nextOrigin: QueueManager.Origin) {
        val prepared = queue.queue.firstOrNull()?.let { track ->
            preparation.take(track).also {
                timing.prefetch(if (it == null) Timing.PrefetchOutcome.MISS else Timing.PrefetchOutcome.HIT)
            }
        }
        val current = invalidate()
        if (nextOrigin == QueueManager.Origin.Manual) playWhenReady = true
        val action = queue.advance(nextOrigin, state.value.autoplayEnabled)
        publish(true)
        job = scope.launch { handleAdvance(action, nextOrigin, current, prepared) }
    }

    private suspend fun handleAdvance(action: QueueManager.Advance, nextOrigin: QueueManager.Origin, current: Long, prepared: ResolvedStream? = null) {
        if (current != generation) return
        when (action) {
            is QueueManager.Advance.Play -> activate(action.track, action.origin, current, prepared)
            is QueueManager.Advance.Fetch -> {
                val result = try { QueueManager.Related.Tracks(timing.measure(Timing.Stage.RADIO_LOOKUP) { extractor.fetchRelated(action.anchor) }) }
                catch (_: ExtractorError.NoResults) { QueueManager.Related.NoResults }
                catch (_: ExtractorError) { QueueManager.Related.Failure }
                if (current != generation) return
                handleAdvance(queue.completeAutoplay(result, nextOrigin), nextOrigin, current)
            }
            QueueManager.Advance.Stopped -> halt(if (state.value.autoplayEnabled && queue.autoplayFetched) "Autoplay exhausted" else null)
            QueueManager.Advance.LookupFailed -> halt("Autoplay lookup failed")
            QueueManager.Advance.RepeatedErrors -> halt("Playback stopped: repeated errors")
        }
    }

    private fun launchActivation(track: TrackItem, nextOrigin: QueueManager.Origin, current: Long) {
        publish(true)
        job = scope.launch { activate(track, nextOrigin, current) }
    }

    private suspend fun activate(track: TrackItem, nextOrigin: QueueManager.Origin, current: Long, prepared: ResolvedStream? = null) {
        installedGeneration = -1L
        retainedInstalledGeneration = null
        output.stop()
        mutableState.update { it.copy(elapsedSecs = 0, durationSecs = track.durationSecs?.takeIf { seconds -> seconds > 0 }) }
        pendingActivation = Activation(track, nextOrigin)
        origin = nextOrigin
        publish(true)
        try {
            val stream = prepared ?: extractor.resolveStream(track)
            output.connect()
            if (current != generation) return
            installedGeneration = current
            output.install(track, stream, current)
            output.play(playWhenReady)
            pendingActivation = null
            publish(false)
            prepareNext()
        } catch (_: ExtractorError) {
            if (current == generation) activationFailed(current)
        } catch (_: java.util.concurrent.ExecutionException) {
            if (current == generation) halt("Playback service unavailable")
        }
    }

    fun ready(current: Long) {
        if (acceptsInstalled(current)) queue.activationSucceeded()
    }

    fun ended(current: Long) {
        if (!acceptsInstalled(current)) return
        if (pendingInputGeneration != null) pendingEndGeneration = current
        else if (!state.value.isLoading) {
            installedGeneration = -1L
            advance(QueueManager.Origin.Automatic())
        }
    }

    fun activationFailed(current: Long) {
        if (!(current == generation && pendingActivation != null) && !acceptsInstalled(current)) return
        pendingActivation = null
        queue.activationFailed()
        when (val activeOrigin = origin) {
            QueueManager.Origin.Manual -> halt("Playback failed")
            is QueueManager.Origin.Automatic -> advance(activeOrigin)
        }
    }

    fun playerError(current: Long, mediaId: String?, recoveryTrack: () -> TrackItem?) {
        if (!acceptsInstalled(current) || mediaId == null || mediaId == consumedErrorMediaId) return
        consumedErrorMediaId = mediaId
        val track = recoveryTrack()
        if (track == null) activationFailed(current) else retry(track, current)
    }

    fun retry(track: TrackItem, current: Long) {
        if (!acceptsInstalled(current)) return
        val retryGeneration = generation
        job?.cancel()
        pendingActivation = Activation(track, origin)
        publish(true, "Retrying playback…")
        job = scope.launch {
            try {
                val stream = extractor.resolveStream(track, finalOnly = true)
                if (retryGeneration != generation) return@launch
                installedGeneration = retryGeneration
                output.install(track, stream, retryGeneration)
                output.play(playWhenReady)
                pendingActivation = null
                publish(false)
            } catch (_: ExtractorError) { if (retryGeneration == generation) activationFailed(retryGeneration) }
        }
    }

    fun playingChanged(playing: Boolean) { mutableState.update { it.copy(isPlaying = playing) }; prepareNext() }
    fun playIntentChanged(ready: Boolean) { intentVersion++; playWhenReady = ready }
    fun availability(available: Boolean) { mutableState.update { it.copy(ytdlpAvailable = available, statusMessage = if (available) it.statusMessage else "yt-dlp is unavailable") } }
    fun noteStatus(message: String) { mutableState.update { if (!it.isLoading && it.statusMessage == null) it.copy(statusMessage = message) else it } }
    fun unavailable(message: String) { invalidate(); halt(message) }

    private fun setPlaying(ready: Boolean) {
        intentVersion++
        playWhenReady = ready
        if (!ready) output.play(false)
        else if (!state.value.isLoading) {
            val current = generation
            job = scope.launch {
                try { output.connect(); if (current == generation && playWhenReady) output.play(true) }
                catch (_: java.util.concurrent.ExecutionException) { if (current == generation) unavailable("Playback service unavailable") }
            }
        }
    }

    private fun prepareNext() {
        if (!state.value.isPlaying || state.value.isLoading) return
        val attempt = preparation.prepare(queue.queue.firstOrNull(), state.value.isPlaying && !state.value.isLoading) ?: return
        prefetch?.cancel()
        prefetch = scope.launch {
            try { preparation.complete(attempt, extractor.resolveStream(attempt.track)) }
            catch (_: ExtractorError) { preparation.fail(attempt); timing.prefetch(Timing.PrefetchOutcome.FAILURE) }
        }
    }

    private fun invalidatePreparation() { prefetch?.cancel(); preparation.invalidate() }
    private fun acceptsInstalled(current: Long) = current >= 0 && current == installedGeneration && (current == generation || current == retainedInstalledGeneration)
    private fun invalidate(): Long { generation++; retainedInstalledGeneration = null; pendingActivation = null; pendingInputGeneration = null; pendingEndGeneration = null; job?.cancel(); invalidatePreparation(); return generation }
    private fun halt(message: String?) { installedGeneration = -1L; retainedInstalledGeneration = null; pendingActivation = null; playWhenReady = false; output.stop(); mutableState.update { it.copy(isPlaying = false, elapsedSecs = 0, durationSecs = null) }; publish(false, message) }
    private fun publish(loading: Boolean, message: String? = null) {
        mutableState.update { it.copy(currentTrack = queue.current, queue = Collections.unmodifiableList(queue.queue), history = Collections.unmodifiableList(queue.history), autoplayAnchor = queue.anchor, autoplayFetched = queue.autoplayFetched, isLoading = loading, statusMessage = message) }
    }
    fun release() { invalidate(); preferences?.close() }
}
