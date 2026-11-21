package io.github.stevenzektyramadhan.isle.update

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException

private const val UPDATE_INTERVAL_SECS = 24 * 60 * 60L
private const val LAST_CHECK_KEY = "last_ytdlp_update_check_secs"

internal fun shouldCheck(nowSecs: Long, lastSecs: Long?): Boolean =
    lastSecs == null || (nowSecs >= lastSecs && nowSecs - lastSecs >= UPDATE_INTERVAL_SECS)

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

    /** Called on IO after YoutubeDL.init; M5 will move this interim preference to DataStore. */
    fun check(): Boolean {
        return attemptUpdate {
            val prefs = appContext.getSharedPreferences("ytdlp_update", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis() / 1_000
            val last = if (prefs.contains(LAST_CHECK_KEY)) prefs.getLong(LAST_CHECK_KEY, 0) else null
            if (shouldCheck(now, last)) {
                val success = try {
                    attemptUpdate { YoutubeDL.updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE) }
                } finally {
                    prefs.edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis() / 1_000).apply()
                }
                Log.i("YtDlpUpdater", if (success) "Stable update check completed" else "Stable update check failed")
                if (!success) throw IllegalStateException("yt-dlp update failed")
            }
        }
    }
}
