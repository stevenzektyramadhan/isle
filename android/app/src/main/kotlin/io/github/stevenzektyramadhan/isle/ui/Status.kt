package io.github.stevenzektyramadhan.isle.ui

import android.content.Context
import io.github.stevenzektyramadhan.isle.R

enum class StatusSeverity { Error, Info }
enum class StatusAction { Dismiss, Retry, Settings }

fun mapStatus(message: String): UiStatus = when (message) {
    "yt-dlp is unavailable" -> UiStatus(StatusSeverity.Error, R.string.status_engine_not_ready_title, R.string.status_engine_not_ready_message, setOf(StatusAction.Settings))
    "Request timed out" -> UiStatus(StatusSeverity.Error, R.string.status_timeout_title, R.string.status_timeout_message, setOf(StatusAction.Dismiss, StatusAction.Retry))
    "No tracks found" -> UiStatus(StatusSeverity.Info, R.string.status_no_tracks_title, R.string.status_no_tracks_message, setOf(StatusAction.Dismiss))
    "This input is not supported yet" -> UiStatus(StatusSeverity.Info, R.string.status_unsupported_title, R.string.status_unsupported_message, setOf(StatusAction.Dismiss))
    "Private network addresses are not allowed" -> UiStatus(StatusSeverity.Error, R.string.status_private_link_title, R.string.status_private_link_message, setOf(StatusAction.Dismiss))
    "Extraction failed", "Stream failed", "Playback failed", "Playback service unavailable", "Autoplay lookup failed", "Playback stopped: repeated errors" -> UiStatus(StatusSeverity.Error, R.string.status_playback_failed_title, R.string.status_playback_failed_message, setOf(StatusAction.Dismiss, StatusAction.Retry))
    "Autoplay disabled", "Autoplay exhausted" -> UiStatus(StatusSeverity.Info, R.string.status_autoplay_stopped_title, R.string.status_autoplay_stopped_message, setOf(StatusAction.Dismiss))
    "yt-dlp update failed" -> UiStatus(StatusSeverity.Error, R.string.status_engine_update_failed_title, R.string.status_engine_update_failed_message, setOf(StatusAction.Dismiss))
    else -> UiStatus(StatusSeverity.Error, R.string.status_generic_title, null, setOf(StatusAction.Dismiss), detail = message)
}

data class UiStatus(val severity: StatusSeverity, val titleRes: Int, val messageRes: Int?, val actions: Set<StatusAction>, val detail: String? = null) {
    fun title(context: Context) = context.getString(titleRes)
    fun message(context: Context) = messageRes?.let(context::getString) ?: detail.orEmpty()
}
