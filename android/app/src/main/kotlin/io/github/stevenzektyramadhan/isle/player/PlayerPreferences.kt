package io.github.stevenzektyramadhan.isle.player

import io.github.stevenzektyramadhan.isle.data.settings.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException

/** Confined to the engine's main scope. Each field's user intent wins over a late restore. */
internal class PlayerPreferences(
    private val store: SettingsStore,
    scope: CoroutineScope,
    private val target: Target,
) {
    interface Target {
        fun restoreVolume(volume: Float)
        fun restoreAutoplay(enabled: Boolean)
        fun preferencesFailed(message: String)
    }

    private var volumeChanged = false
    private var autoplayChanged = false
    private sealed interface Write {
        data class Volume(val value: Float) : Write
        data class Autoplay(val value: Boolean) : Write
    }
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val restore = scope.launch {
        try {
            val saved = store.read()
            if (!volumeChanged) target.restoreVolume(saved.volume)
            if (!autoplayChanged) target.restoreAutoplay(saved.autoplayEnabled)
        } catch (error: IOException) { target.preferencesFailed("Could not read playback preferences: ${error.message}") }
    }
    private val writer = CoroutineScope(scope.coroutineContext.minusKey(Job)).launch {
        for (write in writes) {
            try {
                when (write) {
                    is Write.Volume -> store.setVolume(write.value)
                    is Write.Autoplay -> store.setAutoplay(write.value)
                }
            } catch (error: IOException) { target.preferencesFailed("Could not save playback preferences: ${error.message}") }
        }
    }

    fun volume(volume: Float) { volumeChanged = true; check(writes.trySend(Write.Volume(volume)).isSuccess) }
    fun autoplay(enabled: Boolean) { autoplayChanged = true; check(writes.trySend(Write.Autoplay(enabled)).isSuccess) }
    fun close() { restore.cancel(); writes.close() }
}
