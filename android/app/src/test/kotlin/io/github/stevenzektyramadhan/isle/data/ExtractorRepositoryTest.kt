package io.github.stevenzektyramadhan.isle.data

import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import io.github.stevenzektyramadhan.isle.Timing
import io.github.stevenzektyramadhan.isle.model.TrackItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExtractorRepositoryTest {
    private val anchor = TrackItem("anchor", "Song (Official)", "Artist", "https://www.youtube.com/watch?v=anchor", null)

    @Test fun streamFallbackRecordsBothAttemptsWithoutInputOrExceptionDetails() = runBlocking {
        val lines = mutableListOf<String>()
        val repository = ExtractorRepository(Timing({ 10L }, lines::add)) { _, args ->
            if (args.any { it.contains("visionos") }) throw ExtractorError.Failed
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
