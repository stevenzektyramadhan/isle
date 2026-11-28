package io.github.stevenzektyramadhan.isle.data

import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import io.github.stevenzektyramadhan.isle.Timing
import io.github.stevenzektyramadhan.isle.model.TrackItem
import io.github.stevenzektyramadhan.isle.model.StreamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import io.github.stevenzektyramadhan.isle.data.extractor.SpotifyResolver

class ExtractorRepositoryTest {
    private val anchor = TrackItem("anchor", "Song (Official)", "Artist", "https://www.youtube.com/watch?v=anchor", null)

    @Test fun newPipeTimingSeparatesOutcomesWithoutLeakingDetails() = runBlocking {
        for ((stream, failure, expected) in listOf(
            Triple(StreamInfo("https://8.8.8.8/secret", null), false, "success"),
            Triple(null, false, "no_results"),
            Triple(StreamInfo("http://127.0.0.1/secret", null), false, "unusable_stream"),
            Triple(null, true, "error"),
        )) {
            val lines = mutableListOf<String>()
            val repository = ExtractorRepository(Timing({ 10L }, lines::add), newPipe = {
                if (failure) throw IllegalStateException("secret")
                stream
            }) { _, _ -> """{"url":"https://8.8.8.8/fallback"}""" }
            repository.resolveStream(anchor)
            assertEquals("stage=stream_newpipe outcome=$expected elapsed_ms=0", lines.first())
            assertFalse(lines.any { it.contains("secret") })
        }
    }

    @Test fun newPipeSuccessAndFinalRecoveryUseSeparateStrategies() = runBlocking {
        var calls = 0
        val repository = ExtractorRepository(newPipe = { calls++; StreamInfo("https://8.8.8.8/audio", null) }) { _, _ ->
            """{"url":"https://8.8.8.8/final"}"""
        }
        assertEquals(StreamStrategy.NEWPIPE, repository.resolveStream(anchor).strategy)
        assertTrue(repository.resolveStream(anchor, finalOnly = true).finalStrategy)
        assertEquals(1, calls)
    }

    @Test fun newPipeFailureAndUnsafeStreamFallBackToYtDlp() = runBlocking {
        for (failure in listOf(true, false)) {
            var calls = 0
            val repository = ExtractorRepository(newPipe = {
                if (failure) throw IllegalStateException("secret")
                StreamInfo("http://127.0.0.1/private", null)
            }) { _, _ -> calls++; """{"url":"https://8.8.8.8/fallback"}""" }
            assertEquals(StreamStrategy.ANDROID_WEB, repository.resolveStream(anchor).strategy)
            assertEquals(1, calls)
        }
    }

