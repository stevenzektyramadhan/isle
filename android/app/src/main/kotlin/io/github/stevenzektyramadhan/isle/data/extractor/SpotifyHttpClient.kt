package io.github.stevenzektyramadhan.isle.data.extractor

import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer

internal class UnsafeSpotifyAddress : UnknownHostException("Private network addresses are not allowed")

// OkHttp connects using these exact answers. Never validate with one lookup and
// hand an unchecked second lookup to the HTTP stack (DNS rebinding).
internal class SpotifyDns(private val resolve: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) }) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase().trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) throw UnsafeSpotifyAddress()
        val addresses = resolve(hostname)
        if (addresses.isEmpty()) throw UnknownHostException("No DNS answers")
        if (addresses.any { address ->
            val bytes = address.address.map { it.toInt() and 255 }
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
                if (bytes.size == 4) bytes[0] == 0 || bytes[0] >= 224 || (bytes[0] == 100 && bytes[1] in 64..127)
                else bytes[0] and 0xfe == 0xfc
        }) throw UnsafeSpotifyAddress()
        return addresses
    }
}

internal class SpotifyHttpClient(
    private val dns: SpotifyDns = SpotifyDns(),
    builder: OkHttpClient.Builder = OkHttpClient.Builder(),
) {
    private val client = builder
        .dns(dns)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun get(raw: String): String = withTimeoutOrNull(5_000) {
        var url = raw.toHttpUrlOrNull() ?: throw ExtractorError.Failed
        repeat(11) { hop ->
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw ExtractorError.Failed
            // Numeric hosts bypass OkHttp DNS; validate them here as well.
            val host = url.host.lowercase().trimEnd('.')
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) throw ExtractorError.PrivateNetwork
            if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
                try { dns.lookup(host) } catch (_: UnsafeSpotifyAddress) { throw ExtractorError.PrivateNetwork }
            }
            val response = request(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build())
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                if (hop == 10) throw ExtractorError.Failed
                url = response.location?.let(url::resolve) ?: throw ExtractorError.Failed
            } else {
                if (response.code !in 200..299) throw ExtractorError.Failed
                return@withTimeoutOrNull response.body
            }
        }
        throw ExtractorError.Failed
    } ?: throw ExtractorError.Timeout

    private data class Result(val code: Int, val location: String?, val body: String)

    private suspend fun request(request: Request): Result = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val error = when {
                    generateSequence<Throwable>(e) { it.cause }.any { it is UnsafeSpotifyAddress } -> ExtractorError.PrivateNetwork
                    e is java.io.InterruptedIOException -> ExtractorError.Timeout
                    else -> ExtractorError.Failed
                }
                continuation.resumeWith(kotlin.Result.failure(error))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = if (it.code in 200..299) {
                            val source = it.body.source()
                            val buffer = Buffer()
                            val limit = 2L * 1024 * 1024
                            while (buffer.size <= limit) {
                                if (source.read(buffer, minOf(8192, limit + 1 - buffer.size)) == -1L) break
                            }
                            if (buffer.size > limit) throw ExtractorError.Failed
                            val charset = it.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                            buffer.readString(charset)
                        } else ""
                        Result(it.code, it.header("Location"), body)
                    }
                    continuation.resumeWith(kotlin.Result.success(result))
                } catch (error: ExtractorError) { continuation.resumeWith(kotlin.Result.failure(error)) }
                catch (error: IOException) { continuation.resumeWith(kotlin.Result.failure(if (error is java.io.InterruptedIOException) ExtractorError.Timeout else ExtractorError.Failed)) }
            }
        })
    }
}
