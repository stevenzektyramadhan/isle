package io.github.stevenzektyramadhan.isle.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.stevenzektyramadhan.isle.model.PlayerCommand
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SessionPlayerTest {
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

        assertEquals(listOf("pause"), calls)
        assertEquals(listOf(PlayerCommand.Previous, PlayerCommand.Previous, PlayerCommand.Next, PlayerCommand.Next), commands)
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