    @Test fun newPipeCancellationNeverStartsFallback() = runBlocking {
        var calls = 0
        val lines = mutableListOf<String>()
        val repository = ExtractorRepository(Timing({ 10L }, lines::add), newPipe = { throw CancellationException() }) { _, _ -> calls++; "{}" }
        try { repository.resolveStream(anchor); fail("expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, calls)
        assertEquals(listOf("stage=stream_newpipe outcome=cancelled elapsed_ms=0"), lines)
    }

    @Test fun cancellingActiveNewPipeRequestNeverStartsFallback() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val repository = ExtractorRepository(newPipe = { started.complete(Unit); awaitCancellation() }) { _, _ -> calls++; "{}" }
        val job = launch { repository.resolveStream(anchor) }
        started.await()
        job.cancelAndJoin()
        assertEquals(0, calls)
    }

    @Test fun youtubeHostVariantsUseNewPipe() = runBlocking {
        var calls = 0
        val repository = ExtractorRepository(newPipe = { calls++; StreamInfo("https://8.8.8.8/audio", null) }) { _, _ -> fail("unexpected fallback"); "" }
        for (url in listOf("https://youtube.com/watch?v=x", "https://m.youtube.com/watch?v=x", "https://youtu.be/x")) {
            assertEquals(StreamStrategy.NEWPIPE, repository.resolveStream(anchor.copy(originalUrl = url)).strategy)
        }
        assertEquals(3, calls)
    }

    @Test fun nonYoutubeAndSearchTargetsNeverUseNewPipe() = runBlocking {
        var calls = 0
        val repository = ExtractorRepository(newPipe = { calls++; null }) { _, _ -> """{"url":"https://8.8.8.8/audio"}""" }
        for (url in listOf("https://youtube.com.example.com/watch?v=x", "https://notyoutube.com/watch?v=x", "ytsearch1:Song Official Audio")) {
            repository.resolveStream(anchor.copy(originalUrl = url))
        }
        assertEquals(0, calls)
    }

    @Test fun spotifyOembedFallbackUsesExistingYoutubeSearchAndParsesItsResult() = runBlocking {
        val repository = ExtractorRepository(spotify = SpotifyResolver { url ->
            if (url.contains("/embed/")) "bad" else """{"title":"Collection"}"""
        }) { target, args ->
            assertEquals("ytsearch3:Collection", target)
            assertTrue(args.contains("--flat-playlist"))
            "yt:::Song:::Singer:::https://youtube.com/watch?v=yt:::123"
        }
        assertEquals(listOf(TrackItem("yt", "Song", "Singer", "https://youtube.com/watch?v=yt", 123)), repository.resolveInputs("spotify:playlist:abc"))
    }

    @Test fun invalidSpotifyResourceDoesNotReachYoutube() = runBlocking {
        val repository = ExtractorRepository { _, _ -> fail("invalid resource reached yt-dlp"); "" }
        try {
            repository.resolveInputs("spotify:track:../../../etc")
            fail("expected invalid resource rejection")
        } catch (_: ExtractorError.NoResults) { }
    }

    @Test fun streamFallbackRecordsBothAttemptsWithoutInputOrExceptionDetails() = runBlocking {
        val lines = mutableListOf<String>()
        val repository = ExtractorRepository(Timing({ 10L }, lines::add)) { _, args ->
            if (args.any { it.contains("android,web") }) throw ExtractorError.Failed
            """{"url":"https://8.8.8.8/stream?token=secret"}"""
        }

        assertTrue(repository.resolveStream(anchor).finalStrategy)
        assertEquals(listOf(
            "stage=stream_newpipe outcome=no_results elapsed_ms=0",
            "stage=init_wait outcome=success elapsed_ms=0",
            "stage=stream_primary outcome=error elapsed_ms=0",
            "stage=init_wait outcome=success elapsed_ms=0",
            "stage=stream_final outcome=success elapsed_ms=0",
        ), lines)
    }

    @Test fun androidStreamIsPreferredAndLeavesAlternateAvailable() = runBlocking {
        val clients = mutableListOf<String>()
        val repository = ExtractorRepository { _, args ->
            val client = args.last().substringAfter("player_client=")
            clients.add(client)
            """{"url":"https://8.8.8.8/$client"}"""
        }
        val resolved = repository.resolveStream(anchor)
        assertEquals("https://8.8.8.8/android,web", resolved.stream.url)
        assertFalse(resolved.finalStrategy)
        assertEquals(listOf("android,web"), clients)
    }

    @Test fun androidExtractionFailureOrEmptyResultUsesVisionosAlternate() = runBlocking {
        for (failure in listOf(ExtractorError.Timeout, ExtractorError.Failed, null)) {
            val clients = mutableListOf<String>()
            val repository = ExtractorRepository { _, args ->
                val client = args.last().substringAfter("player_client=")
                clients.add(client)
                if (client == "android,web") {
                    if (failure != null) throw failure
                    "{}"
                } else """{"url":"https://8.8.8.8/alternate"}"""
            }
            val resolved = repository.resolveStream(anchor)
            assertEquals("https://8.8.8.8/alternate", resolved.stream.url)
            assertTrue(resolved.finalStrategy)
            assertEquals(listOf("android,web", "visionos,default"), clients)
        }
    }

    @Test fun finalOnlyNeverReentersAndroidStrategy() = runBlocking {
        val clients = mutableListOf<String>()
        val repository = ExtractorRepository { _, args ->
            val client = args.last().substringAfter("player_client=")
            clients.add(client)
            """{"url":"https://8.8.8.8/$client"}"""
        }
        val resolved = repository.resolveStream(anchor, finalOnly = true)
        assertEquals("https://8.8.8.8/visionos,default", resolved.stream.url)
        assertTrue(resolved.finalStrategy)
        assertEquals(listOf("visionos,default"), clients)
    }

    @Test fun tvhtml5IsAcceptedOnlyFromAndroidStrategy() = runBlocking {
        val repository = ExtractorRepository { _, _ -> """{"url":"https://8.8.8.8/stream?c=TVHTML5"}""" }
        assertEquals("https://8.8.8.8/stream?c=TVHTML5", repository.resolveStream(anchor).stream.url)
        try {
            repository.resolveStream(anchor, finalOnly = true)
            fail("visionos TVHTML5 stream must be rejected")
        } catch (_: ExtractorError.NoResults) { }
    }

    @Test fun playlistReturnsValidEntriesAndRequestsBoundedFlatExtraction() = runBlocking {
        val repository = ExtractorRepository { _, args ->
            assertTrue(args.containsAll(listOf("--flat-playlist", "--playlist-end", "200")))
            "one:::First:::https://youtube.com/watch?v=one\nbroken\ntwo:::Second:::https://youtube.com/watch?v=two"
        }
        assertEquals(listOf("one", "two"), repository.resolveInputs("https://youtube.com/playlist?list=abc").map { it.id })
    }

    @Test fun relatedMixFailureFallsBackAndFiltersDuplicatesAndLimitsResults() = runBlocking {
        val repository = ExtractorRepository { target, _ ->
            if (target.startsWith("https://www.youtube.com/watch")) throw ExtractorError.Failed
            assertEquals("ytsearch50:Song (Official) Artist audio", target)
            (listOf("anchor:::Other:::A:::https://youtube.com/a:::1", "x:::Song [Live]:::A:::https://youtube.com/x:::1", "a:::First:::A:::https://youtube.com/a:::1", "a:::Different:::A:::https://youtube.com/a:::1", "b:::First (Video):::A:::https://youtube.com/b:::1") +
                (1..55).map { "id$it:::Unique $it:::A:::https://youtube.com/$it:::1" }).joinToString("\n")
        }
        val result = repository.fetchRelated(anchor)
        assertEquals(50, result.size)
        assertEquals("a", result.first().id)
        assertEquals("id49", result.last().id)
    }

    @Test fun cancellationDoesNotTriggerRelatedFallback() = runBlocking {
        var calls = 0
        val repository = ExtractorRepository { _, _ -> calls++; throw CancellationException("cancel") }
        try { repository.fetchRelated(anchor); fail("expected cancellation") } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun privateInputAndUnsafeMixIdNeverReachExtractor() = runBlocking {
        val targets = mutableListOf<String>()
        val repository = ExtractorRepository { target, _ -> targets.add(target); "id:::Title:::A:::https://youtube.com/id:::1" }
        try { repository.resolveInputs("http://127.0.0.1/playlist?list=x"); fail("expected rejection") } catch (_: ExtractorError.PrivateNetwork) { }
        repository.fetchRelated(anchor.copy(id = "a&list=evil"))
        assertEquals(listOf("ytsearch50:Song (Official) Artist audio"), targets)
    }
}
