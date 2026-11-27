package io.github.stevenzektyramadhan.isle.update

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class AppUpdateCheckerTest {
    private val fullPage = List(100) { """{"tag_name":"v1.0.0","draft":false}""" }.joinToString(",", "[", "]")

    private fun pagedChecker(pages: (Int) -> String): AppUpdateChecker {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val page = chain.request().url.queryParameter("page")?.toInt() ?: 1
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(pages(page).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return AppUpdateChecker("1.0.0", client)
    }

    @Test fun findsAndroidReleaseOnLaterPage() = runBlocking {
        val result = pagedChecker { page ->
            if (page == 1) fullPage else """[{"tag_name":"android-v2.0.0","draft":false}]"""
        }.check()
        assertEquals(AppUpdateResult.Available("2.0.0", "https://github.com/stevenzektyramadhan/isle/releases/tag/android-v2.0.0"), result)
    }

    @Test fun returnsErrorWhenFullPagesExhaustLimit() = runBlocking {
        var requests = 0
        val result = pagedChecker { requests++; fullPage }.check()
        assertEquals(AppUpdateResult.Error, result)
        assertTrue(requests in 2..20)
    }

    @Test fun rejectsMalformedLaterPageEvenAfterFindingUpdate() = runBlocking {
        val first = fullPage.replaceFirst("v1.0.0", "android-v2.0.0")
        assertEquals(AppUpdateResult.Error, pagedChecker { if (it == 1) first else "[{}]" }.check())
    }

    @Test fun rejectsOversizedLaterPage() = runBlocking {
        assertEquals(AppUpdateResult.Error, pagedChecker { if (it == 1) fullPage else "[" + " ".repeat(1_048_577) + "]" }.check())
    }

    @Test fun cancellationDuringLaterPageDoesNotReturnCurrent() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val page = chain.request().url.queryParameter("page")?.toInt() ?: 1
            if (page == 2) {
                entered.complete(Unit)
                release.await()
                throw java.io.IOException("cancelled")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(fullPage.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = async { AppUpdateChecker("1.0.0", client).check() }
        try {
            withTimeout(5_000) { entered.await() }
            result.cancelAndJoin()
            assertTrue(result.isCancelled)
        } finally {
            release.countDown()
        }
    }

    private fun checker(version: String, body: String, abis: List<String> = listOf("arm64-v8a")): AppUpdateChecker {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return AppUpdateChecker(version, client, abis)
    }

    @Test fun selectsNewestAndroidPrereleaseAndCanonicalUrl() = runBlocking {
        val result = checker("1.9.0", """[
            {"tag_name":"v99.0.0","draft":false,"prerelease":false},
            {"tag_name":"android-v2.0.0","draft":false,"prerelease":true,"html_url":"https://evil.test/"},
            {"tag_name":"android-v10.0.0","draft":true,"prerelease":true},
            {"tag_name":"android-v1.10.0","draft":false,"prerelease":true}
        ]""").check()
        assertEquals(AppUpdateResult.Available("2.0.0", "https://github.com/stevenzektyramadhan/isle/releases/tag/android-v2.0.0"), result)
    }

    @Test fun comparesNumericComponentsAndRejectsMalformedTags() = runBlocking {
        val result = checker("1.9.0", """[
            {"tag_name":"android-v01.20.0","draft":false},
            {"tag_name":"android-v1.10.0-extra","draft":false},
            {"tag_name":"android-v1.10.0","draft":false}
        ]""").check()
        assertEquals(AppUpdateResult.Available("1.10.0", "https://github.com/stevenzektyramadhan/isle/releases/tag/android-v1.10.0"), result)
    }

    @Test fun returnsCurrentWhenNoNewerAndroidReleaseExists() = runBlocking {
        assertEquals(AppUpdateResult.Current, checker("2.0.0", """[{"tag_name":"android-v1.0.0","draft":false}]""").check())
    }

    @Test fun returnsErrorForMalformedInputAndInstalledVersion() = runBlocking {
        assertTrue(checker("1.0.0", "not json").check() is AppUpdateResult.Error)
        assertTrue(checker("1.0", "[]").check() is AppUpdateResult.Error)
    }

    @Test fun returnsErrorForOversizedBody() = runBlocking {
        assertTrue(checker("1.0.0", "[" + " ".repeat(1_048_577) + "]").check() is AppUpdateResult.Error)
    }

    @Test fun returnsErrorForTimeout() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { throw SocketTimeoutException() }).build()
        assertTrue(AppUpdateChecker("1.0.0", client).check() is AppUpdateResult.Error)
    }

    @Test fun selectsMatchingAbiApkAsset() = runBlocking {
        val body = """[{
            "tag_name":"android-v2.0.0",
            "draft":false,
            "assets":[
                {"name":"app-x86_64-release.apk","browser_download_url":"https://github.com/stevenzektyramadhan/isle/releases/download/android-v2.0.0/app-x86_64-release.apk"},
                {"name":"app-arm64-v8a-release.apk","browser_download_url":"https://github.com/stevenzektyramadhan/isle/releases/download/android-v2.0.0/app-arm64-v8a-release.apk"}
            ]
        }]"""
        val result = checker("1.0.0", body, listOf("arm64-v8a", "armeabi-v7a")).check()
        assertEquals(
            AppUpdateResult.Available(
                "2.0.0",
                "https://github.com/stevenzektyramadhan/isle/releases/tag/android-v2.0.0",
                "https://github.com/stevenzektyramadhan/isle/releases/download/android-v2.0.0/app-arm64-v8a-release.apk",
            ),
            result,
        )
    }

    @Test fun rejectsUntrustedApkAssetUrl() = runBlocking {
        val body = """[{
            "tag_name":"android-v2.0.0",
            "draft":false,
            "assets":[
                {"name":"app-arm64-v8a-release.apk","browser_download_url":"https://evil.test/app-arm64-v8a-release.apk"}
            ]
        }]"""
        val result = checker("1.0.0", body, listOf("arm64-v8a")).check()
        assertEquals(
            AppUpdateResult.Available(
                "2.0.0",
                "https://github.com/stevenzektyramadhan/isle/releases/tag/android-v2.0.0",
                null,
            ),
            result,
        )
    }

    @Test fun fallsBackToUniversalApkWhenAbiMissing() = runBlocking {
        val body = """[{
            "tag_name":"android-v2.0.0",
            "draft":false,
            "assets":[
                {"name":"app-universal-release.apk","browser_download_url":"https://github.com/stevenzektyramadhan/isle/releases/download/android-v2.0.0/app-universal-release.apk"}
            ]
        }]"""
        val result = checker("1.0.0", body, listOf("armeabi-v7a")).check()
        assertEquals(
            AppUpdateResult.Available(
                "2.0.0",
                "https://github.com/stevenzektyramadhan/isle/releases/tag/android-v2.0.0",
                "https://github.com/stevenzektyramadhan/isle/releases/download/android-v2.0.0/app-universal-release.apk",
            ),
            result,
        )
    }
}
