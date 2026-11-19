package io.github.stevenzektyramadhan.isle.data

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.stevenzektyramadhan.isle.domain.*
import io.github.stevenzektyramadhan.isle.model.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

private const val SEARCH_FORMAT = "%(id)s:::%(title)s:::%(uploader)s:::%(url)s:::%(duration)s"
private const val METADATA_FORMAT = "%(id)s:::%(title)s:::%(uploader)s:::%(webpage_url)s:::%(duration)s"
private const val STREAM_FORMAT = "ba[ext=m4a][protocol^=http]/ba[ext=mp3][protocol^=http]/ba[acodec^=vorbis][protocol^=http]/ba[protocol^=http]/b[protocol^=http]"

class ExtractorRepository(context: Context, scope: CoroutineScope) {
    private val initialized = scope.async(Dispatchers.IO) {
        try { YoutubeDL.init(context.applicationContext); true } catch (_: Exception) { false }
    }
    suspend fun isAvailable(): Boolean = initialized.await()

    suspend fun resolveInput(input: String): TrackItem = withContext(Dispatchers.IO) {
        when (val kind = InputClassifier.classify(input)) {
            is InputKind.SearchQuery -> run(kind.value.let { "ytsearch3:$it" }, listOf("--flat-playlist", "--match-filter", "live_status!=is_live", "--print", SEARCH_FORMAT))
            is InputKind.DirectUrl -> run(kind.value, listOf("--no-playlist", "--print", METADATA_FORMAT))
            else -> throw ExtractorError.Unsupported
        }.lineSequence().mapNotNull(::parseMetadataLine).firstOrNull() ?: throw ExtractorError.NoResults
    }

    suspend fun resolveStream(track: TrackItem): StreamInfo {
        withContext(Dispatchers.IO) { InputClassifier.classify(track.originalUrl) }
        val common = listOf("-j", "-f", STREAM_FORMAT, "--no-playlist")
        var lastError: ExtractorError? = null
        for ((index, client) in listOf("visionos,default", "android,web").withIndex()) {
            try {
                val stream = parseStreamJson(run(track.originalUrl, common + listOf("--extractor-args", "youtube:player_client=$client")))
                if (stream != null && (index == 1 || !stream.url.contains("c=TVHTML5"))) {
                    withContext(Dispatchers.IO) { InputClassifier.validateStreamUrl(stream.url) }
                    return stream
                }
            } catch (error: ExtractorError) { lastError = error }
        }
        throw lastError ?: ExtractorError.NoResults
    }

    private suspend fun run(target: String, args: List<String>): String {
        if (!initialized.await()) throw ExtractorError.MissingBinary
        val id = UUID.randomUUID().toString()
        val request = YoutubeDLRequest(target)
        val options = listOf("--socket-timeout", "10", "--retries", "1", "--extractor-retries", "1") + args
        var index = 0
        while (index < options.size) {
            val option = options[index++]
            if (index < options.size && !options[index].startsWith("-")) request.addOption(option, options[index++]) else request.addOption(option)
        }
        return try {
            withTimeout(30_000) {
                suspendCancellableCoroutine { continuation ->
                    val executor = Executors.newSingleThreadExecutor()
                    val cleanup = Executors.newSingleThreadScheduledExecutor()
                    continuation.invokeOnCancellation {
                        synchronized(cleanup) {
                            if (!cleanup.isShutdown) cleanup.scheduleWithFixedDelay({ YoutubeDL.destroyProcessById(id) }, 0, 100, TimeUnit.MILLISECONDS)
                        }
                    }
                    executor.execute {
                        try {
                            val output = YoutubeDL.execute(request, id).out
                            if (continuation.isActive) continuation.resumeWith(Result.success(output))
                        } catch (_: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(ExtractorError.Failed))
                        } finally { synchronized(cleanup) { cleanup.shutdownNow() }; executor.shutdown() }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) { throw ExtractorError.Timeout }
    }
}
