package io.github.stevenzektyramadhan.isle.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (landscape) 8.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (landscape) 0.dp else 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                TextButton(onClick = { queueOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Queue (${state.queue.size})") }
            }
            Box(
                modifier = Modifier.size(if (landscape) 72.dp else 220.dp).rotate(angle.value)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = if (landscape) 8.dp else 20.dp).size(if (landscape) 4.dp else 8.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                Box(Modifier.size(if (landscape) 32.dp else 96.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (landscape) 4.dp else 12.dp).background(MaterialTheme.colorScheme.background, CircleShape))
                }
            }
            Column(Modifier.fillMaxWidth()) {
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
                TextButton(onClick = { onCommand(PlayerCommand.Previous) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous") }
                Button(onClick = { onCommand(PlayerCommand.TogglePlayPause) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.isPlaying) "Pause" else "Play") }
                TextButton(onClick = { onCommand(PlayerCommand.Next) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
            }
            Text("Volume ${(state.volume * 100).toInt()}%", modifier = Modifier.fillMaxWidth())
            Slider(value = state.volume.coerceIn(0f, 1f), onValueChange = { onCommand(PlayerCommand.SetVolume(it)) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Volume" })
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Autoplay")
                Switch(checked = state.autoplayEnabled, onCheckedChange = { onCommand(PlayerCommand.ToggleAutoplay) }, modifier = Modifier.semantics { contentDescription = "Autoplay" })
            }
        }
    }
    if (queueOpen) {
        ModalBottomSheet(onDismissRequest = { queueOpen = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).heightIn(max = 500.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Queue (${state.queue.size})", style = MaterialTheme.typography.titleLarge)
                    Row {
                        TextButton(onClick = { onCommand(PlayerCommand.ClearQueue) }, enabled = state.queue.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
                        TextButton(onClick = { queueOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                    }
                }
                if (state.queue.isEmpty()) Text("Queue is empty", modifier = Modifier.padding(vertical = 24.dp))
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.queue.forEach { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.artist?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
