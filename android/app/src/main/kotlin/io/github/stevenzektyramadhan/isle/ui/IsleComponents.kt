package io.github.stevenzektyramadhan.isle.ui

import io.github.stevenzektyramadhan.isle.R
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun IsleIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    IconButton(onClick, modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp), content = content)
}

@Composable
fun PlayPauseButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, description: String) {
    Button(
        onClick = onClick,
        modifier = modifier.size(64.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = description,
            tint = IsleColors.recessed,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun IsleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    semanticsName: String,
    stateDescription: String,
    onValueChangeFinished: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primaryContainer,
        activeTrackColor = MaterialTheme.colorScheme.primaryContainer,
        inactiveTrackColor = IsleColors.sliderInactiveTrack,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        thumb = { SliderDefaults.Thumb(interactionSource, colors = colors, enabled = enabled, thumbSize = DpSize(if (pressed) 14.dp else 8.dp, if (pressed) 14.dp else 8.dp)) },
        track = { sliderState -> SliderDefaults.Track(sliderState = sliderState, colors = colors, enabled = enabled) },
        modifier = modifier.semantics {
            contentDescription = semanticsName
            this.stateDescription = stateDescription
        },
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
fun DiscView(modifier: Modifier = Modifier, label: String, playing: Boolean) {
    val primary = MaterialTheme.colorScheme.primaryContainer
    val track = IsleColors.discRing
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val motionAllowed = rememberMotionEnabled()
    val angle = if (playing && motionAllowed) {
        rememberInfiniteTransition(label = "disc-spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
            label = "disc-angle",
        )
    } else null

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            rotate(angle?.value ?: 0f) {
                val radius = size.minDimension / 2
                listOf(0.98f, 0.86f, 0.68f, 0.48f, 0.24f).forEachIndexed { index, scale ->
                    drawCircle(
                        color = if (index == 4) primary else track,
                        radius = radius * scale,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (index == 4) radius * 0.12f else 1.dp.toPx()),
                    )
                }
                drawArc(
                    color = IsleColors.discArc,
                    startAngle = -35f,
                    sweepAngle = 72f,
                    useCenter = false,
                    topLeft = Offset(radius * 0.24f, radius * 0.24f),
                    size = androidx.compose.ui.geometry.Size(radius * 1.52f, radius * 1.52f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
    }
}

@Composable
fun EqualizerBars(modifier: Modifier = Modifier, playing: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    val motionAllowed = rememberMotionEnabled()
    val heights = if (playing && motionAllowed) {
        val transition = rememberInfiniteTransition(label = "equalizer")
        listOf(0.35f, 0.5f, 0.25f).mapIndexed { index, low ->
            transition.animateFloat(
                initialValue = low,
                targetValue = 0.85f,
                animationSpec = infiniteRepeatable(tween(380 + index * 90, easing = LinearEasing), RepeatMode.Reverse),
                label = "equalizer-bar-$index",
            )
        }
    } else null

    Canvas(modifier.size(width = 20.dp, height = 20.dp)) {
        val barWidth = size.width / 5
        listOf(0.45f, 0.82f, 0.62f).forEachIndexed { index, fallback ->
            val x = barWidth * (index * 2 + 0.5f)
            val fraction = heights?.get(index)?.value ?: fallback
            drawLine(color, Offset(x, size.height * (1 - fraction)), Offset(x, size.height), barWidth, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) {
        mutableStateOf(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f)
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
            }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

@Composable
fun StatusCard(title: String, message: String, error: Boolean, modifier: Modifier = Modifier, detail: String? = null, actions: @Composable () -> Unit = {}) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container), border = BorderStroke(1.dp, IsleColors.hairline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            actions()
        }
    }
}

@Composable
fun TrackRow(index: Int, title: String, artist: String?, duration: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(index), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            artist?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(duration, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, IsleColors.hairline)) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}
