package io.github.stevenzektyramadhan.isle

import io.github.stevenzektyramadhan.isle.data.*
import io.github.stevenzektyramadhan.isle.domain.*
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun metadataAndPlaylistRows() {
        val track = parseMetadataLine("id:::Title:::Artist:::https://example.test/v:::203.9")
        assertEquals(203L, track?.durationSecs)
        assertNull(parseMetadataLine("id:::Title:::Artist:::NA:::203"))
        assertNull(parseMetadataLine("id:::Title:::Artist:::url:::1:::extra"))
        assertNull(parseMetadataLine("id:::Title::::::url:::NA")?.artist)
        assertNotNull(parseFlatPlaylistLine("id:::Title:::url"))
        assertNull(parseFlatPlaylistLine("id::::::url"))
        assertNull(parseFlatPlaylistLine("id:::Title:::NA"))
    }

    @Test fun streamJsonAndFallback() {
        val stream = parseStreamJson("""{"url":"https://example.com/a","http_headers":{"user-agent":"Agent"}}""")
        assertEquals("Agent", stream?.userAgent)
        assertEquals("https://example.com/a", stream?.url)
        assertEquals("https://example.com/b", parseStreamJson("bad\nhttps://example.com/b")?.url)
        assertNull(parseStreamJson("{broken}"))
        assertNull(parseStreamJson("""{"url":42}"""))
    }

    @Test fun classificationBlocksLocalAndDnsPrivate() {
        val public = { _: String -> listOf(InetAddress.getByName("93.184.216.34")) }
        assertEquals(InputKind.SearchQuery("song"), InputClassifier.classify(" song ", public))
        assertTrue(InputClassifier.classify("https://youtube.com/playlist?list=x", public) is InputKind.YouTubePlaylist)
        assertTrue(InputClassifier.classify("spotify:track:abc", public) is InputKind.SpotifyUrl)
        val private = { _: String -> listOf(InetAddress.getByName("10.0.0.1")) }
        assertThrows(ExtractorError.PrivateNetwork::class.java) { InputClassifier.classify("http://example.test", private) }
        assertThrows(ExtractorError.PrivateNetwork::class.java) { InputClassifier.classify("http://localhost/", public) }
        assertThrows(ExtractorError.PrivateNetwork::class.java) { InputClassifier.classify("http://127.1/") }
        assertThrows(ExtractorError.EmptyInput::class.java) { InputClassifier.classify(" ", public) }
        assertEquals(canonicalTitle("@coldplay - Yellow (Lyrics)"), canonicalTitle("Coldplay - Yellow [Official]"))
    }

    @Test fun resolvedStreamRequiresHttpAndPublicDns() {
        val public = { _: String -> listOf(InetAddress.getByName("93.184.216.34")) }
        val mixed = { _: String -> listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.1")) }
        InputClassifier.validateStreamUrl("https://example.test/audio", public)
        assertThrows(ExtractorError.Failed::class.java) { InputClassifier.validateStreamUrl("file:///tmp/audio", public) }
        assertThrows(ExtractorError.Failed::class.java) { InputClassifier.validateStreamUrl("ftp://example.test/audio", public) }
        assertThrows(ExtractorError.PrivateNetwork::class.java) { InputClassifier.validateStreamUrl("http://localhost/audio", public) }
        assertThrows(ExtractorError.PrivateNetwork::class.java) { InputClassifier.validateStreamUrl("https://example.test/audio", mixed) }
        assertThrows(ExtractorError.PrivateNetwork::class.java) { InputClassifier.validateStreamUrl("https://example.test/audio") { emptyList() } }
    }
}
