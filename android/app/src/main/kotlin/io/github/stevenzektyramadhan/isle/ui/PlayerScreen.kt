package io.github.stevenzektyramadhan.isle.ui

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stevenzektyramadhan.isle.R
import io.github.stevenzektyramadhan.isle.model.PlayerCommand
import io.github.stevenzektyramadhan.isle.model.PlayerState
import io.github.stevenzektyramadhan.isle.model.TrackItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerState,
    onCommand: (PlayerCommand) -> Unit,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val motionAllowed = rememberMotionEnabled()

    // Disc rotation: read in graphicsLayer (draw phase) to avoid per-frame recompose
    val angle: State<Float>? = if (state.isPlaying && motionAllowed) {
        rememberInfiniteTransition(label = "disc-spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
            label = "disc-angle",
        )
    } else null

    // Safe seek: local drag state, only send SeekTo on finish
    val duration = state.durationSecs?.takeIf { it > 0 }
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val seekProgress = dragValue ?: if (duration != null) (state.elapsedSecs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val tealGlow = IsleColors.playGlow
    val discSize = if (landscape) 120.dp else 220.dp

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        if (landscape) {
            Row(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlayerHeader(state, onClose, onOpenQueue)
                    PlayerDisc(angle, discSize, tealGlow)
                    PlayerTrackInfo(track)
                    PlayerSeekRow(seekProgress, duration, state,
                        onValueChange = { dragValue = it },
                        onFinished = {
                            dragValue?.let { v -> if (duration != null) onCommand(PlayerCommand.SeekTo((v * duration).toLong())) }
                            dragValue = null
                        },
                    )
                    PlayerControls(state, onCommand)
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(48.dp))
                    PlayerVolumeAutoplay(state, onCommand)
                    PlayerQueuePill(state.queue.size, onOpenQueue)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PlayerHeader(state, onClose, onOpenQueue)
                PlayerDisc(angle, discSize, tealGlow)
                PlayerTrackInfo(track)
                PlayerSeekRow(seekProgress, duration, state,
                    onValueChange = { dragValue = it },
                    onFinished = {
                        dragValue?.let { v -> if (duration != null) onCommand(PlayerCommand.SeekTo((v * duration).toLong())) }
                        dragValue = null
                    },
                )
                PlayerControls(state, onCommand)
                PlayerVolumeAutoplay(state, onCommand)
                PlayerQueuePill(state.queue.size, onOpenQueue)
            }
        }
    }
}

@Composable
private fun PlayerHeader(state: PlayerState, onClose: () -> Unit, onOpenQueue: () -> Unit) {
    val closeDesc = stringResource(R.string.player_close)
    val queueDesc = stringResource(R.string.player_queue_open)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = closeDesc,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val label = if (state.isPlaying) stringResource(R.string.player_live) else stringResource(R.string.player_paused)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        BadgedBox(
            badge = {
                if (state.queue.isNotEmpty()) Badge(containerColor = MaterialTheme.colorScheme.primary) {
                    Text(
                        "${state.queue.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            },
        ) {
            IconButton(onClick = onOpenQueue, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.ic_queue),
                    contentDescription = queueDesc,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlayerDisc(angle: State<Float>?, size: androidx.compose.ui.unit.Dp, glowColor: androidx.compose.ui.graphics.Color) {
    DiscView(
        modifier = Modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor, androidx.compose.ui.graphics.Color.Transparent),
                        radius = this.size.minDimension * 0.75f,
                    ),
                )
            }
            .graphicsLayer { rotationZ = angle?.value ?: 0f },
        label = "ISLE",
        playing = false, // rotation driven externally via graphicsLayer
    )
}

@Composable
private fun PlayerTrackInfo(track: TrackItem) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            track.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = IsleColors.textPrimary,
        )
        track.artist?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlayerSeekRow(
    seekProgress: Float,
    duration: Long?,
    state: PlayerState,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val seekDesc = stringResource(R.string.player_seek)
    val elapsed = formatPlayerTime(state.elapsedSecs)
    val total = duration?.let(::formatPlayerTime) ?: "--:--"
    Column(Modifier.fillMaxWidth()) {
        IsleSlider(
            value = seekProgress,
            onValueChange = onValueChange,
            enabled = duration != null,
            semanticsName = seekDesc,
            stateDescription = "$elapsed / $total",
            onValueChangeFinished = onFinished,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(elapsed, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(total, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayerControls(state: PlayerState, onCommand: (PlayerCommand) -> Unit) {
    val prevDesc = stringResource(R.string.player_previous)
    val nextDesc = stringResource(R.string.player_next)
    val playPauseDesc = stringResource(if (state.isPlaying) R.string.pause else R.string.play)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IsleIconButton(onClick = { onCommand(PlayerCommand.Previous) }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = prevDesc, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlayPauseButton(
            playing = state.isPlaying,
            onClick = { onCommand(PlayerCommand.TogglePlayPause) },
            modifier = Modifier.size(64.dp),
            description = playPauseDesc,
        )
        IsleIconButton(onClick = { onCommand(PlayerCommand.Next) }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = nextDesc, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayerVolumeAutoplay(state: PlayerState, onCommand: (PlayerCommand) -> Unit) {
    val volumeDesc = stringResource(R.string.volume)
    val autoplayDesc = stringResource(R.string.autoplay)
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_volume_down), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                IsleSlider(
                    value = state.volume.coerceIn(0f, 1f),
                    onValueChange = { onCommand(PlayerCommand.SetVolume(it)) },
                    semanticsName = volumeDesc,
                    stateDescription = "${(state.volume * 100).toInt()}%",
                    onValueChangeFinished = {},
                    modifier = Modifier.weight(1f),
                )
                Icon(painterResource(R.drawable.ic_volume_up), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            @Suppress("DEPRECATION")
            Divider(color = IsleColors.hairline)
            SwitchRow(
                title = autoplayDesc,
                subtitle = state.autoplayAnchor?.title?.let { stringResource(R.string.home_autoplay_anchor, it) }
                    ?: stringResource(R.string.autoplay_description),
                checked = state.autoplayEnabled,
                onCheckedChange = { onCommand(PlayerCommand.ToggleAutoplay) },
            )
        }
    }
}

@Composable
private fun PlayerQueuePill(queueSize: Int, onOpenQueue: () -> Unit) {
    Surface(
        onClick = onOpenQueue,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, IsleColors.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.player_queue_pill, queueSize),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(painterResource(R.drawable.ic_chevron_up), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

internal fun formatPlayerTime(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return if (safe >= 3600) "%d:%02d:%02d".format(safe / 3600, safe / 60 % 60, safe % 60)
    else "%d:%02d".format(safe / 60, safe % 60)
}
