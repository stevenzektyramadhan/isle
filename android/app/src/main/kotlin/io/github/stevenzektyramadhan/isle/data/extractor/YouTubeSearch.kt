package io.github.stevenzektyramadhan.isle.data.extractor

import io.github.stevenzektyramadhan.isle.model.TrackItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal object YouTubeSearch {
    private val http = SpotifyHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun searchFirst(query: String): TrackItem? {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20241120.01.00")
                }
            }
            put("query", query)
            put("params", "EgIQAQ==")
        }.toString()
        val body = http.postJson("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", payload)
        return parseSearchJson(body)
    }

    internal fun parseSearchJson(raw: String): TrackItem? {
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return extractFirstVideo(root)
    }

    internal fun parseFirstVideo(html: String): TrackItem? {
        val marker = "var ytInitialData = "
        val start = html.indexOf(marker)
        if (start == -1) return null
        val jsonStart = start + marker.length
        val end = html.indexOf(";</script>", jsonStart)
        if (end == -1) return null
        val root = try {
            json.parseToJsonElement(html.substring(jsonStart, end)) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return extractFirstVideo(root)
    }

    private fun extractFirstVideo(root: JsonObject): TrackItem? {

        val sections = root
            .obj("contents")
            ?.obj("twoColumnSearchResultsRenderer")
            ?.obj("primaryContents")
            ?.obj("sectionListRenderer")
            ?.arr("contents")
            ?: return null

        for (section in sections) {
            val items = (section as? JsonObject)
                ?.obj("itemSectionRenderer")
                ?.arr("contents")
                ?: continue
            for (item in items) {
                val video = (item as? JsonObject)?.obj("videoRenderer") ?: continue
                if (isLive(video)) continue
                val id = video.str("videoId")?.takeIf { it.isNotEmpty() } ?: continue
                val title = extractText(video.obj("title")) ?: continue
                val artist = extractText(video.obj("ownerText"))
                    ?: extractText(video.obj("longBylineText"))
                val durationSecs = parseDurationSecs(extractText(video.obj("lengthText"))) ?: continue
                return TrackItem(
                    id = id,
                    title = title,
                    artist = artist,
                    originalUrl = "https://www.youtube.com/watch?v=$id",
                    durationSecs = durationSecs,
                )
            }
        }
        return null
    }

    private fun isLive(video: JsonObject): Boolean {
        val badges = video.arr("badges") ?: return false
        return badges.any { badge ->
            val style = (badge as? JsonObject)
                ?.obj("metadataBadgeRenderer")
                ?.str("style")
            style?.contains("LIVE", ignoreCase = true) == true
        }
    }

    private fun extractText(obj: JsonObject?): String? {
        if (obj == null) return null
        obj.str("simpleText")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val runs = obj.arr("runs") ?: return null
        val joined = runs.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("").trim()
        return joined.takeIf { it.isNotEmpty() }
    }

    internal fun parseDurationSecs(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(':').map { it.toLongOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = get(key) as? JsonArray
    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
