package io.github.stevenzektyramadhan.isle.ui

import io.github.stevenzektyramadhan.isle.model.PlayerState
import io.github.stevenzektyramadhan.isle.model.PlayerStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusTest {
    @Test
    fun mapsEveryEngineStatus() {
        val messages = listOf(
            "yt-dlp is unavailable", "Request timed out", "No tracks found",
            "This input is not supported yet", "Private network addresses are not allowed",
            "Extraction failed", "Stream failed", "Playback failed", "Playback service unavailable",
            "Playback service disconnected", "Background playback unavailable; open Isle and try again",
            "Autoplay lookup failed", "Playback stopped: repeated errors", "Autoplay disabled",
            "Autoplay exhausted", "yt-dlp update failed",
        )
        messages.forEach { assertNull("$it should not become fallback detail", mapStatus(it).detail) }
        assertEquals(setOf(StatusAction.Settings), mapStatus(messages[0]).actions)
        assertEquals(setOf(StatusAction.Dismiss, StatusAction.Retry), mapStatus(messages[1]).actions)
        assertEquals(StatusSeverity.Info, mapStatus(messages[2]).severity)
        assertEquals(setOf(StatusAction.Dismiss, StatusAction.Retry), mapStatus("Background playback unavailable; open Isle and try again").actions)
    }

    @Test
    fun keepsUnknownStatusAsDetail() {
        val status = mapStatus("Unexpected failure")
        assertEquals(StatusSeverity.Error, status.severity)
        assertNull(status.messageRes)
        assertEquals("Unexpected failure", status.detail)
        assertEquals(setOf(StatusAction.Dismiss), status.actions)
    }

    @Test
    fun playerStateExposesStructuredStatus() {
        assertNull(PlayerState().status)
        val timeoutState = PlayerState(statusMessage = "Request timed out")
        assertEquals(PlayerStatusCode.REQUEST_TIMED_OUT, timeoutState.status?.code)
        assertEquals(StatusAction.Retry in mapStatus(timeoutState.status!!).actions, true)
    }
}
