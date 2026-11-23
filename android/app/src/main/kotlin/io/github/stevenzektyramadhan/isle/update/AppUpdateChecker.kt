package io.github.stevenzektyramadhan.isle.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed interface AppUpdateResult {
    data class Available(val version: String, val releaseUrl: String) : AppUpdateResult
    data object Current : AppUpdateResult
    data object Error : AppUpdateResult
}

/** The caller supplies the installed package's versionName. No installation is performed. */
class AppUpdateChecker(
    private val installedVersion: String,
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun check(): AppUpdateResult = withContext(Dispatchers.IO) {
        val current = Version.parse(installedVersion) ?: return@withContext AppUpdateResult.Error
        try {
            var newest: Version? = null
            for (page in 1..MAX_PAGES) {
                coroutineContext.ensureActive()
                val request = Request.Builder()
                    .url("https://api.github.com/repos/stevenzektyramadhan/isle/releases?per_page=100&page=$page")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val text = fetch(request) ?: return@withContext AppUpdateResult.Error
                val releases = Json.parseToJsonElement(text) as? JsonArray ?: return@withContext AppUpdateResult.Error
                if (releases.size > PAGE_SIZE) return@withContext AppUpdateResult.Error
                for (element in releases) {
                    coroutineContext.ensureActive()
                    val release = element as? JsonObject ?: return@withContext AppUpdateResult.Error
                    val draft = (release["draft"] as? JsonPrimitive)?.booleanOrNull ?: return@withContext AppUpdateResult.Error
                    if (draft) continue
                    val tag = (release["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return@withContext AppUpdateResult.Error
                    if (!tag.startsWith("android-v")) continue
                    val version = Version.parse(tag.removePrefix("android-v")) ?: continue
                    if (version > current && (newest == null || version > newest)) newest = version
                }
                if (releases.size < PAGE_SIZE) {
                    return@withContext newest?.let {
                        AppUpdateResult.Available(it.text, "https://github.com/stevenzektyramadhan/isle/releases/tag/android-v${it.text}")
                    } ?: AppUpdateResult.Current
                }
            }
            AppUpdateResult.Error
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            AppUpdateResult.Error
        } catch (_: IllegalArgumentException) {
            AppUpdateResult.Error
        }
    }

    private suspend fun fetch(request: Request): String? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body
                    val result = try {
                        if (!response.isSuccessful || body.contentLength() > MAX_BYTES) null
                        else {
                            val source = body.source()
                            val buffer = okio.Buffer()
                            while (buffer.size <= MAX_BYTES) {
                                val read = source.read(buffer, MAX_BYTES + 1L - buffer.size)
                                if (read == -1L) break
                            }
                            if (buffer.size > MAX_BYTES) null else buffer.readUtf8()
                        }
                    } catch (_: IOException) { null }
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        })
    }

    private companion object {
        const val MAX_BYTES = 1_048_576L
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 10
    }
}

private data class Version(val text: String, val parts: List<String>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for ((left, right) in parts.zip(other.parts)) {
            val result = left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
            if (result != 0) return result
        }
        return 0
    }

    companion object {
        private val pattern = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")
        fun parse(text: String): Version? = if (pattern.matches(text)) Version(text, text.split('.')) else null
    }
}
