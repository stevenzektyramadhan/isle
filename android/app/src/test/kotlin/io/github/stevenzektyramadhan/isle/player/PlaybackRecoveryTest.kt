package io.github.stevenzektyramadhan.isle.player

import io.github.stevenzektyramadhan.isle.model.TrackItem
import io.github.stevenzektyramadhan.isle.data.ExtractorRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRecoveryTest {
    private val track = TrackItem("one", "One", null, "https://example.com/one", null)

    @Test fun android403UsesAlternateAndAlternate403EndsRecovery() = runBlocking {
        val clients = mutableListOf<String>()
        val repository = ExtractorRepository { _, args ->
            val client = args.last().substringAfter("player_client=")
            clients.add(client)
            """{"url":"https://8.8.8.8/$client"}"""
        }
        val recovery = PlaybackRecovery()
        val first = repository.resolveStream(track)
        recovery.start(track, 1, "first", first.finalStrategy)
        val retry = recovery.retry(1, "first", 403)
        assertEquals(track, retry)
        assertNull(recovery.retry(1, "first", 403))
        val alternate = repository.resolveStream(requireNotNull(retry), finalOnly = true)
        recovery.start(track, 1, "alternate", alternate.finalStrategy)
        assertNull(recovery.retry(1, "first", 403))
        assertNull(recovery.retry(1, "alternate", 403))
        assertEquals("https://8.8.8.8/visionos,default", alternate.stream.url)
        assertEquals(listOf("android,web", "visionos,default"), clients)
    }

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
