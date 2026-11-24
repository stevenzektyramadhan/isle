package io.github.stevenzektyramadhan.isle.player

import io.github.stevenzektyramadhan.isle.model.TrackItem

/** Single-thread confined policy. The clock must return monotonic milliseconds. */
internal class NextTrackPreparation<T : Any>(private val nowMillis: () -> Long) {
    class Attempt internal constructor(val track: TrackItem, internal val generation: Long, internal val startedAt: Long)

    private sealed class State<out T> {
        data object Empty : State<Nothing>()
        class Pending(val attempt: Attempt) : State<Nothing>()
        class Ready<T>(val attempt: Attempt, val value: T) : State<T>()
        class Finished(val track: TrackItem) : State<Nothing>()
    }

    private var generation = 0L
    private var state: State<T> = State.Empty

    /** Reconcile eligibility with playback and the queue head; a returned attempt should be resolved once. */
    fun prepare(track: TrackItem?, playing: Boolean): Attempt? {
        if (!playing || track == null) {
            invalidate()
            return null
        }
        val currentTrack = when (val current = state) {
            State.Empty -> null
            is State.Pending -> current.attempt.track
            is State.Ready -> current.attempt.track
            is State.Finished -> current.track
        }
        if (currentTrack == track) return null
        val attempt = Attempt(track, ++generation, nowMillis())
        state = State.Pending(attempt)
        return attempt
    }

    fun complete(attempt: Attempt, value: T) {
        val pending = state as? State.Pending ?: return
        if (pending.attempt !== attempt || attempt.generation != generation) return
        state = State.Ready(attempt, value)
    }

    fun fail(attempt: Attempt) {
        val pending = state as? State.Pending ?: return
        if (pending.attempt !== attempt || attempt.generation != generation) return
        state = State.Finished(attempt.track)
    }

    /** Consume a matching value if less than 60 seconds have elapsed since preparation began. */
    fun take(track: TrackItem): T? {
        val ready = state as? State.Ready ?: return null
        if (ready.attempt.track != track) return null
        state = State.Finished(track)
        val age = nowMillis() - ready.attempt.startedAt
        return if (age >= 0 && age < 60_000) ready.value else null
    }

    fun invalidate() {
        generation++
        state = State.Empty
    }
}
