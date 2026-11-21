package io.github.stevenzektyramadhan.isle.player

import io.github.stevenzektyramadhan.isle.model.TrackItem
import org.junit.Assert.*
import org.junit.Test

class NextTrackPreparationTest {
    private val track = TrackItem("one", "One", "Artist", "https://example.com/one", 100)
    private var now = 0L
    private val policy = NextTrackPreparation<String> { now }

    @Test fun startsOnlyWhenPlayingAndNextTrackExists() {
        assertNull(policy.prepare(null, true))
        assertNull(policy.prepare(track, false))
        assertNotNull(policy.prepare(track, true))
        assertNull(policy.prepare(track, true))
    }

    @Test fun completedValueIsConsumedOnlyForMatchingFullTrack() {
        val attempt = policy.prepare(track, true)!!
        policy.complete(attempt, "stream")
        assertNull(policy.take(track.copy(title = "Different")))
        assertNull(policy.take(track.copy(artist = "Different")))
        assertNull(policy.take(track.copy(originalUrl = "https://example.com/other")))
        assertNull(policy.take(track.copy(durationSecs = 101)))
        assertNull(policy.take(track.copy(id = "other")))
        assertEquals("stream", policy.take(track.copy()))
        assertNull(policy.take(track))
    }

    @Test fun freshnessIsMeasuredFromStartAndExpiresAtSixtySeconds() {
        val attempt = policy.prepare(track, true)!!
        now = 50_000
        policy.complete(attempt, "stream")
        now = 59_999
        assertEquals("stream", policy.take(track))

        policy.invalidate()
        now = 100_000
        val next = policy.prepare(track, true)!!
        policy.complete(next, "expired")
        now = 160_000
        assertNull(policy.take(track))
    }

    @Test fun invalidationAndReplacementRejectLateCompletions() {
        val old = policy.prepare(track, true)!!
        policy.invalidate()
        val current = policy.prepare(track, true)!!
        policy.complete(old, "stale")
        assertNull(policy.take(track))
        policy.complete(current, "current")
        assertEquals("current", policy.take(track))
    }

    @Test fun changedEligibilityInvalidatesInFlightWork() {
        val old = policy.prepare(track, true)!!
        assertNull(policy.prepare(null, true))
        policy.complete(old, "stale")
        assertNull(policy.take(track))
        val next = track.copy(id = "two")
        val pending = policy.prepare(track, true)!!
        val replacement = policy.prepare(next, true)!!
        policy.complete(pending, "stale")
        policy.complete(replacement, "next")
        assertEquals("next", policy.take(next))
    }

    @Test fun failureDoesNotRetryUntilInvalidatedOrTrackChanges() {
        val attempt = policy.prepare(track, true)!!
        policy.fail(attempt)
        assertNull(policy.prepare(track, true))
        assertNull(policy.take(track))
        val replacement = policy.prepare(track.copy(id = "two"), true)
        assertNotNull(replacement)
        policy.invalidate()
        assertNotNull(policy.prepare(track, true))
    }

    @Test fun lateFailureAndDuplicateCompletionCannotOverwriteCurrentResult() {
        val old = policy.prepare(track, true)!!
        val current = policy.prepare(track.copy(id = "two"), true)!!
        policy.fail(old)
        policy.complete(current, "first")
        policy.complete(current, "second")
        assertEquals("first", policy.take(track.copy(id = "two")))
    }
}
