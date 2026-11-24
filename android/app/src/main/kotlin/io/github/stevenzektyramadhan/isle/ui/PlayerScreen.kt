package io.github.stevenzektyramadhan.isle.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stevenzektyramadhan.isle.model.PlayerCommand
import io.github.stevenzektyramadhan.isle.model.PlayerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(state: PlayerState, onCommand: (PlayerCommand) -> Unit, onClose: () -> Unit) {
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (queueOpen) queueOpen = false else onClose() }
    val track = state.currentTrack ?: return
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val angle = remember { Animatable(0f) }
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            angle.animateTo(360f, tween(((360f - angle.value) / 360f * 12000).toInt().coerceAtLeast(1), easing = LinearEasing))
            angle.snapTo(0f)
        }
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (landscape) 12.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (landscape) 4.dp else 20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                Text("NOW PLAYING", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { queueOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Queue (${state.queue.size})") }
            }
            Box(
                modifier = Modifier.size(if (landscape) 108.dp else 240.dp).rotate(angle.value)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (landscape) 88.dp else 216.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (landscape) 32.dp else 88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (landscape) 6.dp else 14.dp).background(MaterialTheme.colorScheme.background, CircleShape))
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                track.artist?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val duration = state.durationSecs?.takeIf { it > 0 }
            Slider(
                value = if (duration != null) (state.elapsedSecs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { if (duration != null) onCommand(PlayerCommand.SeekTo((it * duration).toLong())) },
                enabled = duration != null,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Seek" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatPlayerTime(state.elapsedSecs))
                Text(duration?.let(::formatPlayerTime) ?: "--:--")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onCommand(PlayerCommand.Previous) }, modifier = Modifier.size(52.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp)) { Text("|◀") }
                Button(onClick = { onCommand(PlayerCommand.TogglePlayPause) }, modifier = Modifier.size(68.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp)) { Text(if (state.isPlaying) "Ⅱ" else "▶", style = MaterialTheme.typography.titleLarge) }
                OutlinedButton(onClick = { onCommand(PlayerCommand.Next) }, modifier = Modifier.size(52.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp)) { Text("▶|") }
            }
            Text("Volume ${(state.volume * 100).toInt()}%", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Slider(value = state.volume.coerceIn(0f, 1f), onValueChange = { onCommand(PlayerCommand.SetVolume(it)) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Volume" })
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Autoplay", style = MaterialTheme.typography.titleSmall)
                        Text("Continue with recommendations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.autoplayEnabled, onCheckedChange = { onCommand(PlayerCommand.ToggleAutoplay) }, modifier = Modifier.semantics { contentDescription = "Autoplay" })
                }
            }
        }
    }
    if (queueOpen) {
        ModalBottomSheet(onDismissRequest = { queueOpen = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).heightIn(max = 500.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Queue (${state.queue.size})", style = MaterialTheme.typography.titleLarge)
                    Row {
                        TextButton(onClick = { onCommand(PlayerCommand.ClearQueue) }, enabled = state.queue.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
                        TextButton(onClick = { queueOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                    }
                }
                if (state.queue.isEmpty()) Text("Queue is empty", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.queue.forEachIndexed { index, item ->
                        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("%02d".format(index + 1), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                Column {
                                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    item.artist?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun formatPlayerTime(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return if (safe >= 3600) "%d:%02d:%02d".format(safe / 3600, safe / 60 % 60, safe % 60)
    else "%d:%02d".format(safe / 60, safe % 60)
}
