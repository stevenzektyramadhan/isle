package io.github.stevenzektyramadhan.isle.ui

import android.content.Context
import io.github.stevenzektyramadhan.isle.R
import io.github.stevenzektyramadhan.isle.model.PlayerStatus
import io.github.stevenzektyramadhan.isle.model.PlayerStatusCode
import io.github.stevenzektyramadhan.isle.model.classifyPlayerStatus

enum class StatusSeverity { Error, Info }
enum class StatusAction { Dismiss, Retry, Settings }

fun mapStatus(message: String): UiStatus = mapStatus(classifyPlayerStatus(message))

fun mapStatus(status: PlayerStatus): UiStatus = when (status.code) {
    PlayerStatusCode.YTDLP_UNAVAILABLE -> UiStatus(StatusSeverity.Error, R.string.status_engine_not_ready_title, R.string.status_engine_not_ready_message, setOf(StatusAction.Settings))
    PlayerStatusCode.REQUEST_TIMED_OUT -> UiStatus(StatusSeverity.Error, R.string.status_timeout_title, R.string.status_timeout_message, setOf(StatusAction.Dismiss, StatusAction.Retry))
    PlayerStatusCode.NO_TRACKS_FOUND -> UiStatus(StatusSeverity.Info, R.string.status_no_tracks_title, R.string.status_no_tracks_message, setOf(StatusAction.Dismiss))
    PlayerStatusCode.UNSUPPORTED_INPUT -> UiStatus(StatusSeverity.Info, R.string.status_unsupported_title, R.string.status_unsupported_message, setOf(StatusAction.Dismiss))
    PlayerStatusCode.PRIVATE_NETWORK_BLOCKED -> UiStatus(StatusSeverity.Error, R.string.status_private_link_title, R.string.status_private_link_message, setOf(StatusAction.Dismiss))
    PlayerStatusCode.PLAYBACK_FAILED -> UiStatus(StatusSeverity.Error, R.string.status_playback_failed_title, R.string.status_playback_failed_message, setOf(StatusAction.Dismiss, StatusAction.Retry))
    PlayerStatusCode.BACKGROUND_REQUIRED -> UiStatus(StatusSeverity.Error, R.string.status_background_required_title, R.string.status_background_required_message, setOf(StatusAction.Dismiss, StatusAction.Retry))
    PlayerStatusCode.AUTOPLAY_STOPPED -> UiStatus(StatusSeverity.Info, R.string.status_autoplay_stopped_title, R.string.status_autoplay_stopped_message, setOf(StatusAction.Dismiss))
    PlayerStatusCode.YTDLP_UPDATE_FAILED -> UiStatus(StatusSeverity.Error, R.string.status_engine_update_failed_title, R.string.status_engine_update_failed_message, setOf(StatusAction.Dismiss))
    PlayerStatusCode.UNKNOWN -> UiStatus(StatusSeverity.Error, R.string.status_generic_title, null, setOf(StatusAction.Dismiss), detail = status.message)
}

data class UiStatus(val severity: StatusSeverity, val titleRes: Int, val messageRes: Int?, val actions: Set<StatusAction>, val detail: String? = null) {
    fun title(context: Context) = context.getString(titleRes)
    fun message(context: Context) = messageRes?.let(context::getString) ?: detail.orEmpty()
}
