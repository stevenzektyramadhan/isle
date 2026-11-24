package io.github.stevenzektyramadhan.isle

import android.app.Application
import io.github.stevenzektyramadhan.isle.player.PlayerEngine

class IsleApp : Application() {
    lateinit var playerEngine: PlayerEngine
        private set
    override fun onCreate() { super.onCreate(); playerEngine = PlayerEngine(this) }
}
