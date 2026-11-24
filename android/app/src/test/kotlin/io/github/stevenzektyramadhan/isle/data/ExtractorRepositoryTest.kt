package io.github.stevenzektyramadhan.isle.data

import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import io.github.stevenzektyramadhan.isle.Timing
import io.github.stevenzektyramadhan.isle.model.TrackItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import io.github.stevenzektyramadhan.isle.data.extractor.SpotifyResolver

class ExtractorRepositoryTest {
    private val anchor = TrackItem("anchor", "Song (Official)", "Artist", "https://www.youtube.com/watch?v=anchor", null)

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
