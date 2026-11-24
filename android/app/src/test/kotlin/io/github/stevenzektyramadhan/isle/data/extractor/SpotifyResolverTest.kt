package io.github.stevenzektyramadhan.isle.data.extractor

import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import io.github.stevenzektyramadhan.isle.model.TrackItem
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpotifyResolverTest {
    private fun embed(entity: String) = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":$entity}}}}}</script>"""

    @Test fun resourcesAcceptTrackAlbumPlaylistAndNormalizeEmbedAndLocalePaths() {
        for (kind in listOf("track", "album", "playlist")) {
            for (input in listOf("spotify:$kind:Ab12", "https://open.spotify.com/$kind/Ab12?si=secret#fragment", "https://open.spotify.com/intl-id/embed/$kind/Ab12")) {
                assertEquals(SpotifyResource(kind, "Ab12"), parseSpotifyResource(input))
            }
        }
        for (input in listOf("spotify:artist:abc", "spotify:track:abc:extra", "spotify:track:", "spotify:track:a_b", "spotify:track:../etc", "https://evil.test/open.spotify.com/track/abc", "https://open.spotify.com.evil.test/track/abc", "https://user@open.spotify.com/track/abc", "https://open.spotify.com/track/abc/extra", "https://open.spotify.com/track/%61bc")) {
            assertNull(input, parseSpotifyResource(input))
        }
    }

    @Test fun embedPreservesOrderSkipsInvalidEntriesAndBuildsLazySearches() {
        val result = parseSpotifyEmbedTracks(embed("""{"trackList":[{"uri":"spotify:track:first","title":" First ","subtitle":" A\u00a0B ","duration":1999},{"title":" "},{"uri":"spotify:track:second","name":"Second","artists":[{"name":"Singer"}],"duration":2000},{"title":"Third","duration":-1}]}"""))
        assertEquals(listOf(
            TrackItem("first", "First", "A B", "ytsearch1:First A B Official Audio", 1),
            TrackItem("second", "Second", "Singer", "ytsearch1:Second Singer Official Audio", 2),
            TrackItem("Third", "Third", null, "ytsearch1:Third Official Audio", null),
        ), result)
        assertEquals("Solo", parseSpotifyEmbedTracks(embed("""{"name":"Solo"}"""))!!.single().title)
        for (html in listOf("bad", embed("{}"), embed("""{"trackList":[]}"""), embed("""{"title":2}"""))) assertNull(parseSpotifyEmbedTracks(html))
    }

    @Test fun oembedRequiresNonblankStringTitle() {
        assertEquals("Playlist", parseSpotifyOembedTitle("""{"title":"  Playlist  "}"""))
        for (json in listOf("bad", "{}", """{"title":null}""", """{"title":5}""", """{"title":" "}""")) assertNull(parseSpotifyOembedTitle(json))
    }

    @Test fun embedSuccessNeverFetchesOembedOrSearches() = runBlocking {
        val urls = mutableListOf<String>()
        val resolver = SpotifyResolver { url -> urls.add(url); embed("""{"title":"Song","subtitle":"Artist"}""") }
        val result = resolver.resolve("spotify:track:abc") { fail("unexpected search"); emptyList() }
        assertEquals("ytsearch1:Song Artist Official Audio", result.single().originalUrl)
        assertEquals(listOf("https://open.spotify.com/embed/track/abc"), urls)
    }

    @Test fun embedFailureOrMalformedBodyFallsBackToEncodedOembedThenSearch() = runBlocking {
        for (failed in listOf(false, true)) {
            val urls = mutableListOf<String>()
            val resolver = SpotifyResolver { url ->
                urls.add(url)
                if (urls.size == 1) { if (failed) throw IOException("offline") else "bad embed" }
                else """{"title":" Collection "}"""
            }
            val expected = listOf(TrackItem("yt", "Found", null, "https://youtube.com/watch?v=yt", null))
            assertEquals(expected, resolver.resolve("spotify:album:abc") { query -> assertEquals("Collection", query); expected })
            assertEquals(listOf("https://open.spotify.com/embed/album/abc", "https://open.spotify.com/oembed?url=spotify%3Aalbum%3Aabc"), urls)
        }
    }

    @Test fun securityRejectionAndCancellationNeverFallBack() = runBlocking {
        for (error in listOf(ExtractorError.PrivateNetwork, CancellationException("cancelled"))) {
            var calls = 0
            val resolver = SpotifyResolver { calls++; throw error }
            try { resolver.resolve("spotify:playlist:abc") { fail("unexpected search"); emptyList() }; fail("expected error") }
            catch (actual: Exception) { assertSame(error, actual) }
            assertEquals(1, calls)
        }
    }

    @Test fun dnsRejectsMixedPrivateAnswersAndRebindingOnActualLookup() {
        val public = InetAddress.getByName("8.8.8.8")
        val private = InetAddress.getByName("127.0.0.1")
        var lookup = 0
        val dns = SpotifyDns { lookup++; if (lookup == 1) listOf(public) else listOf(public, private) }
        assertEquals(listOf(public), dns.lookup("open.spotify.com"))
        try { dns.lookup("open.spotify.com"); fail("rebound DNS accepted") } catch (_: UnsafeSpotifyAddress) { }
        for (ip in listOf("0.0.0.0", "10.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.0.1", "100.64.0.1", "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fc00::1", "::ffff:127.0.0.1")) {
            try { SpotifyDns { listOf(InetAddress.getByName(ip)) }.lookup("open.spotify.com"); fail("accepted $ip") } catch (_: UnsafeSpotifyAddress) { }
        }
        for (host in listOf("localhost", "a.localhost", "router.local", "service.internal")) {
            try { SpotifyDns { fail("local name reached DNS"); emptyList() }.lookup(host); fail("accepted $host") } catch (_: UnsafeSpotifyAddress) { }
        }
        try { SpotifyDns { emptyList() }.lookup("open.spotify.com"); fail("accepted empty DNS") } catch (_: java.net.UnknownHostException) { }
    }
}
