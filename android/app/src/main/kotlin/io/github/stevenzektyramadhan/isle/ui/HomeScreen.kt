package io.github.stevenzektyramadhan.isle.ui

import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stevenzektyramadhan.isle.R
import io.github.stevenzektyramadhan.isle.model.PlayerState
import io.github.stevenzektyramadhan.isle.model.PlayerStatus
import io.github.stevenzektyramadhan.isle.model.classifyPlayerStatus
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    state: HomeUiState,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQueue: () -> Unit,
    updateNotice: @Composable () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val settingsDescription = stringResource(R.string.settings)
    val clearDescription = stringResource(R.string.home_clear)
    val pasteDescription = stringResource(R.string.home_paste)
    val searchDescription = stringResource(R.string.search)
    var focused by rememberSaveable { mutableStateOf(false) }

    val searchBorder by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = tween(180),
        label = "search-border",
    )

    val activeStatus = (state.status ?: state.statusMessage?.let(::classifyPlayerStatus))
        ?.takeUnless { state.statusDismissed }

    val contentKey = when {
        state.isLoading -> "loading"
        activeStatus != null -> "status:${activeStatus.code}:${activeStatus.message}"
        state.player.currentTrack != null -> "session"
        else -> "idle"
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = if (state.player.currentTrack == null) 28.dp else 116.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Editorial Top Bar
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.home_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    IsleIconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_settings),
                            contentDescription = settingsDescription,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                updateNotice()

                // Recessed Search / Link Input
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = IsleColors.recessed,
                        border = BorderStroke(1.dp, searchBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_search),
                                contentDescription = null,
                                tint = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            BasicTextField(
                                value = state.input,
                                onValueChange = onInputChange,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 14.dp)
                                    .onFocusChanged { focused = it.isFocused },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                singleLine = true,
                                readOnly = state.isLoading,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = { if (state.input.isNotBlank() && !state.isLoading) onSubmit() },
                                ),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (state.input.isEmpty()) {
                                            Text(
                                                stringResource(R.string.home_search_placeholder),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            if (state.input.isNotEmpty()) {
                                IsleIconButton(onClick = { onInputChange("") }, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        painterResource(R.drawable.ic_close),
                                        contentDescription = clearDescription,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            } else if (!state.isLoading) {
                                IsleIconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                                        val pasted = clipboard?.primaryClip
                                            ?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)
                                            ?.coerceToText(context)
                                            ?.toString()
                                            ?.trim()
                                            ?.takeIf { it.isNotEmpty() }
                                        if (pasted != null) onInputChange(pasted)
                                    },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_content_paste),
                                        contentDescription = pasteDescription,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            val canSubmit = state.input.isNotBlank() && !state.isLoading
                            Surface(
                                onClick = { if (canSubmit) onSubmit() },
                                enabled = canSubmit,
                                modifier = Modifier
                                    .size(44.dp)
                                    .semantics { contentDescription = searchDescription },
                                shape = RoundedCornerShape(14.dp),
                                color = if (canSubmit) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (state.isLoading) {
                                        CircularProgressIndicator(
                                            Modifier.size(18.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            painterResource(R.drawable.ic_arrow_forward),
                                            contentDescription = null,
                                            tint = if (canSubmit) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.home_search_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                AnimatedContent(
                    targetState = contentKey,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                    label = "home-body-state",
                ) { key ->
                    when {
                        key == "loading" -> StatusCard(
                            title = stringResource(R.string.home_searching_title),
                            message = resources.getString(R.string.home_searching_message, state.input.trim()),
                            error = false,
                            actions = {
                                LinearProgressIndicator(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(CircleShape),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = IsleColors.sliderInactiveTrack,
                                )
                            },
                        )

                        key.startsWith("status:") && activeStatus != null -> {
                            val status = mapStatus(activeStatus)
                            StatusCard(
                                title = status.title(context),
                                message = status.message(context),
                                error = status.severity == StatusSeverity.Error,
                                detail = status.detail,
                                actions = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (StatusAction.Retry in status.actions && state.input.isNotBlank()) {
                                            TextButton(onClick = onSubmit, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                                                Text(stringResource(R.string.status_retry))
                                            }
                                        }
                                        if (StatusAction.Settings in status.actions) {
                                            TextButton(onClick = onOpenSettings, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                                                Text(stringResource(R.string.status_settings))
                                            }
                                        }
                                        TextButton(onClick = onDismissStatus, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                                            Text(stringResource(R.string.status_dismiss))
                                        }
                                    }
                                },
                            )
                        }

                        key == "session" -> HomeActiveSession(
                            state = state.player,
                            onOpenPlayer = onOpenPlayer,
                            onOpenQueue = onOpenQueue,
                        )

                        else -> HomeIdleState()
                    }
                }
            }

            if (state.player.currentTrack != null) {
                MiniPlayer(
                    state = state.player,
                    onTogglePlayback = onTogglePlayback,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onOpenPlayer = onOpenPlayer,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

data class HomeUiState(
    val input: String = "",
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val status: PlayerStatus? = null,
    val statusDismissed: Boolean = false,
    val player: PlayerState = PlayerState(),
)

@Composable
private fun HomeIdleState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            stringResource(R.string.home_idle_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.home_idle_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeActiveSession(
    state: PlayerState,
    onOpenPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.autoplayEnabled && state.autoplayAnchor != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_autoplay),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(R.string.home_autoplay_anchor, state.autoplayAnchor.title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (state.queue.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onOpenQueue)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.home_up_next, state.queue.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        stringResource(R.string.home_view_queue),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        val preview = state.queue.take(4)
                        preview.forEachIndexed { index, item ->
                            TrackRow(
                                index = index + 1,
                                title = item.title,
                                artist = item.artist,
                                duration = item.durationSecs?.let(::formatPlayerTime) ?: "--:--",
                            )
                            if (index < preview.lastIndex) {
                                HorizontalDivider(color = IsleColors.divider)
                            }
                        }
                    }
                }

                if (state.queue.all { it.durationSecs != null }) {
                    Text(
                        stringResource(
                            R.string.home_queue_duration,
                            formatPlayerTime(state.queue.sumOf { it.durationSecs ?: 0L }),
                        ),
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(horizontal = 4.dp),
                        style = IsleTypography.tabularDuration,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.home_session_heading),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.home_session_no_queue),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = onOpenPlayer,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(stringResource(R.string.home_session_open_player))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    state: PlayerState,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nextDescription = stringResource(R.string.home_next_track)
    val track = state.currentTrack ?: return
    val duration = state.durationSecs?.takeIf { it > 0 }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 64.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)

    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val nextOffset = (offsetX.value + dragAmount).coerceIn(-swipeThresholdPx * 1.4f, swipeThresholdPx * 1.4f)
                                offsetX.snapTo(nextOffset)
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value <= -swipeThresholdPx -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentOnNext()
                                    }
                                    offsetX.value >= swipeThresholdPx -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentOnPrevious()
                                    }
                                }
                                offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                        },
                    )
                },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 70.dp)
                        .clickable(onClick = onOpenPlayer)
                        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (state.isPlaying) {
                                EqualizerBars(playing = true)
                            } else {
                                Icon(
                                    painterResource(R.drawable.ic_music_note),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            track.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            track.artist?.takeIf(String::isNotBlank) ?: trackSourceLabel(track.originalUrl).orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    PlayPauseButton(
                        playing = state.isPlaying,
                        onClick = onTogglePlayback,
                        modifier = Modifier.size(48.dp),
                        description = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                        iconSize = 24.dp,
                    )
                    IsleIconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_skip_next),
                            contentDescription = nextDescription,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (duration != null) {
                    val progress = (state.elapsedSecs.toFloat() / duration).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .background(IsleColors.sliderInactiveTrack),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}
