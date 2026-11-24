package io.github.stevenzektyramadhan.isle

import android.util.Log
import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import kotlinx.coroutines.CancellationException

internal class Timing(
    private val clock: () -> Long,
    private val sink: (String) -> Unit,
) {
    enum class Stage { INIT_WAIT, METADATA, STREAM_PRIMARY, STREAM_FINAL, SERVICE_CONNECTION, PLAYER_READY, RADIO_LOOKUP }
    enum class Outcome { SUCCESS, ERROR, CANCELLED, NO_RESULTS, UNUSABLE_STREAM }
    enum class PrefetchOutcome { HIT, MISS, FAILURE }

    fun prefetch(outcome: PrefetchOutcome) {
        sink("event=prefetch outcome=${outcome.name.lowercase(java.util.Locale.ROOT)}")
    }

    fun start(): Long = clock()

    fun extractionException(error: Exception) {
        val category = when (error) {
            is CancellationException -> throw error
            is YoutubeDL.CanceledException -> "wrapper_cancelled"
            is YoutubeDLException -> "wrapper_error"
            is InterruptedException -> "interrupted"
            else -> "other"
        }
        sink("extraction_failure=$category")
    }

    fun extractionTimeout() { sink("extraction_failure=timeout") }

    fun record(stage: Stage, outcome: Outcome, start: Long) {
        sink("stage=${stage.name.lowercase(java.util.Locale.ROOT)} outcome=${outcome.name.lowercase(java.util.Locale.ROOT)} elapsed_ms=${clock() - start}")
    }

    suspend fun <T> measure(stage: Stage, block: suspend () -> T): T {
        val start = start()
        var outcome = Outcome.ERROR
        try {
            return block().also { outcome = Outcome.SUCCESS }
        } catch (error: CancellationException) {
            outcome = Outcome.CANCELLED
            throw error
        } catch (error: ExtractorError.NoResults) {
            outcome = Outcome.NO_RESULTS
            throw error
        } finally {
            record(stage, outcome, start)
        }
    }

    companion object {
        val android = Timing({ SystemClock.elapsedRealtime() }, { Log.i("IsleTiming", it) })
    }
}
