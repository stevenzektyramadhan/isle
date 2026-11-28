package io.github.stevenzektyramadhan.isle.data.extractor

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request

class NewPipeDownloaderTest {
    private fun request(url: String) = Request.newBuilder().get(url).build()

    @Test fun privateRedirectsAreRejectedBeforeAnotherRequest() {
        for (location in listOf("https://127.0.0.1/secret", "https://[::1]/", "https://host.internal/", "https://host.local/", "http://example.com/")) {
            var calls = 0
            val downloader = NewPipeDownloader(builder = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("redirect")
                    .header("Location", location).body("".toResponseBody()).build()
            })
            try { downloader.execute(request("https://youtube.com/")); fail("unsafe redirect accepted") } catch (_: IOException) { }
            assertEquals(1, calls)
        }
    }

    @Test fun connectionDnsRejectsPrivateAnswers() {
        val downloader = NewPipeDownloader(SpotifyDns { listOf(InetAddress.getByName("10.0.0.1")) })
        try { downloader.execute(request("https://youtube.com/")); fail("private DNS accepted") } catch (_: IOException) { }
    }

    @Test fun oversizedBodyIsRejectedAndLimitIsAccepted() {
        for (size in listOf(2 * 1024 * 1024, 2 * 1024 * 1024 + 1)) {
            val downloader = NewPipeDownloader(builder = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                    .body("a".repeat(size).toResponseBody()).build()
            })
            try {
                assertEquals(size, downloader.execute(request("https://youtube.com/")).responseBody().length)
                assertEquals(2 * 1024 * 1024, size)
            } catch (error: IOException) { assertEquals(2 * 1024 * 1024 + 1, size) }
        }
    }

    @Test fun gzipExpandedBodyIsBounded() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nConnection: close\r\n\r\n".toByteArray())
                    GZIPOutputStream(output).use { it.write(ByteArray(2 * 1024 * 1024 + 1) { 97 }) }
                }
            } catch (_: IOException) { }
        }
        try {
            val downloader = NewPipeDownloader(builder = OkHttpClient.Builder().addInterceptor { chain ->
                val local = chain.request().url.newBuilder().scheme("http").host("127.0.0.1").port(server.localPort).build()
                chain.proceed(chain.request().newBuilder().url(local).build())
            })
            try { downloader.execute(request("https://youtube.com/")); fail("oversized decoded body accepted") } catch (_: IOException) { }
        } finally { server.close(); worker.join() }
    }

    @Test fun crossOriginRedirectStripsCredentialsAndLoopsAreBounded() {
        val seen = mutableListOf<okhttp3.Request>()
        val downloader = NewPipeDownloader(builder = OkHttpClient.Builder().addInterceptor { chain ->
            seen.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("redirect")
                .header("Location", "https://example.com/next").body("".toResponseBody()).build()
        })
        val input = Request.newBuilder().get("https://youtube.com/").setHeader("Authorization", "secret").setHeader("Cookie", "secret").build()
        try { downloader.execute(input); fail("redirect loop accepted") } catch (_: IOException) { }
        assertEquals(11, seen.size)
        assertNull(seen[1].header("Authorization"))
        assertNull(seen[1].header("Cookie"))
    }
}
