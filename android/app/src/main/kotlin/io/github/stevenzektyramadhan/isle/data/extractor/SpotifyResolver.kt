package io.github.stevenzektyramadhan.isle.data.extractor

import io.github.stevenzektyramadhan.isle.domain.ExtractorError
import io.github.stevenzektyramadhan.isle.model.TrackItem
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class SpotifyResource(val kind: String, val id: String)

internal fun parseSpotifyResource(input: String): SpotifyResource? {
    val clean = input.trim().substringBefore('?').substringBefore('#')
    val parts = if (clean.startsWith("spotify:")) {
        clean.removePrefix("spotify:").split(':')
    } else {
        val uri = try { URI(clean) } catch (_: java.net.URISyntaxException) { return null }
        if (uri.scheme !in listOf("https", "http") || !uri.host.equals("open.spotify.com", true) || uri.rawUserInfo != null || uri.port !in listOf(-1, 80, 443)) return null
        uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() && it != "embed" && !it.startsWith("intl-") }
    }
    if (parts.size != 2 || parts[0] !in listOf("track", "album", "playlist") || !parts[1].matches(Regex("[A-Za-z0-9]+"))) return null
    return SpotifyResource(parts[0], parts[1])
}

private fun parseObject(raw: String): JsonObject? = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: IllegalArgumentException) { null }
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun parseSpotifyEmbedTracks(html: String): List<TrackItem>? {
    val marker = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
    if (!html.contains(marker)) return null
    val root = parseObject(html.substringAfter(marker).substringBefore("</script>")) ?: return null
    var entity = root
    for (key in listOf("props", "pageProps", "state", "data", "entity")) entity = entity[key] as? JsonObject ?: return null
    val entries = (entity["trackList"] as? JsonArray) ?: listOf(entity)
    return entries.mapNotNull { value ->
        val entry = value as? JsonObject ?: return@mapNotNull null
        val title = (if (entry.containsKey("title")) entry.string("title") else entry.string("name"))?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val rawArtist = entry.string("subtitle") ?: ((entry["artists"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("name")
        val artist = rawArtist?.replace('\u00a0', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        val id = entry.string("uri")?.substringAfterLast(':')?.takeIf { it.isNotEmpty() } ?: title
        val duration = (entry["duration"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toULongOrNull()?.div(1000u)?.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
        TrackItem(id, title, artist, "ytsearch1:$title${artist?.let { " $it" }.orEmpty()} Official Audio", duration)
    }.takeIf { it.isNotEmpty() }
}

internal fun parseSpotifyOembedTitle(raw: String): String? = parseObject(raw)?.string("title")?.trim()?.takeIf { it.isNotEmpty() }

internal class SpotifyResolver(private val fetch: suspend (String) -> String = SpotifyHttpClient()::get) {
    suspend fun resolve(input: String, search: suspend (String) -> List<TrackItem>): List<TrackItem> {
        val resource = parseSpotifyResource(input) ?: throw ExtractorError.NoResults
        val tracks = try {
            parseSpotifyEmbedTracks(fetch("https://open.spotify.com/embed/${resource.kind}/${resource.id}"))
        } catch (error: CancellationException) { throw error }
        catch (error: ExtractorError.PrivateNetwork) { throw error }
        catch (_: IOException) { null }
        catch (_: ExtractorError) { null }
        if (tracks != null) return tracks
        val url = "https://open.spotify.com/oembed".toHttpUrl().newBuilder().addQueryParameter("url", input).build()
        val title = parseSpotifyOembedTitle(fetch(url.toString())) ?: throw ExtractorError.NoResults
        return search(title).ifEmpty { throw ExtractorError.NoResults }
    }
}
