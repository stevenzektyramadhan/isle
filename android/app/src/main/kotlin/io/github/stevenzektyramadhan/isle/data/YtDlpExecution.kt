package io.github.stevenzektyramadhan.isle.data

import io.github.stevenzektyramadhan.isle.update.YtDlpExecutionGate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine

internal class YtDlpExecution(private val destroy: () -> Unit) {
    suspend fun execute(block: () -> String): String = suspendCancellableCoroutine { continuation ->
        val executor = Executors.newSingleThreadExecutor()
        val cleanup = Executors.newSingleThreadScheduledExecutor()
        continuation.invokeOnCancellation {
            synchronized(cleanup) {
                if (!cleanup.isShutdown) cleanup.scheduleWithFixedDelay(destroy, 0, 100, TimeUnit.MILLISECONDS)
            }
        }
        executor.execute {
            try {
                // Cancellation only requests destruction; execute must return before this thread releases the gate.
                YtDlpExecutionGate.withExecution {
                    if (continuation.isActive) {
                        val output = block()
                        if (continuation.isActive) continuation.resumeWith(Result.success(output))
                    }
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            } finally {
                synchronized(cleanup) { cleanup.shutdownNow() }
                executor.shutdown()
            }
        }
    }
}
