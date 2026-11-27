package io.github.stevenzektyramadhan.isle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stevenzektyramadhan.isle.R
import io.github.stevenzektyramadhan.isle.model.PlayerCommand
import io.github.stevenzektyramadhan.isle.model.PlayerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(state: PlayerState, onCommand: (PlayerCommand) -> Unit, onClose: () -> Unit) {
    val closeDescription = stringResource(R.string.close)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.65f).dp
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).heightIn(max = maxHeight),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.queue), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                Text("${state.queue.size} ${stringResource(R.string.queue_tracks)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onCommand(PlayerCommand.ClearQueue) }, enabled = state.queue.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.queue_clear), color = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = closeDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.currentTrack?.let { track ->
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EqualizerBars(playing = state.isPlaying)
                        Column {
                            Text(stringResource(R.string.queue_current), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(track.title, style = MaterialTheme.typography.titleSmall)
                            track.artist?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            Text(stringResource(R.string.queue_up_next), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.queue.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.queue_empty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.queue_empty_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    itemsIndexed(state.queue) { index, item ->
                        TrackRow(index + 1, item.title, item.artist, item.durationSecs?.let(::formatPlayerTime) ?: "--:--")
                    }
                }
            }
            SwitchRow(
                title = stringResource(R.string.autoplay),
                subtitle = state.autoplayAnchor?.title?.let { stringResource(R.string.home_autoplay_anchor, it) } ?: stringResource(R.string.autoplay_description),
                checked = state.autoplayEnabled,
                onCheckedChange = { onCommand(PlayerCommand.ToggleAutoplay) },
            )
        }
    }
}
