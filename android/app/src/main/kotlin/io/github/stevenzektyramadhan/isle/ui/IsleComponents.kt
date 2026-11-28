package io.github.stevenzektyramadhan.isle.ui

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stevenzektyramadhan.isle.R
import kotlin.math.PI
import kotlin.math.sin

fun Modifier.pressScale(pressed: Boolean, motionAllowed: Boolean, targetScale: Float = 0.94f): Modifier = composed {
    val scale by animateFloatAsState(
        targetValue = if (pressed && motionAllowed) targetScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press-scale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun IsleIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val motionAllowed = rememberMotionEnabled()
    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .pressScale(pressed, motionAllowed, 0.90f),
        content = content,
    )
}

@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String,
    iconSize: androidx.compose.ui.unit.Dp = 30.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val motionAllowed = rememberMotionEnabled()
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        interactionSource = interactionSource,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .pressScale(pressed, motionAllowed, 0.93f),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = description,
            modifier = Modifier.size(iconSize),
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
    val haptics = LocalHapticFeedback.current
    var wasPressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed && !wasPressed && enabled) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        wasPressed = pressed
    }

    val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val inactiveColor = if (enabled) IsleColors.sliderInactiveTrack else IsleColors.sliderInactiveTrack.copy(alpha = 0.45f)
    val thumbColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val thumbSize by animateDpAsState(
        targetValue = if (pressed && enabled) 16.dp else 12.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "slider-thumb",
    )

    val colors = SliderDefaults.colors(
        thumbColor = thumbColor,
        activeTrackColor = activeColor,
        inactiveTrackColor = inactiveColor,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = {
            if (enabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onValueChangeFinished()
        },
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            Box(
                modifier = Modifier
                    .size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(thumbSize)
                        .clip(CircleShape)
                        .background(thumbColor),
                )
            }
        },
        track = { sliderState ->
            val range = (sliderState.valueRange.endInclusive - sliderState.valueRange.start).takeIf { it > 0f } ?: 1f
            val fraction = ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            ) {
                val centerY = size.height / 2f
                val stroke = size.height
                drawLine(
                    color = inactiveColor,
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                if (fraction > 0f) {
                    drawLine(
                        color = activeColor,
                        start = Offset(0f, centerY),
                        end = Offset(size.width * fraction, centerY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        },
        modifier = modifier.semantics {
            contentDescription = semanticsName
            this.stateDescription = stateDescription
        },
    )
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .clip(MaterialTheme.shapes.medium)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChange(it)
                },
            )
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun TrackArtworkCard(
    playing: Boolean,
    sourceLabel: String?,
    modifier: Modifier = Modifier,
) {
    val motionAllowed = rememberMotionEnabled()
    val primary = MaterialTheme.colorScheme.primary
    val subtleRing = IsleColors.artworkGroove
    val transition = rememberInfiniteTransition(label = "artwork-motion")
    val spin = if (playing && motionAllowed) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(22_000, easing = LinearEasing)),
            label = "vinyl-angle",
        )
    } else null
    val wavePhase = if (playing && motionAllowed) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing)),
            label = "wave-phase",
        )
    } else null

    val cardBrush = Brush.linearGradient(listOf(IsleColors.artworkTop, IsleColors.artworkBottom))

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, IsleColors.subtleBorder),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(cardBrush),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension * 0.34f

                // Subtle organic vinyl rings without loud labels or neon glow
                rotate(spin?.value ?: 0f, pivot = center) {
                    listOf(1.0f, 0.80f, 0.60f, 0.40f).forEach { factor ->
                        drawCircle(
                            color = subtleRing,
                            radius = maxRadius * factor,
                            center = center,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                    drawArc(
                        color = primary.copy(alpha = if (playing) 0.42f else 0.18f),
                        startAngle = -35f,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = Offset(center.x - maxRadius * 0.80f, center.y - maxRadius * 0.80f),
                        size = androidx.compose.ui.geometry.Size(maxRadius * 1.60f, maxRadius * 1.60f),
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }

                // Minimal tactile wave bars centered inside the vinyl core
                val barCount = 5
                val barSpacing = 6.dp.toPx()
                val barWidth = 2.5.dp.toPx()
                val totalWidth = (barCount - 1) * barSpacing
                val phase = wavePhase?.value ?: 0.7f
                val maxHalfHeight = maxRadius * 0.24f
                for (i in 0 until barCount) {
                    val x = center.x - totalWidth / 2f + i * barSpacing
                    val amp = if (playing) {
                        0.38f + 0.62f * ((sin(phase + i * 0.9f) + 1f) / 2f)
                    } else {
                        listOf(0.32f, 0.58f, 0.82f, 0.52f, 0.36f)[i]
                    }
                    val half = maxHalfHeight * amp
                    drawLine(
                        color = primary.copy(alpha = if (playing) 0.88f else 0.5f),
                        start = Offset(x, center.y - half),
                        end = Offset(x, center.y + half),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }

            if (sourceLabel != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.72f),
                ) {
                    Text(
                        text = sourceLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun EqualizerBars(modifier: Modifier = Modifier, playing: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    val motionAllowed = rememberMotionEnabled()
    val heights = if (playing && motionAllowed) {
        val transition = rememberInfiniteTransition(label = "equalizer")
        listOf(0.32f, 0.48f, 0.24f).mapIndexed { index, low ->
            transition.animateFloat(
                initialValue = low,
                targetValue = 0.88f,
                animationSpec = infiniteRepeatable(
                    tween(420 + index * 100, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "equalizer-bar-$index",
            )
        }
    } else null

    Canvas(modifier.size(width = 18.dp, height = 18.dp)) {
        val barWidth = size.width / 5.5f
        listOf(0.42f, 0.80f, 0.58f).forEachIndexed { index, fallback ->
            val x = barWidth * (index * 2 + 0.75f)
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
fun StatusCard(
    title: String,
    message: String,
    error: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actions: @Composable () -> Unit = {},
) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val titleColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val bodyColor = if (error) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.88f) else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = bodyColor)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = bodyColor.copy(alpha = 0.8f))
            }
            actions()
        }
    }
}

@Composable
fun TrackRow(index: Int, title: String, artist: String?, duration: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$index",
            modifier = Modifier.width(28.dp),
            style = IsleTypography.tabularDuration,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            artist?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(duration, style = IsleTypography.tabularDuration, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

internal fun trackSourceLabel(originalUrl: String): String? {
    val host = runCatching { Uri.parse(originalUrl).host?.lowercase() }.getOrNull().orEmpty()
    return when {
        host.contains("youtube") || host == "youtu.be" -> "YouTube"
        host.contains("spotify") -> "Spotify"
        host.isNotBlank() -> host.removePrefix("www.")
        else -> null
    }
}
