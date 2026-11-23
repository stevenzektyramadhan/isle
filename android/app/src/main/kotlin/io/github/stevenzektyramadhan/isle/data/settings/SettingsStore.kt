package io.github.stevenzektyramadhan.isle.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.playerSettings by preferencesDataStore("player_settings")

internal data class PlayerSettings(val volume: Float = 1f, val autoplayEnabled: Boolean = true)

internal interface SettingsStore {
    suspend fun read(): PlayerSettings
    suspend fun setVolume(volume: Float)
    suspend fun setAutoplay(enabled: Boolean)
}

internal class DataStoreSettings(private val store: DataStore<Preferences>) : SettingsStore {
    constructor(context: Context) : this(context.applicationContext.playerSettings)

    private val volume = floatPreferencesKey("volume")
    private val autoplay = booleanPreferencesKey("autoplay")

    override suspend fun read(): PlayerSettings {
        val values = store.data.first()
        val gain = values[volume]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        return PlayerSettings(gain, values[autoplay] ?: true)
    }

    override suspend fun setVolume(volume: Float) { store.edit { it[this.volume] = volume } }
    override suspend fun setAutoplay(enabled: Boolean) { store.edit { it[autoplay] = enabled } }
}
