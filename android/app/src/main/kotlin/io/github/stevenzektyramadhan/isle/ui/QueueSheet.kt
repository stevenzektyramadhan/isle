package io.github.stevenzektyramadhan.isle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stevenzektyramadhan.isle.R
import io.github.stevenzektyramadhan.isle.model.PlayerCommand
import io.github.stevenzektyramadhan.isle.model.PlayerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(state: PlayerState, onCommand: (PlayerCommand) -> Unit, onClose: () -> Unit) {
    val closeDescription = stringResource(R.string.close)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.70f).dp

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
                .heightIn(max = maxHeight),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.queue), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.queue_tracks, state.queue.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { onCommand(PlayerCommand.ClearQueue) },
                    enabled = state.queue.isNotEmpty(),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.queue_clear),
                        color = if (state.queue.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                IsleIconButton(onClick = onClose) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = closeDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.currentTrack?.let { track ->
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EqualizerBars(playing = state.isPlaying)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(R.string.queue_current),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                track.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            track.artist?.takeIf(String::isNotBlank)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.queue_up_next),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.queue.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.queue_empty), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.queue_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    itemsIndexed(state.queue) { index, item ->
                        TrackRow(
                            index = index + 1,
                            title = item.title,
                            artist = item.artist,
                            duration = item.durationSecs?.let(::formatPlayerTime) ?: "--:--",
                        )
                        if (index < state.queue.lastIndex) {
                            HorizontalDivider(color = IsleColors.divider)
                        }
                    }
                }
            }

            HorizontalDivider(color = IsleColors.divider)

            SwitchRow(
                title = stringResource(R.string.autoplay),
                subtitle = state.autoplayAnchor?.title?.let { stringResource(R.string.home_autoplay_anchor, it) }
                    ?: stringResource(R.string.autoplay_description),
                checked = state.autoplayEnabled,
                onCheckedChange = { onCommand(PlayerCommand.ToggleAutoplay) },
            )
        }
    }
}
