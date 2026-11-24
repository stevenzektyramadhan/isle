package io.github.stevenzektyramadhan.isle.data.extractor

import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import java.net.InetAddress
import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SpotifyHttpClientTest {
    private suspend fun withBody(size: Int, chunked: Boolean, gzip: Boolean = false, check: suspend (SpotifyHttpClient) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val output = socket.getOutputStream()
                    val headers = if (chunked || gzip) "Transfer-Encoding: chunked\r\n" else "Content-Length: $size\r\n"
                    output.write("HTTP/1.1 200 OK\r\n${headers}${if (gzip) "Content-Encoding: gzip\r\n" else ""}Connection: close\r\n\r\n".toByteArray())
                    val block = ByteArray(8192) { 'a'.code.toByte() }
                    val compressed = if (gzip) ByteArrayOutputStream() else null
                    val body = if (compressed != null) GZIPOutputStream(compressed) else null
                    var remaining = size
                    while (remaining > 0) {
                        val count = minOf(remaining, block.size)
                        if (body != null) body.write(block, 0, count) else {
                            if (chunked) output.write("${count.toString(16)}\r\n".toByteArray())
                            output.write(block, 0, count)
                            if (chunked) output.write("\r\n".toByteArray())
                        }
                        remaining -= count
                    }
                    if (body != null && compressed != null) {
                        body.close()
                        val bytes = compressed.toByteArray()
                        output.write("${bytes.size.toString(16)}\r\n".toByteArray())
                        output.write(bytes)
                        output.write("\r\n".toByteArray())
                    }
                    if (chunked || gzip) output.write("0\r\n\r\n".toByteArray())
                }
            } catch (_: java.io.IOException) { /* Client may close an oversized response early. */ }
        }
        try {
            val http = SpotifyHttpClient(builder = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.newBuilder().scheme("http").host("127.0.0.1").port(server.localPort).build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            })
            check(http)
        } finally { server.close(); worker.join() }
    }

    @Test fun oversizedFixedLengthBodyIsRejected() = runBlocking {
        withBody(2 * 1024 * 1024 + 1, chunked = false) { http ->
            try { http.get("https://open.spotify.com/start"); fail("oversized body accepted") } catch (_: ExtractorError.Failed) { }
        }
    }

    @Test fun oversizedChunkedBodyIsRejected() = runBlocking {
        withBody(2 * 1024 * 1024 + 1, chunked = true) { http ->
            try { http.get("https://open.spotify.com/start"); fail("oversized body accepted") } catch (_: ExtractorError.Failed) { }
        }
    }

    @Test fun oversizedGzipDecodedBodyIsRejected() = runBlocking {
        withBody(2 * 1024 * 1024 + 1, chunked = true, gzip = true) { http ->
            try { http.get("https://open.spotify.com/start"); fail("oversized decoded body accepted") } catch (_: ExtractorError.Failed) { }
        }
    }

    @Test fun bodyAtDecodedLimitIsAccepted() = runBlocking {
        withBody(2 * 1024 * 1024, chunked = true, gzip = true) { http ->
            assertEquals("a".repeat(2 * 1024 * 1024), http.get("https://open.spotify.com/start"))
        }
    }

    @Test fun redirectsToPrivateNumericAndLocalNamesAreRejectedBeforeNextRequest() = runBlocking {
        for (location in listOf("http://127.0.0.1/secret", "http://169.254.169.254/latest", "http://[::1]/secret", "https://router.local/secret", "https://a.internal/secret")) {
            var calls = 0
            val http = SpotifyHttpClient(builder = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                    .header("Location", location).body("".toResponseBody()).build()
            })
            try { http.get("https://open.spotify.com/embed/track/abc"); fail("unsafe redirect followed") } catch (_: ExtractorError.PrivateNetwork) { }
            assertEquals(1, calls)
        }
    }

    @Test fun relativeRedirectsResolveAndRedirectLoopsAreBounded() = runBlocking {
        val urls = mutableListOf<String>()
        val http = SpotifyHttpClient(builder = OkHttpClient.Builder().addInterceptor { chain ->
            urls.add(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (urls.size == 1) 302 else 200).message("Response")
                .header("Location", "/next").body("payload".toResponseBody()).build()
        })
        assertEquals("payload", http.get("https://open.spotify.com/embed/track/abc"))
        assertEquals(listOf("https://open.spotify.com/embed/track/abc", "https://open.spotify.com/next"), urls)
        var calls = 0
        val loop = SpotifyHttpClient(builder = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "/loop").body("".toResponseBody()).build()
        })
        try { loop.get("https://open.spotify.com/start"); fail("unbounded loop") } catch (_: ExtractorError.Failed) { }
        assertEquals(11, calls)
    }

    @Test fun actualConnectionDnsAnswersAreRejectedWithoutOpeningASocket() = runBlocking {
        var lookups = 0
        val http = SpotifyHttpClient(SpotifyDns { lookups++; listOf(InetAddress.getByName("10.0.0.1")) })
        try { http.get("https://open.spotify.com/embed/track/abc"); fail("private DNS accepted") } catch (_: ExtractorError.PrivateNetwork) { }
        assertEquals(1, lookups)
    }

    @Test fun timeoutAndHttpErrorsAreTyped() = runBlocking {
        val timeout = SpotifyHttpClient(builder = OkHttpClient.Builder().addInterceptor { throw java.net.SocketTimeoutException() })
        try { timeout.get("https://open.spotify.com/start"); fail("timeout accepted") } catch (_: ExtractorError.Timeout) { }
        val failed = SpotifyHttpClient(builder = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("Failed").body("bad".toResponseBody()).build()
        })
        try { failed.get("https://open.spotify.com/start"); fail("HTTP error accepted") } catch (_: ExtractorError.Failed) { }
    }
}
