package io.github.stevenzektyramadhan.isle.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.*
import androidx.media3.common.text.CueGroup
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.stevenzektyramadhan.isle.model.PlayerCommand

@UnstableApi
class SessionPlayer(player: Player, private val dispatch: (PlayerCommand) -> Unit) : ForwardingPlayer(player) {
    private val listeners = mutableMapOf<Player.Listener, Player.Listener>()
    override fun addListener(listener: Player.Listener) {
        if (listeners.containsKey(listener)) return
        val forwarding = object : Player.Listener by listener {
            override fun onEvents(player: Player, events: Player.Events) = listener.onEvents(player, events)
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = listener.onTimelineChanged(timeline, reason)
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = listener.onMediaItemTransition(mediaItem, reason)
            override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = listener.onMediaMetadataChanged(mediaMetadata)
            override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = listener.onPlaylistMetadataChanged(mediaMetadata)
            override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)
            override fun onAvailableCommandsChanged(commands: Player.Commands) = listener.onAvailableCommandsChanged(availableCommands)
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = listener.onTrackSelectionParametersChanged(parameters)
            override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(playbackState)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayWhenReadyChanged(playWhenReady, reason)
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
            override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
            override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)
            override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = listener.onPlaybackParametersChanged(playbackParameters)
            override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = listener.onSeekBackIncrementChanged(seekBackIncrementMs)
            override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)
            override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
            override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
            override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = listener.onAudioAttributesChanged(audioAttributes)
            override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)
            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)
            override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)
            override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)
            override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()
            override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)
            override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)
        }
        listeners[listener] = forwarding
        super.addListener(forwarding)
    }
    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }
    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon().addAll(
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    ).build()
    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    override fun play() = dispatch(PlayerCommand.Play)
    override fun pause() = dispatch(PlayerCommand.Pause)
    override fun setPlayWhenReady(playWhenReady: Boolean) = dispatch(if (playWhenReady) PlayerCommand.Play else PlayerCommand.Pause)
    override fun seekToNext() = dispatch(PlayerCommand.Next)
    override fun seekToNextMediaItem() = dispatch(PlayerCommand.Next)
    override fun seekToPrevious() = dispatch(PlayerCommand.Previous)
    override fun seekToPreviousMediaItem() = dispatch(PlayerCommand.Previous)
    override fun stop() = dispatch(PlayerCommand.Stop)
}
