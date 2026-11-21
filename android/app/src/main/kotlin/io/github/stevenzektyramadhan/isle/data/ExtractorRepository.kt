package io.github.stevenzektyramadhan.isle.data

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.stevenzektyramadhan.isle.Timing
import io.github.stevenzektyramadhan.isle.IsleApp
import io.github.stevenzektyramadhan.isle.domain.*
import io.github.stevenzektyramadhan.isle.model.*
import io.github.stevenzektyramadhan.isle.update.YtDlpUpdater
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

private const val SEARCH_FORMAT = "%(id)s:::%(title)s:::%(uploader)s:::%(url)s:::%(duration)s"
private const val METADATA_FORMAT = "%(id)s:::%(title)s:::%(uploader)s:::%(webpage_url)s:::%(duration)s"
private const val PLAYLIST_FORMAT = "%(id)s:::%(title)s:::%(url)s"
private const val STREAM_FORMAT = "ba[ext=m4a][protocol^=http]/ba[ext=mp3][protocol^=http]/ba[acodec^=vorbis][protocol^=http]/ba[protocol^=http]/b[protocol^=http]"

data class ResolvedStream(val stream: StreamInfo, val finalStrategy: Boolean)

class ExtractorRepository private constructor(
    private val ready: Deferred<Unit>,
    private val availability: () -> Boolean,
    private val invocation: (suspend (String, List<String>) -> String)?,
    private val timing: Timing,
) {
    private constructor(context: Context, scope: CoroutineScope, available: BooleanArray) : this(scope.async(Dispatchers.IO) {
        try {
            YoutubeDL.init(context.applicationContext)
            available[0] = true
            if (!YtDlpUpdater(context).check()) scope.launch { (context.applicationContext as IsleApp).playerEngine.noteStatus("yt-dlp update failed") }
        } catch (error: CancellationException) { throw error } catch (_: Exception) { }
    }, { available[0] }, null, Timing.android)

    constructor(context: Context, scope: CoroutineScope) : this(context, scope, booleanArrayOf(false))

    internal constructor(timing: Timing = Timing({ System.nanoTime() / 1_000_000 }, {}), invocation: suspend (String, List<String>) -> String) : this(CompletableDeferred(Unit), { true }, invocation, timing)

    suspend fun isAvailable(): Boolean { ready.await(); return availability() }

    suspend fun resolveInput(input: String): TrackItem = resolveInputs(input).first()

    suspend fun resolveInputs(input: String): List<TrackItem> = timing.measure(Timing.Stage.METADATA) { withContext(Dispatchers.IO) {
        when (val kind = InputClassifier.classify(input)) {
            is InputKind.SearchQuery -> listOfNotNull(run("ytsearch3:${kind.value}", listOf("--flat-playlist", "--match-filter", "live_status!=is_live", "--print", SEARCH_FORMAT)).lineSequence().mapNotNull(::parseMetadataLine).firstOrNull())
            is InputKind.DirectUrl -> listOfNotNull(run(kind.value, listOf("--no-playlist", "--print", METADATA_FORMAT)).lineSequence().mapNotNull(::parseMetadataLine).firstOrNull())
            is InputKind.YouTubePlaylist -> run(kind.value, listOf("--flat-playlist", "--playlist-end", "200", "--print", PLAYLIST_FORMAT)).lineSequence().mapNotNull(::parseFlatPlaylistLine).take(200).toList()
            is InputKind.SpotifyUrl -> throw ExtractorError.Unsupported
        }.ifEmpty { throw ExtractorError.NoResults }
    } }

    suspend fun fetchRelated(anchor: TrackItem): List<TrackItem> = withContext(Dispatchers.IO) {
        val search = "ytsearch50:${anchor.title} ${anchor.artist ?: ""} audio"
        val searchArgs = listOf("--flat-playlist", "--match-filter", "live_status!=is_live", "--print", SEARCH_FORMAT)
        val host = try { URI(anchor.originalUrl).host?.lowercase() } catch (_: java.net.URISyntaxException) { null }
        val youtube = host == "youtu.be" || host == "youtube.com" || host?.endsWith(".youtube.com") == true
        val safeId = anchor.id.matches(Regex("[A-Za-z0-9_-]+"))
        val raw = if (youtube && safeId) {
            try {
                run("https://www.youtube.com/watch?v=${anchor.id}&list=RD${anchor.id}", listOf("--flat-playlist", "-I", "2:50", "--print", SEARCH_FORMAT))
            } catch (_: ExtractorError) { run(search, searchArgs) }
        } else run(search, searchArgs)
        val titles = mutableSetOf(canonicalTitle(anchor.title))
        val ids = mutableSetOf<String>()
        raw.lineSequence().mapNotNull(::parseMetadataLine).filter { track ->
            val title = canonicalTitle(track.title)
            track.id != anchor.id && title.isNotEmpty() && title !in titles && ids.add(track.id) && titles.add(title)
        }.take(50).toList().ifEmpty { throw ExtractorError.NoResults }
    }

    suspend fun resolveStream(track: TrackItem, finalOnly: Boolean = false): ResolvedStream {
        withContext(Dispatchers.IO) { InputClassifier.classify(track.originalUrl) }
        val common = listOf("-j", "-f", STREAM_FORMAT, "--no-playlist")
        var lastError: ExtractorError? = null
        val clients = if (finalOnly) listOf("android,web") else listOf("visionos,default", "android,web")
        for (client in clients) {
            val stage = if (client == "android,web") Timing.Stage.STREAM_FINAL else Timing.Stage.STREAM_PRIMARY
            val started = timing.start()
            var outcome = Timing.Outcome.ERROR
            try {
                val stream = parseStreamJson(run(track.originalUrl, common + listOf("--extractor-args", "youtube:player_client=$client")))
                val finalStrategy = client == "android,web"
                if (stream != null && (finalStrategy || !stream.url.contains("c=TVHTML5"))) {
                    withContext(Dispatchers.IO) { InputClassifier.validateStreamUrl(stream.url) }
                    outcome = Timing.Outcome.SUCCESS
                    return ResolvedStream(stream, finalStrategy)
                }
                outcome = if (stream == null) Timing.Outcome.NO_RESULTS else Timing.Outcome.UNUSABLE_STREAM
            } catch (error: CancellationException) { outcome = Timing.Outcome.CANCELLED; throw error }
            catch (error: ExtractorError) { lastError = error }
            finally { timing.record(stage, outcome, started) }
        }
        throw lastError ?: ExtractorError.NoResults
    }

    private suspend fun run(target: String, args: List<String>): String {
        timing.measure(Timing.Stage.INIT_WAIT) {
            if (!isAvailable()) throw ExtractorError.MissingBinary
        }
        invocation?.let { return it(target, args) }
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
