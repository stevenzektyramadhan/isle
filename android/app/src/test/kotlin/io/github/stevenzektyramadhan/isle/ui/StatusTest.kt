package io.github.stevenzektyramadhan.isle.ui

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
            "Autoplay lookup failed", "Playback stopped: repeated errors", "Autoplay disabled",
            "Autoplay exhausted", "yt-dlp update failed",
        )
        messages.forEach { assertNull("$it should not become fallback detail", mapStatus(it).detail) }
        assertEquals(setOf(StatusAction.Settings), mapStatus(messages[0]).actions)
        assertEquals(setOf(StatusAction.Dismiss, StatusAction.Retry), mapStatus(messages[1]).actions)
        assertEquals(StatusSeverity.Info, mapStatus(messages[2]).severity)
    }

    @Test
    fun keepsUnknownStatusAsDetail() {
        val status = mapStatus("Unexpected failure")
        assertEquals(StatusSeverity.Error, status.severity)
        assertNull(status.messageRes)
        assertEquals("Unexpected failure", status.detail)
        assertEquals(setOf(StatusAction.Dismiss), status.actions)
    }
}
