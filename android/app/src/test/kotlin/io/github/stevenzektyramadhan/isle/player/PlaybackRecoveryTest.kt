package io.github.stevenzektyramadhan.isle.player

import io.github.stevenzektyramadhan.isle.model.TrackItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRecoveryTest {
    private val track = TrackItem("one", "One", null, "https://example.com/one", null)

    @Test fun initial403RetriesTheSameTrackOnlyOnce() {
        val recovery = PlaybackRecovery()
        recovery.start(track, 1, "item-1", false)
        assertEquals(track, recovery.retry(1, "item-1", 403))
        assertNull(recovery.retry(1, "item-1", 403))
    }

    @Test fun finalStrategyAndNon403DoNotRetry() {
        val recovery = PlaybackRecovery()
        recovery.start(track, 1, "item-1", true)
        assertNull(recovery.retry(1, "item-1", 403))
        recovery.start(track, 2, "item-2", false)
        assertNull(recovery.retry(2, "item-2", 500))
        assertEquals(track, recovery.retry(2, "item-2", 403))
    }

    @Test fun playbackAfterReadyDoesNotRetry() {
        val recovery = PlaybackRecovery()
        recovery.start(track, 1, "item-1", false)
        recovery.ready("item-1")
        assertNull(recovery.retry(1, "item-1", 403))
    }

    @Test fun staleGenerationOrMediaItemCannotRetry() {
        val recovery = PlaybackRecovery()
        recovery.start(track, 2, "item-2", false)
        assertNull(recovery.retry(1, "item-2", 403))
        assertNull(recovery.retry(2, "item-1", 403))
        assertEquals(track, recovery.retry(2, "item-2", 403))
    }

    @Test fun staleReadyDoesNotDisableCurrentRetry() {
        val recovery = PlaybackRecovery()
        recovery.start(track, 2, "item-2", false)
        recovery.ready("item-1")
        assertEquals(track, recovery.retry(2, "item-2", 403))
    }
}
