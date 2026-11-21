package io.github.stevenzektyramadhan.isle.model

data class TrackItem(val id: String, val title: String, val artist: String?, val originalUrl: String, val durationSecs: Long?)
data class StreamInfo(val url: String, val userAgent: String?)
data class PlayerState(
    val currentTrack: TrackItem? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val ytdlpAvailable: Boolean? = null,
    val statusMessage: String? = null,
    val queue: List<TrackItem> = emptyList(),
    val history: List<TrackItem> = emptyList(),
    val autoplayEnabled: Boolean = true,
    val autoplayAnchor: TrackItem? = null,
    val autoplayFetched: Boolean = false,
)

sealed interface PlayerCommand {
    data class SubmitInput(val input: String) : PlayerCommand
    data object TogglePlayPause : PlayerCommand
    data object Stop : PlayerCommand
    data object Next : PlayerCommand
    data object Previous : PlayerCommand
    data object Play : PlayerCommand
    data object Pause : PlayerCommand
    data object ClearQueue : PlayerCommand
    data object ToggleAutoplay : PlayerCommand
}
