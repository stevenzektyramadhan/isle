package io.github.stevenzektyramadhan.isle.data

import io.github.stevenzektyramadhan.isle.model.StreamInfo
import io.github.stevenzektyramadhan.isle.model.TrackItem
import kotlinx.serialization.json.*

fun parseMetadataLine(line: String): TrackItem? {
    val fields = line.trim().split(":::").map(String::trim)
    if (fields.size != 5 || fields[0].isEmpty() || fields[1].isEmpty() || fields[3].isEmpty() || fields[3] == "NA") return null
    val duration = fields[4].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.toLong()
    return TrackItem(fields[0], fields[1], fields[2].takeUnless { it.isEmpty() || it == "NA" }, fields[3], duration)
}

fun parseFlatPlaylistLine(line: String): TrackItem? {
    val fields = line.trim().split(":::").map(String::trim)
    if (fields.size != 3 || fields[0].isEmpty() || fields[1].isEmpty() || fields[2].isEmpty() || fields[2] == "NA") return null
    return TrackItem(fields[0], fields[1], null, fields[2], null)
}

fun parseStreamJson(raw: String): StreamInfo? {
    for (line in raw.lineSequence().map(String::trim).filter { it.startsWith("{") }) {
        val obj = try { Json.parseToJsonElement(line) as? JsonObject } catch (_: IllegalArgumentException) { null } ?: continue
        val url = (obj["url"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank) ?: continue
        val headers = obj["http_headers"] as? JsonObject
        val agent = ((headers?.get("User-Agent") ?: headers?.get("user-agent")) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return StreamInfo(url, agent)
    }
    return raw.lineSequence().map(String::trim).firstOrNull { it.startsWith("https://") || it.startsWith("http://") }?.let { StreamInfo(it, null) }
}
