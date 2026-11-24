package io.github.stevenzektyramadhan.isle.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.dp

private val IsleColors = darkColorScheme(
    primary = Color(0xFF70FFE5),
    onPrimary = Color(0xFF00372F),
    primaryContainer = Color(0xFF00E5C9),
    onPrimaryContainer = Color(0xFF00372F),
    secondary = Color(0xFF44E2CD),
    background = Color(0xFF101419),
    onBackground = Color(0xFFE0E2EA),
    surface = Color(0xFF181C21),
    onSurface = Color(0xFFE0E2EA),
    surfaceVariant = Color(0xFF262A30),
    onSurfaceVariant = Color(0xFFB9CAC5),
    outline = Color(0xFF84948F),
    outlineVariant = Color(0xFF3B4A46),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val IsleShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun IsleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IsleColors,
        shapes = IsleShapes,
        typography = Typography(),
        content = content,
    )
}
