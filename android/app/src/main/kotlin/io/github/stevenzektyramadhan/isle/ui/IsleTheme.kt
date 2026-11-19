package io.github.stevenzektyramadhan.isle.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IsleColors = darkColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFF3F4F6),
    background = Color(0xFF0C0A16),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF151222),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF221D33),
    onSurfaceVariant = Color(0xFF9A90AD),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF0C0A16),
)

@Composable
fun IsleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IsleColors,
        content = content,
    )
}
