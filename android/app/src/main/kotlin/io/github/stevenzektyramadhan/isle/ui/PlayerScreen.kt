package io.github.stevenzektyramadhan.isle.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stevenzektyramadhan.isle.R
import io.github.stevenzektyramadhan.isle.model.PlayerCommand
import io.github.stevenzektyramadhan.isle.model.PlayerState
import io.github.stevenzektyramadhan.isle.model.TrackItem

@Composable
fun PlayerScreen(
    state: PlayerState,
    onCommand: (PlayerCommand) -> Unit,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val needsCompactScroll = landscape || configuration.screenHeightDp < 580 || configuration.fontScale >= 1.5f

    // Safe seek: local drag state, only send SeekTo on finish
    val duration = state.durationSecs?.takeIf { it > 0 }
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val seekProgress = dragValue ?: if (duration != null) (state.elapsedSecs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    var volumeExpanded by rememberSaveable { mutableStateOf(false) }

    // Swipe down to collapse
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 110.dp.toPx() }
    val dragOffsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragOffsetY.value }
            .draggable(
                orientation = Orientation.Vertical,
                enabled = !needsCompactScroll,
                state = rememberDraggableState { delta ->
                    scope.launch {
                        dragOffsetY.snapTo((dragOffsetY.value + delta).coerceAtLeast(0f))
                    }
                },
                onDragStopped = { velocity ->
                    scope.launch {
                        if (dragOffsetY.value > dismissThresholdPx || velocity > 900f) {
                            onClose()
                            dragOffsetY.snapTo(0f)
                        } else {
                            dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                },
            ),
    ) {
        if (landscape) {
            Row(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .widthIn(max = 840.dp)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlayerHeader(state, onClose, onOpenQueue, showHandle = false)
                    TrackArtworkCard(
                        playing = state.isPlaying,
                        sourceLabel = trackSourceLabel(track.originalUrl),
                        modifier = Modifier
                            .size(164.dp),
                    )
                    PlayerTrackInfo(track)
                    PlayerSeekRow(
                        seekProgress = seekProgress,
                        duration = duration,
                        state = state,
                        onValueChange = { dragValue = it },
                        onFinished = {
                            dragValue?.let { v -> if (duration != null) onCommand(PlayerCommand.SeekTo((v * duration).toLong())) }
                            dragValue = null
                        },
                    )
                    PlayerControls(state, onCommand)
                }

                Column(
                    Modifier
                        .weight(0.95f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(40.dp))
                    PlayerVolumePanel(state, onCommand)
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        SwitchRow(
                            title = stringResource(R.string.autoplay),
                            subtitle = state.autoplayAnchor?.title?.let { stringResource(R.string.home_autoplay_anchor, it) }
                                ?: stringResource(R.string.autoplay_description),
                            checked = state.autoplayEnabled,
                            onCheckedChange = { onCommand(PlayerCommand.ToggleAutoplay) },
                        )
                    }
                    PlayerQueueTrigger(state.queue.size, onOpenQueue, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            val portraitModifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .let { if (needsCompactScroll) it.verticalScroll(rememberScrollState()) else it }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = portraitModifier.widthIn(max = 560.dp),
                    verticalArrangement = if (needsCompactScroll) Arrangement.spacedBy(16.dp) else Arrangement.SpaceBetween,
                ) {
                    PlayerHeader(state, onClose, onOpenQueue, showHandle = true)

                    // Adaptive artwork area keeps full player on 1 screen without scrolling
                    Box(
                        modifier = if (needsCompactScroll) {
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        } else {
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        TrackArtworkCard(
                            playing = state.isPlaying,
                            sourceLabel = trackSourceLabel(track.originalUrl),
                            modifier = Modifier
                                .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
                                .aspectRatio(1f),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        PlayerTrackInfo(track)

                        PlayerSeekRow(
                            seekProgress = seekProgress,
                            duration = duration,
                            state = state,
                            onValueChange = { dragValue = it },
                            onFinished = {
                                dragValue?.let { v -> if (duration != null) onCommand(PlayerCommand.SeekTo((v * duration).toLong())) }
                                dragValue = null
                            },
                        )

                        PlayerControls(state, onCommand)

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AnimatedVisibility(
                                visible = volumeExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                PlayerVolumePanel(state, onCommand)
                            }

                            PlayerUtilityRow(
                                state = state,
                                volumeExpanded = volumeExpanded,
                                onToggleVolume = { volumeExpanded = !volumeExpanded },
                                onToggleAutoplay = { onCommand(PlayerCommand.ToggleAutoplay) },
                                onOpenQueue = onOpenQueue,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerHeader(
    state: PlayerState,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    showHandle: Boolean,
) {
    val closeDesc = stringResource(R.string.player_close)
    val queueDesc = stringResource(R.string.player_queue_open)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showHandle) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp),
            ) {}
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IsleIconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.ic_chevron_down),
                    contentDescription = closeDesc,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (state.isPlaying) stringResource(R.string.player_live) else stringResource(R.string.player_paused),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BadgedBox(
                badge = {
                    if (state.queue.isNotEmpty()) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Text("${state.queue.size}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
            ) {
                IsleIconButton(onClick = onOpenQueue, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_queue),
                        contentDescription = queueDesc,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerTrackInfo(track: TrackItem) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
        )
        track.artist?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
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
    val elapsedSecs = if (duration != null) (seekProgress * duration).toLong() else state.elapsedSecs
    val elapsed = formatPlayerTime(elapsedSecs)
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(elapsed, style = IsleTypography.tabularTime, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(total, style = IsleTypography.tabularTime, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayerControls(state: PlayerState, onCommand: (PlayerCommand) -> Unit) {
    val prevDesc = stringResource(R.string.player_previous)
    val nextDesc = stringResource(R.string.player_next)
    val playPauseDesc = stringResource(if (state.isPlaying) R.string.pause else R.string.play)
    val haptics = LocalHapticFeedback.current

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IsleIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCommand(PlayerCommand.Previous)
            },
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_skip_previous),
                contentDescription = prevDesc,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp),
            )
        }
        PlayPauseButton(
            playing = state.isPlaying,
            onClick = { onCommand(PlayerCommand.TogglePlayPause) },
            modifier = Modifier.size(68.dp),
            description = playPauseDesc,
            iconSize = 32.dp,
        )
        IsleIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCommand(PlayerCommand.Next)
            },
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_skip_next),
                contentDescription = nextDesc,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun PlayerVolumePanel(state: PlayerState, onCommand: (PlayerCommand) -> Unit) {
    val volumeDesc = stringResource(R.string.volume)
    val percent = "${(state.volume.coerceIn(0f, 1f) * 100).toInt()}%"
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_volume_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            IsleSlider(
                value = state.volume.coerceIn(0f, 1f),
                onValueChange = { onCommand(PlayerCommand.SetVolume(it)) },
                semanticsName = volumeDesc,
                stateDescription = percent,
                onValueChangeFinished = {},
                modifier = Modifier.weight(1f),
            )
            Text(
                percent,
                style = IsleTypography.tabularTime,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(38.dp),
            )
        }
    }
}

@Composable
private fun PlayerUtilityRow(
    state: PlayerState,
    volumeExpanded: Boolean,
    onToggleVolume: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val volumePercent = "${(state.volume.coerceIn(0f, 1f) * 100).toInt()}%"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onToggleVolume,
            shape = CircleShape,
            color = if (volumeExpanded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painterResource(if (state.volume < 0.45f) R.drawable.ic_volume_down else R.drawable.ic_volume_up),
                    contentDescription = stringResource(R.string.player_volume_toggle),
                    tint = if (volumeExpanded) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    volumePercent,
                    style = IsleTypography.tabularTime,
                    color = if (volumeExpanded) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = if (state.autoplayEnabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clip(CircleShape)
                .toggleable(
                    value = state.autoplayEnabled,
                    role = Role.Switch,
                    onValueChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleAutoplay()
                    },
                ),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_autoplay),
                    contentDescription = null,
                    tint = if (state.autoplayEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.autoplay),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.autoplayEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PlayerQueueTrigger(state.queue.size, onOpenQueue)
    }
}

@Composable
private fun PlayerQueueTrigger(queueSize: Int, onOpenQueue: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpenQueue,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_queue),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.player_queue_pill, queueSize),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatPlayerTime(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return if (safe >= 3600) "%d:%02d:%02d".format(safe / 3600, safe / 60 % 60, safe % 60)
    else "%d:%02d".format(safe / 60, safe % 60)
}
