package io.github.stevenzektyramadhan.isle

import io.github.stevenzektyramadhan.isle.domain.QueueManager
import io.github.stevenzektyramadhan.isle.domain.QueueManager.Advance
import io.github.stevenzektyramadhan.isle.domain.QueueManager.Origin
import io.github.stevenzektyramadhan.isle.domain.QueueManager.Related
import io.github.stevenzektyramadhan.isle.model.TrackItem
import org.junit.Assert.*
import org.junit.Test

class QueueManagerTest {
    private fun track(id: String, title: String = id) = TrackItem(id, title, null, "https://example.com/$id", null)

    @Test fun advanceAndPreviousPreserveOrderAndEmptyAdvanceMovesCurrentToHistory() {
        val q = QueueManager()
        val (a, b, c) = listOf(track("a"), track("b"), track("c"))
        q.acceptInput(listOf(a, b, c))
        assertEquals(Advance.Play(b, Origin.Manual), q.advance(Origin.Manual, false))
        assertEquals(a, q.previous())
        assertEquals(listOf(b, c), q.queue)
        assertEquals(Advance.Play(b, Origin.Manual), q.advance(Origin.Manual, false))
        assertEquals(Advance.Play(c, Origin.Manual), q.advance(Origin.Manual, false))
        assertEquals(Advance.Stopped, q.advance(Origin.Manual, false))
        assertNull(q.current)
        assertEquals(c, q.previous())
    }

    @Test fun historyIsBoundedButAttemptsSurviveEviction() {
        val q = QueueManager()
        val tracks = (0..201).map { track("$it") }
        q.acceptInput(tracks)
        repeat(201) { q.advance(Origin.Manual, false) }
        assertEquals(200, q.history.size)
        assertEquals(tracks[1], q.history.first())
        assertTrue(q.attemptedIds.contains("0"))
    }

    @Test fun successfulInputReplacesSessionButEmptyInputDoesNot() {
        val q = QueueManager()
        val a = track("a")
        val b = track("b")
        q.acceptInput(listOf(a))
        q.advance(Origin.Manual, true)
        q.completeAutoplay(Related.NoResults, Origin.Manual)
        q.acceptInput(emptyList())
        assertEquals(a, q.anchor)
        assertTrue(q.autoplayFetched)
        q.acceptInput(listOf(b))
        assertEquals(b, q.anchor)
        assertEquals(setOf("b"), q.attemptedIds)
        assertFalse(q.autoplayFetched)
    }

    @Test fun autoplayUsesAnchorAndFiltersAttemptsByIdOrCanonicalTitle() {
        val q = QueueManager()
        val a = track("a", "Artist - Song (Official)")
        val b = track("b", "Other")
        val fresh = track("fresh", "Fresh")
        q.acceptInput(listOf(a, b))
        q.advance(Origin.Manual, true)
        assertEquals(Advance.Fetch(a), q.advance(Origin.Automatic(), true))
        assertEquals(Advance.Play(fresh, Origin.Automatic(true)), q.completeAutoplay(
            Related.Tracks(listOf(track("a", "Different"), track("x", "Artist Song [Lyrics]"), fresh)), Origin.Automatic()
        ))
        assertTrue(q.autoplayFetched)
        assertTrue(q.queue.isEmpty())
    }

    @Test fun lookupFailureCanRetryButNoResultsCannot() {
        val q = QueueManager()
        val a = track("a")
        q.acceptInput(listOf(a))
        assertEquals(Advance.Fetch(a), q.advance(Origin.Manual, true))
        assertEquals(Advance.LookupFailed, q.completeAutoplay(Related.Failure, Origin.Manual))
        assertEquals(Advance.Fetch(a), q.advance(Origin.Manual, true))
        assertEquals(Advance.Stopped, q.completeAutoplay(Related.NoResults, Origin.Manual))
        assertEquals(Advance.Stopped, q.advance(Origin.Manual, true))
    }

    @Test fun automaticFailuresStopAtThreeAndManualActionsResetCounter() {
        val q = QueueManager()
        q.acceptInput((0..4).map { track("$it") })
        repeat(2) {
            q.activationFailed()
            assertTrue(q.advance(Origin.Automatic(), false) is Advance.Play)
        }
        q.activationFailed()
        assertEquals(Advance.RepeatedErrors, q.advance(Origin.Automatic(), false))
        assertEquals("2", q.current?.id)
        assertEquals(Advance.Play(track("3"), Origin.Manual), q.advance(Origin.Manual, false))
        assertEquals(0, q.consecutiveErrors)
        q.activationFailed()
        q.activationSucceeded()
        assertEquals(0, q.consecutiveErrors)
    }

    @Test fun failedActivationsRemainInHistory() {
        val q = QueueManager()
        val (a, b, c) = listOf(track("a"), track("b"), track("c"))
        q.acceptInput(listOf(a, b))
        q.activationFailed()
        q.advance(Origin.Automatic(), false)
        // Rust src/audio.rs advance_queue and pop_previous retain attempted tracks.
        assertEquals(listOf(a), q.history)
        assertEquals(a, q.previous())
        assertEquals(listOf(b), q.queue)
        q.activationFailed()
        q.acceptInput(listOf(c))
        assertEquals(listOf(a), q.history)
    }
}
