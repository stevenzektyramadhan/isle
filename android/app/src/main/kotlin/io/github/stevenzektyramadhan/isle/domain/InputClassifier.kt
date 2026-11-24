package io.github.stevenzektyramadhan.isle.domain

import java.net.InetAddress
import java.net.URI

sealed interface InputKind {
    data class SearchQuery(val value: String) : InputKind
    data class DirectUrl(val value: String) : InputKind
    data class YouTubePlaylist(val value: String) : InputKind
    data class SpotifyUrl(val value: String) : InputKind
}

sealed class ExtractorError(message: String) : Exception(message) {
    data object EmptyInput : ExtractorError("Input is empty")
    data object PrivateNetwork : ExtractorError("Private network addresses are not allowed")
    data object MissingBinary : ExtractorError("yt-dlp is unavailable")
    data object Timeout : ExtractorError("Request timed out")
    data object NoResults : ExtractorError("No tracks found")
    data object Unsupported : ExtractorError("This input is not supported yet")
    data object Failed : ExtractorError("Extraction failed")
}

object InputClassifier {
    fun validateStreamUrl(raw: String, resolve: (String) -> List<InetAddress> = { host ->
        try { InetAddress.getAllByName(host).toList() } catch (_: java.net.UnknownHostException) { emptyList() }
    }) {
        val uri = try { URI(raw) } catch (_: java.net.URISyntaxException) { throw ExtractorError.Failed }
        if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host == null) throw ExtractorError.Failed
        validateHost(uri.host.trim('[', ']'), resolve, requireResolved = true)
    }

    fun classify(raw: String, resolve: (String) -> List<InetAddress> = { host ->
        try { InetAddress.getAllByName(host).toList() } catch (_: java.net.UnknownHostException) { emptyList() }
    }): InputKind {
        val input = raw.trim()
        if (input.isEmpty()) throw ExtractorError.EmptyInput
        if (!input.startsWith("http://") && !input.startsWith("https://") && !input.startsWith("spotify:")) return InputKind.SearchQuery(input)
        if (!input.startsWith("spotify:")) {
            val host = try { URI(input).host?.trim('[', ']') } catch (_: Exception) { null }
            if (host == null) throw ExtractorError.PrivateNetwork
            validateHost(host, resolve)
        }
        return when {
            input.contains("open.spotify.com") || input.startsWith("spotify:") -> InputKind.SpotifyUrl(input)
            input.contains("list=") || input.contains("/playlist") -> InputKind.YouTubePlaylist(input)
            else -> InputKind.DirectUrl(input)
        }
    }

    private fun validateHost(host: String, resolve: (String) -> List<InetAddress>, requireResolved: Boolean = false) {
        val domain = host.lowercase().trimEnd('.')
        if (domain == "localhost" || domain.endsWith(".localhost") || domain.endsWith(".local") || domain.endsWith(".internal")) throw ExtractorError.PrivateNetwork
        val addresses = resolve(host)
        if ((requireResolved && addresses.isEmpty()) || addresses.any(::privateAddress)) throw ExtractorError.PrivateNetwork
    }

    private fun privateAddress(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 255 }
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        return if (bytes.size == 4) (bytes[0] == 100 && bytes[1] in 64..127) || bytes.all { it == 255 }
        else bytes[0] and 0xfe == 0xfc
    }
}

fun canonicalTitle(title: String): String = title.substringBefore('(').substringBefore('[')
    .map { if (it.isLetterOrDigit()) it.lowercaseChar() else ' ' }.joinToString("").trim().split(Regex("\\s+")).joinToString(" ")
