package io.github.stevenzektyramadhan.isle.player

import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import androidx.media3.common.util.UnstableApi
import io.github.stevenzektyramadhan.isle.model.PlayerCommand
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SessionPlayerTest {
    @Test fun playerEventsAndPlaybackStateReachRegisteredListener() {
        lateinit var registered: Player.Listener
        val delegate = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            if (method.name == "addListener") registered = args[0] as Player.Listener
            null
        } as Player
        val player = SessionPlayer(delegate) { }
        var eventPlayer: Player? = null
        var state: Int? = null
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { eventPlayer = player }
            override fun onPlaybackStateChanged(playbackState: Int) { state = playbackState }
        })

        registered.onPlaybackStateChanged(Player.STATE_READY)
        registered.onEvents(delegate, Player.Events(FlagSet.Builder().build()))

        assertEquals(Player.STATE_READY, state)
        assertSame(player, eventPlayer)
    }

    @Test fun nextAndPreviousDoNotSeekOrRestartTheCurrentTrack() {
        val calls = mutableListOf<String>()
        val commands = mutableListOf<PlayerCommand>()
        val delegate = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            calls.add(method.name)
            null
        } as Player
        val player = SessionPlayer(delegate) { commands.add(it) }

        player.seekToPrevious()
        player.seekToPreviousMediaItem()
        player.seekToNext()
        player.seekToNextMediaItem()
        player.pause()
        player.play()
        player.playWhenReady = false

        assertEquals(emptyList<String>(), calls)
        assertEquals(listOf(PlayerCommand.Previous, PlayerCommand.Previous, PlayerCommand.Next, PlayerCommand.Next, PlayerCommand.Pause, PlayerCommand.Play, PlayerCommand.Pause), commands)
    }

    @Test fun stopIsRoutedThroughEngineRatherThanStoppingOnlyTheUnderlyingPlayer() {
        val calls = mutableListOf<String>()
        val commands = mutableListOf<PlayerCommand>()
        val delegate = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            calls.add(method.name)
            null
        } as Player
        val player = SessionPlayer(delegate) { commands.add(it) }

        player.stop()

        assertEquals(emptyList<String>(), calls)
        assertEquals(listOf(PlayerCommand.Stop), commands)
    }
}
