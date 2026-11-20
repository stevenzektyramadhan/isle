package io.github.stevenzektyramadhan.isle.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.stevenzektyramadhan.isle.model.PlayerCommand

@UnstableApi
class SessionPlayer(player: Player, private val dispatch: (PlayerCommand) -> Unit) : ForwardingPlayer(player) {
    override fun seekToNext() = dispatch(PlayerCommand.Next)
    override fun seekToNextMediaItem() = dispatch(PlayerCommand.Next)
    override fun seekToPrevious() = dispatch(PlayerCommand.Previous)
    override fun seekToPreviousMediaItem() = dispatch(PlayerCommand.Previous)
    override fun stop() = dispatch(PlayerCommand.Stop)
}
