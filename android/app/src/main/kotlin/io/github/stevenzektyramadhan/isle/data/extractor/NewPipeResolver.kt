package io.github.stevenzektyramadhan.isle.data.extractor

import io.github.stevenzektyramadhan.isle.model.StreamInfo
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

internal object NewPipeResolver {
    private val initialized by lazy {
        NewPipe.init(NewPipeDownloader())
    }

    suspend fun resolve(url: String): StreamInfo? = runInterruptible(Dispatchers.IO) {
        initialized
        org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(url).audioStreams
            .filter { it.isUrl }.maxByOrNull { it.averageBitrate }?.let { StreamInfo(it.content, null) }
    }
}

internal class NewPipeDownloader(
    private val dns: SpotifyDns = SpotifyDns(),
    builder: OkHttpClient.Builder = OkHttpClient.Builder(),
) : Downloader() {
    private val client = builder.dns(dns).proxy(Proxy.NO_PROXY).followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()

    override fun execute(request: Request): Response {
        var url = request.url().toHttpUrlOrNull() ?: throw IOException("Invalid URL")
        var method = request.httpMethod()
        var body = request.dataToSend()?.toRequestBody()
            ?: if (method == "POST") ByteArray(0).toRequestBody() else null
        var includeCredentials = true
        repeat(11) { hop ->
            val host = url.host.lowercase().trimEnd('.')
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || host == "localhost" ||
                host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) throw IOException("Unsafe address")
            if (host.contains(':') || host.all { it.isDigit() || it == '.' }) dns.lookup(host)
            val builder = okhttp3.Request.Builder().url(url)
            request.headers().forEach { (name, values) ->
                if (includeCredentials || !name.equals("Authorization", true) && !name.equals("Cookie", true) && !name.equals("Proxy-Authorization", true)) {
                    values.forEach { builder.addHeader(name, it) }
                }
            }
            client.newCall(builder.method(method, body).build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    if (hop == 10) throw IOException("Too many redirects")
                    val next = response.header("Location")?.let(url::resolve) ?: throw IOException("Invalid redirect")
                    if (url.scheme == "https" && next.scheme != "https") throw IOException("Unsafe redirect")
                    if (url.scheme != next.scheme || url.host != next.host || url.port != next.port) includeCredentials = false
                    if (response.code == 303 && method != "HEAD" || response.code in listOf(301, 302) && method == "POST") {
                        method = "GET"
                        body = null
                    }
                    url = next
                } else {
                    val source = response.body.source()
                    val buffer = Buffer()
                    val limit = 2L * 1024 * 1024
                    while (buffer.size <= limit && source.read(buffer, minOf(8192, limit + 1 - buffer.size)) != -1L) { }
                    if (buffer.size > limit) throw IOException("Response too large")
                    val charset = response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    return Response(response.code, response.message, response.headers.toMultimap(), buffer.readString(charset), url.toString())
                }
            }
        }
        throw IOException("Too many redirects")
    }
}
