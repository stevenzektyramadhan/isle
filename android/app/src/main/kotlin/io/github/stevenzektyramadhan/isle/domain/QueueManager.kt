package io.github.stevenzektyramadhan.isle.domain

import io.github.stevenzektyramadhan.isle.model.TrackItem

/** Synchronous queue/session state. The caller owns playback, lookup and stale-result cancellation. */
class QueueManager {
    sealed interface Origin {
        data object Manual : Origin
        data class Automatic(val radioMixLoaded: Boolean = false) : Origin
    }

    sealed interface Advance {
        data class Play(val track: TrackItem, val origin: Origin) : Advance
        data class Fetch(val anchor: TrackItem) : Advance
        data object Stopped : Advance
        data object LookupFailed : Advance
        data object RepeatedErrors : Advance
    }

    sealed interface Related {
        data class Tracks(val tracks: List<TrackItem>) : Related
        data object NoResults : Related
        data object Failure : Related
    }

    private val upcoming = ArrayDeque<TrackItem>()
    private val past = ArrayDeque<TrackItem>()
    private val ids = mutableSetOf<String>()
    private val titles = mutableSetOf<String>()

    val queue: List<TrackItem> get() = upcoming.toList()
    val history: List<TrackItem> get() = past.toList()
    val attemptedIds: Set<String> get() = ids.toSet()
    val attemptedTitles: Set<String> get() = titles.toSet()
    var current: TrackItem? = null
        private set
    var anchor: TrackItem? = null
        private set
    var autoplayFetched: Boolean = false
        private set
    var consecutiveErrors: Int = 0
        private set

    /** Call after successful input resolution; an empty result leaves the session unchanged. */
    fun acceptInput(tracks: List<TrackItem>): TrackItem? {
        val first = tracks.firstOrNull() ?: return null
        consecutiveErrors = 0
        anchor = first
        ids.clear()
        titles.clear()
        autoplayFetched = false
        current?.let(::pushHistory)
        upcoming.clear()
        upcoming.addAll(tracks.drop(1))
        return activate(first)
    }

    /** Call on manual submission even when resolution later fails. */
    fun resetErrors() { consecutiveErrors = 0 }

    fun clearQueue() { upcoming.clear() }

    /** Stop playback without resetting the queue, history or autoplay session. */
    fun stop() { current = null }

    fun previous(): TrackItem? {
        resetErrors()
        val previous = past.removeLastOrNull() ?: return null
        current?.let(upcoming::addFirst)
        return activate(previous)
    }

    fun advance(origin: Origin, autoplayEnabled: Boolean): Advance {
        when (origin) {
            Origin.Manual -> resetErrors()
            is Origin.Automatic -> if (consecutiveErrors >= 3) return Advance.RepeatedErrors
        }
        current?.let(::pushHistory)
        current = null
        val next = upcoming.removeFirstOrNull()
        if (next != null) return Advance.Play(activate(next), origin)
        val mixLoaded = origin is Origin.Automatic && origin.radioMixLoaded
        val sessionAnchor = anchor
        return if (!mixLoaded && autoplayEnabled && !autoplayFetched && sessionAnchor != null) {
            Advance.Fetch(sessionAnchor)
        } else Advance.Stopped
    }

    /** Complete a requested fetch only if it still belongs to the current session/advance. */
    fun completeAutoplay(result: Related, origin: Origin): Advance = when (result) {
        Related.Failure -> Advance.LookupFailed
        Related.NoResults -> {
            autoplayFetched = true
            Advance.Stopped
        }
        is Related.Tracks -> {
            autoplayFetched = true
            upcoming.addAll(result.tracks.filter { it.id !in ids && canonicalTitle(it.title) !in titles })
            val next = upcoming.removeFirstOrNull()
            if (next == null) Advance.Stopped else {
                val nextOrigin = when (origin) {
                    Origin.Manual -> Origin.Manual
                    is Origin.Automatic -> Origin.Automatic(radioMixLoaded = true)
                }
                Advance.Play(activate(next), nextOrigin)
            }
        }
    }

    /** Record the result of the most recent non-stale playback activation. */
    fun activationSucceeded() { resetErrors() }
    fun activationFailed() { if (consecutiveErrors < Int.MAX_VALUE) consecutiveErrors++ }

    private fun activate(track: TrackItem): TrackItem {
        current = track
        ids.add(track.id)
        titles.add(canonicalTitle(track.title))
        return track
    }

    private fun pushHistory(track: TrackItem) {
        past.addLast(track)
        if (past.size > 200) past.removeFirst()
    }
}
