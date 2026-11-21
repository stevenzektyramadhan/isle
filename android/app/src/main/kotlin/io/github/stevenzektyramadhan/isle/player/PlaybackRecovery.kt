package io.github.stevenzektyramadhan.isle.player

import io.github.stevenzektyramadhan.isle.model.TrackItem

internal class PlaybackRecovery {
    private data class Attempt(val track: TrackItem, val generation: Long, val mediaId: String, var eligible: Boolean)
    private var attempt: Attempt? = null

    fun start(track: TrackItem, generation: Long, mediaId: String, finalStrategy: Boolean) {
        attempt = Attempt(track, generation, mediaId, !finalStrategy)
    }

    fun ready(mediaId: String?) {
        attempt?.takeIf { it.mediaId == mediaId }?.eligible = false
    }

    fun retry(generation: Long, mediaId: String?, status: Int?): TrackItem? {
        val current = attempt ?: return null
        if (!current.eligible || current.generation != generation || current.mediaId != mediaId || status != 403) return null
        current.eligible = false
        return current.track
    }
}
