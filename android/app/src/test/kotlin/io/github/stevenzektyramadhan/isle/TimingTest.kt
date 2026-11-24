package io.github.stevenzektyramadhan.isle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TimingTest {
    @Test fun recordsOnlyFixedFieldsAndElapsedTimeOnSuccess() = runBlocking {
        var now = 100L
        val lines = mutableListOf<String>()
        val timing = Timing({ now }, lines::add)

        val result = timing.measure(Timing.Stage.METADATA) { now = 142L; "private title and https://example.com/?token=secret" }

        assertEquals("private title and https://example.com/?token=secret", result)
        assertEquals(listOf("stage=metadata outcome=success elapsed_ms=42"), lines)
    }

    @Test fun recordsCancellationAndRethrowsTheSameException() = runBlocking {
        val lines = mutableListOf<String>()
        val timing = Timing({ 10L }, lines::add)
        val cancellation = CancellationException("private input")

        try {
            timing.measure(Timing.Stage.STREAM_PRIMARY) { throw cancellation }
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(listOf("stage=stream_primary outcome=cancelled elapsed_ms=0"), lines)
    }
}
