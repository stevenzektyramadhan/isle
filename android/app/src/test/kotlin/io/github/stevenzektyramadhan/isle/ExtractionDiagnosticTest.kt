package io.github.stevenzektyramadhan.isle

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ExtractionDiagnosticTest {
    @Test fun exceptionCategoriesNeverEmitSensitiveDetails() {
        val secret = "https://example.com/?token=secret Authorization: private user input"
        val lines = mutableListOf<String>()
        val timing = Timing({ 0L }, lines::add)
        val cases = listOf(
            YoutubeDLException(secret) to "wrapper_error",
            YoutubeDL.CanceledException() to "wrapper_cancelled",
            InterruptedException(secret) to "interrupted",
            IllegalStateException(secret) to "other",
        )

        cases.forEach { (error, _) -> timing.extractionException(error) }
        timing.extractionTimeout()

        assertEquals(cases.map { "extraction_failure=${it.second}" } + "extraction_failure=timeout", lines)
        assertTrue(lines.none { it.contains(secret) || it.contains("secret") || it.contains("Authorization") })
    }

    @Test fun coroutineCancellationIsRethrownWithoutDiagnostic() {
        val lines = mutableListOf<String>()
        val timing = Timing({ 0L }, lines::add)
        val cancellation = CancellationException("secret")

        try {
            timing.extractionException(cancellation)
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertTrue(lines.isEmpty())
    }
}
