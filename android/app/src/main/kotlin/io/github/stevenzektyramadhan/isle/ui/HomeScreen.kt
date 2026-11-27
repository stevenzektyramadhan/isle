package io.github.stevenzektyramadhan.isle.ui

import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stevenzektyramadhan.isle.R
import io.github.stevenzektyramadhan.isle.model.PlayerState
import io.github.stevenzektyramadhan.isle.model.PlayerStatus
import io.github.stevenzektyramadhan.isle.model.classifyPlayerStatus

@Composable
fun HomeScreen(
    state: HomeUiState,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
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
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                    .imePadding().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = if (state.player.currentTrack == null) 24.dp else 112.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge, color = IsleColors.textPrimary)
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = settingsDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                updateNotice()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clip(CircleShape)
                            .background(IsleColors.recessed)
                            .then(if (focused) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape) else Modifier)
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        BasicTextField(
                            value = state.input,
                            onValueChange = onInputChange,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 16.dp)
                                .onFocusChanged { focused = it.isFocused },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = IsleColors.textPrimary),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = true,
                            readOnly = state.isLoading,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { if (state.input.isNotBlank() && !state.isLoading) onSubmit() }),
                            decorationBox = { inner ->
                                Box {
                                    if (state.input.isEmpty()) Text(stringResource(R.string.home_search_placeholder), color = IsleColors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                                    inner()
                                }
                            },
                        )
                        if (state.input.isNotEmpty()) {
                            IconButton(onClick = { onInputChange("") }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    contentDescription = clearDescription,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else if (!state.isLoading) {
                            IconButton(
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
                        Surface(
                            onClick = { if (state.input.isNotBlank() && !state.isLoading) onSubmit() },
                            enabled = state.input.isNotBlank() && !state.isLoading,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = searchDescription }, shape = CircleShape,
                            color = if (state.input.isNotBlank() && !state.isLoading) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (state.isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                                else Icon(
                                    painterResource(R.drawable.ic_arrow_forward),
                                    contentDescription = null,
                                    tint = if (state.input.isNotBlank()) IsleColors.recessed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                    Text(stringResource(R.string.home_search_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                when {
                    state.isLoading -> StatusCard(
                        title = stringResource(R.string.home_searching_title),
                        message = resources.getString(R.string.home_searching_message, state.input.trim()),
                        error = false,
                        actions = { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary) },
                    )
                    (state.status ?: state.statusMessage?.let(::classifyPlayerStatus)) != null && !state.statusDismissed -> {
                        val playerStatus = state.status ?: classifyPlayerStatus(state.statusMessage!!)
                        val status = mapStatus(playerStatus)
                        StatusCard(
                            title = status.title(context), message = status.message(context), error = status.severity == StatusSeverity.Error,
                            detail = status.detail,
                            actions = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (StatusAction.Retry in status.actions && state.input.isNotBlank()) {
                                        androidx.compose.material3.TextButton(onClick = onSubmit) { Text(stringResource(R.string.status_retry)) }
                                    }
                                    if (StatusAction.Settings in status.actions) {
                                        androidx.compose.material3.TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.status_settings)) }
                                    }
                                    androidx.compose.material3.TextButton(onClick = onDismissStatus) { Text(stringResource(R.string.status_dismiss)) }
                                }
                            },
                        )
                    }
                    state.player.currentTrack != null -> HomeNowPlaying(state, onOpenQueue)
                    else -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DiscView(Modifier.size(184.dp), label = "ISLE", playing = false)
                        Text(stringResource(R.string.home_idle_title), style = MaterialTheme.typography.headlineMedium, color = IsleColors.textPrimary)
                        Text(stringResource(R.string.home_idle_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.player.currentTrack != null) MiniPlayer(state.player, onTogglePlayback, onNext, onOpenPlayer, Modifier.align(Alignment.BottomCenter))
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
private fun HomeNowPlaying(state: HomeUiState, onOpenQueue: () -> Unit) {
    val track = state.player.currentTrack ?: return
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DiscView(Modifier.size(176.dp), label = "ISLE", playing = state.player.isPlaying)
        Text(track.title, style = MaterialTheme.typography.headlineMedium, color = IsleColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        track.artist?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (state.player.autoplayEnabled && state.player.autoplayAnchor != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                Text(stringResource(R.string.home_autoplay_anchor, state.player.autoplayAnchor.title), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.player.queue.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onOpenQueue), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.home_up_next, state.player.queue.size), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.home_view_queue), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                state.player.queue.take(2).forEachIndexed { index, item ->
                    TrackRow(index + 1, item.title, item.artist, item.durationSecs?.let(::formatPlayerTime) ?: "--:--")
                }
                if (state.player.queue.all { it.durationSecs != null }) {
                    Text(stringResource(R.string.home_queue_duration, formatPlayerTime(state.player.queue.sumOf { it.durationSecs ?: 0L })), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: PlayerState, onTogglePlayback: () -> Unit, onNext: () -> Unit, onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val nextDescription = stringResource(R.string.home_next_track)
    val track = state.currentTrack ?: return
    Column(modifier.fillMaxWidth().navigationBarsPadding()) {
        val duration = state.durationSecs?.takeIf { it > 0 }
        if (duration != null) LinearProgressIndicator(
            progress = { (state.elapsedSecs.toFloat() / duration).coerceIn(0f, 1f) },
            Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f), border = BorderStroke(1.dp, IsleColors.hairline)) {
            Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.weight(1f).clickable(onClick = onOpenPlayer), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiscView(Modifier.size(44.dp), label = "ISLE", playing = state.isPlaying)
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(track.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PlayPauseButton(state.isPlaying, onTogglePlayback, Modifier.size(48.dp), stringResource(if (state.isPlaying) R.string.pause else R.string.play))
                IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = nextDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
