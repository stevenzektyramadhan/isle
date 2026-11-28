package io.github.stevenzektyramadhan.isle.model

enum class PlayerStatusCode {
    YTDLP_UNAVAILABLE,
    REQUEST_TIMED_OUT,
    NO_TRACKS_FOUND,
    UNSUPPORTED_INPUT,
    PRIVATE_NETWORK_BLOCKED,
    PLAYBACK_FAILED,
    BACKGROUND_REQUIRED,
    AUTOPLAY_STOPPED,
    YTDLP_UPDATE_FAILED,
    UNKNOWN,
}

data class PlayerStatus(val code: PlayerStatusCode, val message: String)

fun classifyPlayerStatus(message: String): PlayerStatus {
    val code = when (message) {
        "yt-dlp is unavailable" -> PlayerStatusCode.YTDLP_UNAVAILABLE
        "Request timed out" -> PlayerStatusCode.REQUEST_TIMED_OUT
        "No tracks found" -> PlayerStatusCode.NO_TRACKS_FOUND
        "This input is not supported yet" -> PlayerStatusCode.UNSUPPORTED_INPUT
        "Private network addresses are not allowed" -> PlayerStatusCode.PRIVATE_NETWORK_BLOCKED
        "Extraction failed", "Stream failed", "Playback failed", "Playback service unavailable", "Playback service disconnected", "Autoplay lookup failed", "Playback stopped: repeated errors" -> PlayerStatusCode.PLAYBACK_FAILED
        "Background playback unavailable; open Isle and try again" -> PlayerStatusCode.BACKGROUND_REQUIRED
        "Autoplay disabled", "Autoplay exhausted" -> PlayerStatusCode.AUTOPLAY_STOPPED
        "yt-dlp update failed" -> PlayerStatusCode.YTDLP_UPDATE_FAILED
        else -> PlayerStatusCode.UNKNOWN
    }
    return PlayerStatus(code, message)
}

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
    val volume: Float = 1f,
    val elapsedSecs: Long = 0,
    val durationSecs: Long? = null,
    val preferencesError: String? = null,
) {
    val status: PlayerStatus? get() = statusMessage?.let(::classifyPlayerStatus)
}

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
    data class SetVolume(val volume: Float) : PlayerCommand
    data class SeekTo(val seconds: Long) : PlayerCommand
}
