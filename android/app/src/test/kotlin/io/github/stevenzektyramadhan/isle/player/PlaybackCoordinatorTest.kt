package io.github.stevenzektyramadhan.isle.player

import io.github.stevenzektyramadhan.isle.data.ExtractorRepository
import io.github.stevenzektyramadhan.isle.data.ResolvedStream
import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import io.github.stevenzektyramadhan.isle.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PlaybackCoordinatorTest {
    private suspend fun PlaybackCoordinator.settled() = withTimeout(5_000) { state.first { !it.isLoading } }
    private class Output : PlaybackOutput {
        val installed = mutableListOf<String>()
        var playing = false
        override suspend fun connect() = Unit
        override fun install(track: TrackItem, stream: ResolvedStream, generation: Long) { installed.add(track.id) }
        override fun play(ready: Boolean) { playing = ready }
        override fun stop() { playing = false }
    }

    private fun repository(stream: suspend () -> Unit = {}) = ExtractorRepository { target, args ->
        when {
            "-j" in args -> { stream(); """{"url":"https://8.8.8.8/stream","http_headers":{}}""" }
            target == "ytsearch3:bad" -> throw ExtractorError.Failed
            "--playlist-end" in args -> "a:::Alpha:::a\nb:::Beta:::b\nc:::Gamma:::c\nd:::Delta:::d\ne:::Epsilon:::e"
            else -> "z:::Zeta:::Artist:::https://www.youtube.com/watch?v=z:::10"
        }
    }

    @Test fun failedInputPreservesQueueHistoryAndPlayingTrack() = runBlocking {
        val output = Output()
        val coordinator = PlaybackCoordinator(repository(), this, output)
        coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
        coordinator.settled()
        coordinator.dispatch(PlayerCommand.Next)
        coordinator.settled()
        coordinator.dispatch(PlayerCommand.SubmitInput("bad"))
        coordinator.settled()
        assertEquals("b", coordinator.state.value.currentTrack?.id)
        assertEquals(listOf("c", "d", "e"), coordinator.state.value.queue.map { it.id })
        assertEquals(listOf("a"), coordinator.state.value.history.map { it.id })
        assertTrue(output.playing)
        coordinator.release()
    }

    @Test fun endDuringFailedSubmissionAdvancesPreservedSessionExactlyOnce() = runBlocking {
        for (queued in listOf(true, false)) {
            val lookupStarted = CompletableDeferred<Unit>()
            val lookupResult = CompletableDeferred<String>()
            val source = ExtractorRepository { target, args ->
                when {
                    target == "ytsearch3:pending" -> { lookupStarted.complete(Unit); lookupResult.await() }
                    "-j" in args -> """{"url":"https://8.8.8.8/stream"}"""
                    "--playlist-end" in args -> "a:::Alpha:::https://www.youtube.com/watch?v=a\nb:::Beta:::https://www.youtube.com/watch?v=b\nc:::Gamma:::https://www.youtube.com/watch?v=c"
                    "-I" in args -> "b:::Beta:::Artist:::https://www.youtube.com/watch?v=b:::10\nc:::Gamma:::Artist:::https://www.youtube.com/watch?v=c:::10"
                    else -> "a:::Alpha:::Artist:::https://www.youtube.com/watch?v=a:::10"
                }
            }
            val output = Output()
            val coordinator = PlaybackCoordinator(source, this, output)
            try {
                coordinator.dispatch(PlayerCommand.SubmitInput(if (queued) "https://www.youtube.com/playlist?list=test" else "song"))
                coordinator.settled()
                val endedGeneration = coordinator.generation
                coordinator.dispatch(PlayerCommand.SubmitInput("pending"))
                withTimeout(5_000) { lookupStarted.await() }
                coordinator.dispatch(PlayerCommand.Pause)

                coordinator.ended(endedGeneration)
                coordinator.ended(endedGeneration)
                assertEquals(listOf("a"), output.installed)
                assertEquals("a", coordinator.state.value.currentTrack?.id)
                assertEquals(if (queued) listOf("b", "c") else emptyList<String>(), coordinator.state.value.queue.map { it.id })
                assertTrue(coordinator.state.value.history.isEmpty())
                lookupResult.completeExceptionally(ExtractorError.Failed)
                coroutineContext[Job]!!.children.toList().forEach { it.join() }
                coordinator.settled()

                assertEquals(listOf("a", "b"), output.installed)
                assertEquals(listOf("a"), coordinator.state.value.history.map { it.id })
                assertEquals(listOf("c"), coordinator.state.value.queue.map { it.id })
                assertEquals("a", coordinator.state.value.autoplayAnchor?.id)
                assertFalse(output.playing)
                coordinator.ended(endedGeneration)
                assertEquals("b", coordinator.state.value.currentTrack?.id)
                assertFalse(coordinator.state.value.isLoading)
            } finally { coordinator.release() }
        }
    }

    @Test fun successfulSubmissionDiscardsDeferredEndOfReplacedTrack() = runBlocking {
        val lookupStarted = CompletableDeferred<Unit>()
        val lookupResult = CompletableDeferred<String>()
        val source = ExtractorRepository { target, args ->
            when {
                target == "ytsearch3:pending" -> { lookupStarted.complete(Unit); lookupResult.await() }
                "-j" in args -> """{"url":"https://8.8.8.8/stream"}"""
                else -> "a:::Alpha:::Artist:::https://www.youtube.com/watch?v=a:::10"
            }
        }
        val output = Output()
        val coordinator = PlaybackCoordinator(source, this, output)
        try {
            coordinator.dispatch(PlayerCommand.SubmitInput("song"))
            coordinator.settled()
            val oldGeneration = coordinator.generation
            coordinator.dispatch(PlayerCommand.SubmitInput("pending"))
            withTimeout(5_000) { lookupStarted.await() }
            coordinator.ended(oldGeneration)
            lookupResult.complete("b:::Beta:::Artist:::https://www.youtube.com/watch?v=b:::10")
            coordinator.settled()
            coordinator.ended(oldGeneration)
            assertEquals(listOf("a", "b"), output.installed)
            assertEquals("b", coordinator.state.value.currentTrack?.id)
            assertEquals("b", coordinator.state.value.autoplayAnchor?.id)
            assertFalse(coordinator.state.value.isLoading)
            assertFalse(coordinator.state.value.autoplayFetched)
        } finally { coordinator.release() }
    }

    @Test fun pauseDuringResolutionSurvivesInstallAndPreviousRestoresQueueFront() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val output = Output()
        val coordinator = PlaybackCoordinator(repository { gate.await() }, this, output)
        coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
        yield()
        coordinator.dispatch(PlayerCommand.Pause)
        gate.complete(Unit)
        coordinator.settled()
        assertFalse(output.playing)
        coordinator.dispatch(PlayerCommand.Next)
        coordinator.settled()
        coordinator.dispatch(PlayerCommand.Previous)
        coordinator.settled()
        assertEquals(listOf("a", "b", "a"), output.installed)
        assertEquals(listOf("b", "c", "d", "e"), coordinator.state.value.queue.map { it.id })
        coordinator.release()
    }

    @Test fun endedAdvancesButThreeAutomaticFailuresLeaveRemainingQueueUntouched() = runBlocking {
        var fail = false
        val output = Output()
        val coordinator = PlaybackCoordinator(repository { if (fail) throw ExtractorError.Failed }, this, output)
        coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
        coordinator.settled()
        fail = true
        coordinator.ended(coordinator.generation)
        coordinator.settled()
        assertEquals(listOf("a"), output.installed)
        assertEquals(listOf("e"), coordinator.state.value.queue.map { it.id })
        assertFalse(output.playing)
        assertFalse(coordinator.state.value.isLoading)
        coordinator.release()
    }

    @Test fun previousAfterFailedActivationReturnsToMostRecentAttemptedTrack() = runBlocking {
        for (automatic in listOf(false, true)) {
            var fail = false
            val output = Output()
            val coordinator = PlaybackCoordinator(repository { if (fail) throw ExtractorError.Failed }, this, output)
            try {
                coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
                coordinator.settled()
                fail = true
                if (automatic) coordinator.ended(coordinator.generation) else coordinator.dispatch(PlayerCommand.Next)
                coordinator.settled()
                fail = false

                coordinator.dispatch(PlayerCommand.Previous)
                coordinator.settled()

                // Rust: src/audio.rs advance_queue and pop_previous (lines 65-79).
                assertEquals(if (automatic) "c" else "a", coordinator.state.value.currentTrack?.id)
                assertEquals(if (automatic) listOf("a", "c") else listOf("a", "a"), output.installed)
                assertEquals(if (automatic) listOf("d", "e") else listOf("b", "c", "d", "e"), coordinator.state.value.queue.map { it.id })
                assertEquals(if (automatic) listOf("a", "b") else emptyList<String>(), coordinator.state.value.history.map { it.id })
            } finally { coordinator.release() }
        }
    }

    @Test fun cancelledNonCooperativeLookupCannotReplaceNewerInput() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val source = ExtractorRepository { target, args ->
            if (target == "ytsearch3:old") withContext(NonCancellable) { entered.complete(Unit); gate.await(); finished.complete(Unit) }
            if ("-j" in args) """{"url":"https://8.8.8.8/stream"}"""
            else if (target == "ytsearch3:old") "old:::Old:::Artist:::https://www.youtube.com/watch?v=old:::10"
            else "new:::New:::Artist:::https://www.youtube.com/watch?v=new:::10"
        }
        val output = Output()
        val coordinator = PlaybackCoordinator(source, this, output)
        coordinator.dispatch(PlayerCommand.SubmitInput("old"))
        entered.await()
        coordinator.dispatch(PlayerCommand.SubmitInput("new"))
        coordinator.settled()
        gate.complete(Unit)
        finished.await()
        yield()
        assertEquals(listOf("new"), output.installed)
        assertEquals("new", coordinator.state.value.currentTrack?.id)
        coordinator.release()
    }

    @Test fun exhaustedQueueLoadsOneAutoplayPoolAndFiltersTheSessionAnchor() = runBlocking {
        var relatedCalls = 0
        val source = ExtractorRepository { _, args ->
            when {
                "-j" in args -> """{"url":"https://8.8.8.8/stream"}"""
                "-I" in args -> {
                    relatedCalls++
                    "a:::Alpha:::Artist:::https://www.youtube.com/watch?v=a:::10\nb:::Beta:::Artist:::https://www.youtube.com/watch?v=b:::10"
                }
                else -> "a:::Alpha:::Artist:::https://www.youtube.com/watch?v=a:::10"
            }
        }
        val output = Output()
        val coordinator = PlaybackCoordinator(source, this, output)
        coordinator.dispatch(PlayerCommand.SubmitInput("song"))
        coordinator.settled()
        coordinator.ended(coordinator.generation)
        coordinator.settled()
        coordinator.ended(coordinator.generation)
        coordinator.settled()
        assertEquals(listOf("a", "b"), output.installed)
        assertEquals(1, relatedCalls)
        assertEquals("a", coordinator.state.value.autoplayAnchor?.id)
        assertTrue(coordinator.state.value.autoplayFetched)
        assertFalse(output.playing)
        coordinator.release()
    }

    @Test fun prefetchedStreamIsConsumedOnlyBeforeSixtySeconds() = runBlocking {
        for ((age, expectedResolutions) in listOf(59_999L to 2, 60_000L to 3)) {
            var now = 0L
            var resolutions = 0
            val prepared = CompletableDeferred<Unit>()
            val source = repository { resolutions++; if (resolutions == 2) prepared.complete(Unit) }
            val output = Output()
            val coordinator = PlaybackCoordinator(source, this, output) { now }
            coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
            coordinator.settled()
            coordinator.playingChanged(true)
            prepared.await()
            coroutineContext[Job]!!.children.toList().forEach { it.join() }
            coordinator.playingChanged(false)
            now = age
            coordinator.dispatch(PlayerCommand.Next)
            coordinator.settled()
            assertEquals(listOf("a", "b"), output.installed)
            assertEquals(expectedResolutions, resolutions)
            coordinator.release()
        }
    }
}
