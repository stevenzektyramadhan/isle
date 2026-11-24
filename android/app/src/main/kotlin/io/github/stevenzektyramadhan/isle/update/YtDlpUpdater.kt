package io.github.stevenzektyramadhan.isle.update

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

private const val UPDATE_INTERVAL_SECS = 24 * 60 * 60L
private const val LAST_CHECK_KEY = "last_ytdlp_update_check_secs"

internal fun shouldCheck(nowSecs: Long, lastSecs: Long?, manual: Boolean = false): Boolean =
    manual || lastSecs == null || (nowSecs >= lastSecs && nowSecs - lastSecs >= UPDATE_INTERVAL_SECS)

private val Context.updateSettings by preferencesDataStore("ytdlp_update_settings")
private val lastCheck = longPreferencesKey(LAST_CHECK_KEY)

/** All yt-dlp executions must hold this gate until their process has actually exited. */
object YtDlpExecutionGate {
    private val lock = ReentrantReadWriteLock(true)
    @Volatile private var integrated = false

    fun <T> withExecution(block: () -> T): T = lock.read(block)
    internal suspend fun <T> withUpdate(block: () -> T): T = runInterruptible(Dispatchers.IO) {
        val write = lock.writeLock()
        write.lockInterruptibly()
        try { block() } finally { write.unlock() }
    }

    /** Call only after initialization and after every execution path uses [withExecution]. */
    fun enableManualUpdates() { integrated = true }
    internal fun manualUpdatesAvailable(): Boolean = integrated
}

enum class ManualUpdateResult { SUCCESS, FAILURE, INTEGRATION_REQUIRED }

internal fun attemptUpdate(update: () -> Unit): Boolean = try {
    update()
    true
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    false
}

class YtDlpUpdater(context: Context) {
    private val appContext = context.applicationContext

    /** Called on IO after YoutubeDL.init. */
    fun check(): Boolean = runBlocking { checkInternal(manual = false) }

    suspend fun checkManually(): ManualUpdateResult = withContext(Dispatchers.IO) {
        if (!YtDlpExecutionGate.manualUpdatesAvailable()) return@withContext ManualUpdateResult.INTEGRATION_REQUIRED
        if (checkInternal(manual = true)) ManualUpdateResult.SUCCESS else ManualUpdateResult.FAILURE
    }

    private suspend fun checkInternal(manual: Boolean): Boolean = try {
        val prefs = appContext.getSharedPreferences("ytdlp_update", Context.MODE_PRIVATE)
        val store = appContext.updateSettings
        YtDlpExecutionGate.withUpdate {
            runBlocking {
                val legacy = if (prefs.contains(LAST_CHECK_KEY)) prefs.getLong(LAST_CHECK_KEY, 0) else null
                if (legacy != null) {
                    store.edit { if (it[lastCheck] == null) it[lastCheck] = legacy }
                    prefs.edit().remove(LAST_CHECK_KEY).apply()
                }
                val now = System.currentTimeMillis() / 1_000
                val last = store.data.first()[lastCheck]
                if (shouldCheck(now, last, manual)) {
                    try {
                        YoutubeDL.updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
                    } finally {
                        store.edit { it[lastCheck] = System.currentTimeMillis() / 1_000 }
                    }
                    Log.i("YtDlpUpdater", "Stable update check completed")
                }
            }
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}
