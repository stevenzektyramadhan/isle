package io.github.stevenzektyramadhan.isle.player

import io.github.stevenzektyramadhan.isle.data.ExtractorRepository
import io.github.stevenzektyramadhan.isle.Timing
import io.github.stevenzektyramadhan.isle.data.ResolvedStream
import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import io.github.stevenzektyramadhan.isle.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PlaybackCoordinatorTest {
    private fun PlaybackCoordinator(extractor: ExtractorRepository, scope: CoroutineScope, output: PlaybackOutput, nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) =
        io.github.stevenzektyramadhan.isle.player.PlaybackCoordinator(extractor, scope, output, timing = Timing(nowMillis, {}), nowMillis = nowMillis)
    private suspend fun PlaybackCoordinator.settled() = withTimeout(5_000) { state.first { !it.isLoading } }
    private class Output : PlaybackOutput {
        val installed = mutableListOf<String>()
        var playing = false
        val seeks = mutableListOf<Long>()
        var appliedVolume = 1f
        override suspend fun connect() = Unit
        override fun install(track: TrackItem, stream: ResolvedStream, generation: Long) { installed.add(track.id) }
        override fun play(ready: Boolean) { playing = ready }
        override fun stop() { playing = false }
        override fun seekTo(seconds: Long) { seeks.add(seconds) }
        override fun setVolume(volume: Float) { appliedVolume = volume }
    }

    private fun repository(stream: suspend () -> Unit = {}) = ExtractorRepository { target, args ->
        when {
            "-j" in args -> { stream(); """{"url":"https://8.8.8.8/stream","http_headers":{}}""" }
            target == "ytsearch3:bad" -> throw ExtractorError.Failed
            "--playlist-end" in args -> "a:::Alpha:::a\nb:::Beta:::b\nc:::Gamma:::c\nd:::Delta:::d\ne:::Epsilon:::e"
            else -> "z:::Zeta:::Artist:::https://www.youtube.com/watch?v=z:::10"
        }
    }

    @Test fun seekClampsToDurationAndStoppedPlaybackRejectsProgressAndSeek() = runBlocking {
        val output = Output()
        val coordinator = PlaybackCoordinator(repository(), this, output)
        try {
            coordinator.dispatch(PlayerCommand.SeekTo(5))
            assertTrue(output.seeks.isEmpty())
            coordinator.dispatch(PlayerCommand.SubmitInput("song"))
            coordinator.settled()
            coordinator.progress(3, 12)
            assertEquals(3L, coordinator.state.value.elapsedSecs)
            assertEquals(12L, coordinator.state.value.durationSecs)
            coordinator.dispatch(PlayerCommand.SeekTo(Long.MAX_VALUE))
            assertEquals(12L, coordinator.state.value.elapsedSecs)
            coordinator.dispatch(PlayerCommand.SeekTo(-5))
            assertEquals(listOf(12L, 0L), output.seeks)
            coordinator.progress(20, 12)
            assertEquals(12L, coordinator.state.value.elapsedSecs)
            coordinator.dispatch(PlayerCommand.Stop)
            coordinator.progress(8, 12)
            coordinator.dispatch(PlayerCommand.SeekTo(4))
            assertEquals(listOf(12L, 0L), output.seeks)
            assertEquals(0L, coordinator.state.value.elapsedSecs)
            assertNull(coordinator.state.value.durationSecs)
        } finally { coordinator.release() }
    }

    @Test fun volumeClampsFiniteCommandsAndRejectsNonFiniteValues() = runBlocking {
        val output = Output()
        val coordinator = PlaybackCoordinator(repository(), this, output)
        try {
            for ((input, expected) in listOf(2f to 1f, -1f to 0f, .4f to .4f, Float.NaN to .4f, Float.POSITIVE_INFINITY to .4f)) {
                coordinator.dispatch(PlayerCommand.SetVolume(input))
                assertEquals(expected, coordinator.state.value.volume)
                assertEquals(expected, output.appliedVolume)
            }
        } finally { coordinator.release() }
    }

    @Test fun replacementStopsOutgoingOutputBeforeStreamResolution() = runBlocking {
        for (command in listOf(PlayerCommand.Next, PlayerCommand.Previous, PlayerCommand.SubmitInput("song"))) {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var block = false
            val output = Output()
            val coordinator = PlaybackCoordinator(repository { if (block) { started.complete(Unit); gate.await() } }, this, output)
            try {
                coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
                coordinator.settled()
                if (command == PlayerCommand.Previous) {
                    coordinator.dispatch(PlayerCommand.Next)
                    coordinator.settled()
                }
                val outgoing = coordinator.generation
                block = true
                coordinator.dispatch(command)
                started.await()
                assertFalse("Outgoing output must stop during $command", output.playing)
                coordinator.ended(outgoing)
                coordinator.activationFailed(outgoing)
                assertTrue(coordinator.state.value.isLoading)
                gate.complete(Unit)
                coordinator.settled()
                assertTrue(output.playing)
            } finally { coordinator.release() }
        }
    }

    @Test fun retiredInstallationsCannotChangeStoppedOrUnavailableSessions() = runBlocking {
        for (retirement in listOf("stop", "halt", "unavailable")) {
            for (callback in listOf("error", "end", "ready")) {
                val output = Output()
                val coordinator = PlaybackCoordinator(repository(), this, output)
                try {
                    coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
                    coordinator.settled()
                    val installed = coordinator.generation
                    when (retirement) {
                        "stop" -> coordinator.dispatch(PlayerCommand.Stop)
                        "halt" -> coordinator.activationFailed(installed)
                        else -> coordinator.unavailable("disconnected")
                    }
                    val stopped = coordinator.state.value
                    when (callback) {
                        "error" -> coordinator.activationFailed(installed)
                        "end" -> coordinator.ended(installed)
                        else -> coordinator.ready(installed)
                    }
                    coroutineContext[Job]!!.children.toList().forEach { it.join() }
                    assertEquals("$retirement / $callback", stopped, coordinator.state.value)
                    assertFalse(output.playing)
                    assertEquals(listOf("a"), output.installed)
                } finally { coordinator.release() }
            }
        }
    }

    @Test fun duplicateInstalledErrorCannotAbort403RecoveryOrSpendFailureBudget() = runBlocking {
        for (recoveryFails in listOf(false, true)) {
            val retryStarted = CompletableDeferred<Unit>()
            val retryResult = CompletableDeferred<Unit>()
            var resolutions = 0
            var failFollowing = false
            val source = repository {
                resolutions++
                if (resolutions == 3) { retryStarted.complete(Unit); retryResult.await() }
                if (failFollowing) throw ExtractorError.Failed
            }
            val recovery = PlaybackRecovery()
            val installs = mutableListOf<String>()
            var mediaId = ""
            var installedGeneration = -1L
            val output = object : PlaybackOutput {
                override suspend fun connect() = Unit
                override fun install(track: TrackItem, stream: ResolvedStream, generation: Long) {
                    installs.add(track.id)
                    mediaId = installs.size.toString()
                    installedGeneration = generation
                    recovery.start(track, generation, mediaId, stream.finalStrategy)
                }
                override fun play(ready: Boolean) = Unit
                override fun stop() = Unit
            }
            val coordinator = PlaybackCoordinator(source, this, output)
            try {
                coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
                coordinator.settled()
                coordinator.ended(coordinator.generation)
                coordinator.settled()
                val failedMediaId = mediaId
                fun deliver403() {
                    coordinator.playerError(installedGeneration, failedMediaId) {
                        recovery.retry(installedGeneration, failedMediaId, 403)
                    }
                }
                deliver403()
                retryStarted.await()
                deliver403()
                assertEquals("b", coordinator.state.value.currentTrack?.id)
                assertEquals(listOf("c", "d", "e"), coordinator.state.value.queue.map { it.id })
                assertTrue(coordinator.state.value.isLoading)
                failFollowing = recoveryFails
                retryResult.complete(Unit)
                coordinator.settled()
                if (recoveryFails) {
                    assertEquals(listOf("a", "b"), installs)
                    assertEquals(listOf("e"), coordinator.state.value.queue.map { it.id })
                } else {
                    assertEquals(listOf("a", "b", "b"), installs)
                    failFollowing = true
                    coordinator.playerError(installedGeneration, mediaId) { recovery.retry(installedGeneration, mediaId, 403) }
                    coordinator.settled()
                    assertEquals(listOf("e"), coordinator.state.value.queue.map { it.id })
                }
                assertEquals("Playback stopped: repeated errors", coordinator.state.value.statusMessage)
            } finally { coordinator.release() }
        }
    }

    @Test fun failedInputResumesInterruptedActivationWithItsPlaybackIntentAndOrigin() = runBlocking {
        for (navigation in listOf("next", "previous", "input", "automatic")) {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var resolutions = 0
            val output = Output()
            val coordinator = PlaybackCoordinator(repository {
                resolutions++
                if (resolutions == if (navigation == "previous") 3 else 2) {
                    started.complete(Unit)
                    gate.await()
                }
            }, this, output)
            try {
                coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
                coordinator.settled()
                when (navigation) {
                    "previous" -> {
                        coordinator.dispatch(PlayerCommand.Next)
                        coordinator.settled()
                        coordinator.dispatch(PlayerCommand.Previous)
                    }
                    "input" -> coordinator.dispatch(PlayerCommand.SubmitInput("song"))
                    "automatic" -> coordinator.ended(coordinator.generation)
                    else -> coordinator.dispatch(PlayerCommand.Next)
                }
                started.await()
                coordinator.dispatch(PlayerCommand.Pause)
                coordinator.dispatch(PlayerCommand.SubmitInput("bad"))
                coroutineContext[Job]!!.children.toList().forEach { it.join() }
                coordinator.settled()
                assertEquals(navigation, if (navigation == "previous") listOf("a", "b", "a") else if (navigation == "input") listOf("a", "z") else listOf("a", "b"), output.installed)
                assertFalse(output.playing)
                assertFalse(coordinator.state.value.isLoading)
                coordinator.activationFailed(coordinator.generation)
                coordinator.settled()
                assertEquals(navigation, if (navigation == "automatic") "c" else if (navigation == "previous") "a" else if (navigation == "input") "z" else "b", coordinator.state.value.currentTrack?.id)
                assertEquals(navigation, navigation == "automatic", coordinator.state.value.statusMessage == null)
            } finally { coordinator.release() }
        }
    }

    @Test fun obsoleteFailedSubmissionCannotRestoreActivationAfterStopOrNavigation() = runBlocking {
        for (command in listOf(PlayerCommand.Stop, PlayerCommand.Next, PlayerCommand.Previous)) {
            val activationStarted = CompletableDeferred<Unit>()
            val lookupStarted = CompletableDeferred<Unit>()
            val lookupResult = CompletableDeferred<String>()
            val activationGate = CompletableDeferred<Unit>()
            var resolutions = 0
            val source = ExtractorRepository { target, args ->
                when {
                    target == "ytsearch3:pending" -> withContext(NonCancellable) {
                        lookupStarted.complete(Unit)
                        lookupResult.await()
                    }
                    "-j" in args -> {
                        resolutions++
                        if (resolutions == 2) { activationStarted.complete(Unit); activationGate.await() }
                        """{"url":"https://8.8.8.8/stream"}"""
                    }
                    else -> "a:::Alpha:::https://www.youtube.com/watch?v=a\nb:::Beta:::https://www.youtube.com/watch?v=b\nc:::Gamma:::https://www.youtube.com/watch?v=c"
                }
            }
            val output = Output()
            val coordinator = PlaybackCoordinator(source, this, output)
            try {
                coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
                coordinator.settled()
                coordinator.dispatch(PlayerCommand.Next)
                activationStarted.await()
                coordinator.dispatch(PlayerCommand.SubmitInput("pending"))
                lookupStarted.await()
                coordinator.dispatch(command)
                coordinator.settled()
                lookupResult.completeExceptionally(ExtractorError.Failed)
                coroutineContext[Job]!!.children.toList().forEach { it.join() }
                assertEquals(if (command == PlayerCommand.Stop) listOf("a") else if (command == PlayerCommand.Next) listOf("a", "c") else listOf("a", "a"), output.installed)
                assertEquals(if (command == PlayerCommand.Stop) null else if (command == PlayerCommand.Next) "c" else "a", coordinator.state.value.currentTrack?.id)
            } finally { coordinator.release() }
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

    @Test fun prefetchMetricsDistinguishHitFromMissWithoutTrackData() = runBlocking {
        for ((age, outcome) in listOf(59_999L to "hit", 60_000L to "miss")) {
            var now = 0L
            val lines = mutableListOf<String>()
            val completed = CompletableDeferred<Unit>()
            var resolutions = 0
            val source = repository { if (++resolutions == 2) completed.complete(Unit) }
            val coordinator = PlaybackCoordinator(source, this, Output(), timing = Timing({ now }, lines::add)) { now }
            try {
                coordinator.dispatch(PlayerCommand.SubmitInput("https://www.youtube.com/playlist?list=test"))
                coordinator.settled()
                coordinator.playingChanged(true)
                withTimeout(5_000) { completed.await() }
                coroutineContext[Job]!!.children.toList().forEach { withTimeout(5_000) { it.join() } }
                coordinator.playingChanged(false)
                now = age
                coordinator.dispatch(PlayerCommand.Next)
                coordinator.settled()
                assertEquals(listOf("event=prefetch outcome=$outcome"), lines)
            } finally { coordinator.release() }
        }
    }

    @Test fun radioLookupReportsOutcomeAndDurationWithoutInputData() = runBlocking {
        for ((response, outcome) in listOf("b:::Beta:::Artist:::https://www.youtube.com/watch?v=b:::10" to "success", "" to "no_results")) {
            var now = 10L
            val lines = mutableListOf<String>()
            val source = ExtractorRepository { _, args ->
                when {
                    "-j" in args -> """{"url":"https://8.8.8.8/stream"}"""
                    "-I" in args -> { now = 42L; response }
                    else -> "a:::Alpha:::Artist:::https://www.youtube.com/watch?v=a:::10"
                }
            }
            val coordinator = PlaybackCoordinator(source, this, Output(), timing = Timing({ now }, lines::add))
            try {
                coordinator.dispatch(PlayerCommand.SubmitInput("private song"))
                coordinator.settled()
                coordinator.ended(coordinator.generation)
                coordinator.settled()
                assertEquals(listOf("stage=radio_lookup outcome=$outcome elapsed_ms=32"), lines)
            } finally { coordinator.release() }
        }
    }
}
