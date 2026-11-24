package io.github.stevenzektyramadhan.isle.player

import io.github.stevenzektyramadhan.isle.data.settings.PlayerSettings
import io.github.stevenzektyramadhan.isle.data.settings.SettingsStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlayerPreferencesTest {
    @Test fun closeDrainsAcceptedWritesEvenWhenEngineScopeIsCancelled() = runBlocking {
        val engineScope = CoroutineScope(coroutineContext + Job())
        val finished = CompletableDeferred<Unit>()
        var saved = PlayerSettings()
        val store = object : SettingsStore {
            override suspend fun read() = saved
            override suspend fun setVolume(volume: Float) { yield(); saved = saved.copy(volume = volume) }
            override suspend fun setAutoplay(enabled: Boolean) { yield(); saved = saved.copy(autoplayEnabled = enabled); finished.complete(Unit) }
        }
        val preferences = PlayerPreferences(store, engineScope, Target())
        preferences.volume(.25f)
        preferences.autoplay(false)
        preferences.close()
        engineScope.cancel()
        withTimeoutOrNull(1_000) { finished.await() }
        assertEquals(PlayerSettings(.25f, false), saved)
    }

    private class Target : PlayerPreferences.Target {
        var volume = 1f
        var autoplay = true
        val errors = mutableListOf<String>()
        override fun restoreVolume(volume: Float) { this.volume = volume }
        override fun restoreAutoplay(enabled: Boolean) { autoplay = enabled }
        override fun preferencesFailed(message: String) { errors.add(message) }
    }

    @Test fun lateRestorePreservesOnlyFieldsChangedByUserAndWritesInOrder() = runBlocking {
        for (changeVolume in listOf(true, false)) {
            val read = CompletableDeferred<PlayerSettings>()
            val writes = mutableListOf<Any>()
            val finished = CompletableDeferred<Unit>()
            val store = object : SettingsStore {
                override suspend fun read() = read.await()
                override suspend fun setVolume(volume: Float) { writes.add(volume); if (writes.size == 2) finished.complete(Unit) }
                override suspend fun setAutoplay(enabled: Boolean) { writes.add(enabled); if (writes.size == 2) finished.complete(Unit) }
            }
            val target = Target()
            val preferences = PlayerPreferences(store, this, target)
            try {
                if (changeVolume) { preferences.volume(.8f); preferences.volume(.9f) }
                else { preferences.autoplay(true); preferences.autoplay(false) }
                read.complete(PlayerSettings(.2f, false))
                withTimeout(5_000) { finished.await() }
                yield()
                assertEquals(if (changeVolume) 1f else .2f, target.volume)
                assertEquals(if (changeVolume) false else true, target.autoplay)
                assertEquals(if (changeVolume) listOf(.8f, .9f) else listOf(true, false), writes)
            } finally { preferences.close() }
        }
    }

    @Test fun readAndWriteFailuresAreSurfacedAndLaterWritesStillRun() = runBlocking {
        val finished = CompletableDeferred<Unit>()
        val target = Target()
        val store = object : SettingsStore {
            override suspend fun read(): PlayerSettings = throw IOException("read failed")
            override suspend fun setVolume(volume: Float) { throw IOException("write failed") }
            override suspend fun setAutoplay(enabled: Boolean) { finished.complete(Unit) }
        }
        val preferences = PlayerPreferences(store, this, target)
        try {
            preferences.volume(.5f)
            preferences.autoplay(false)
            withTimeout(5_000) { finished.await() }
            assertEquals(2, target.errors.size)
            assertTrue(target.errors[0].contains("read failed"))
            assertTrue(target.errors[1].contains("write failed"))
        } finally { preferences.close() }
    }
}
