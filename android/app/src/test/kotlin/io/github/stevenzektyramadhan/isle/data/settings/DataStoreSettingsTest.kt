package io.github.stevenzektyramadhan.isle.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun preferencesSurviveStoreRestart() = runBlocking {
        val file = temporary.root.resolve("player.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = DataStoreSettings(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        try {
            assertEquals(PlayerSettings(), first.read())
            first.setVolume(.35f)
            first.setAutoplay(false)
        } finally { firstScope.coroutineContext[Job]!!.cancelAndJoin() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = DataStoreSettings(PreferenceDataStoreFactory.create(scope = secondScope) { file })
            assertEquals(PlayerSettings(.35f, false), second.read())
        } finally { secondScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun invalidStoredVolumeCannotEscapePlayerRange() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { temporary.root.resolve("invalid.preferences_pb") }
        try {
            for ((input, expected) in listOf(-2f to 0f, 4f to 1f, Float.NaN to 1f)) {
                store.edit { it[floatPreferencesKey("volume")] = input }
                assertEquals(expected, DataStoreSettings(store).read().volume)
            }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
