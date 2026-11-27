package io.github.stevenzektyramadhan.isle.ui

import io.github.stevenzektyramadhan.isle.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object IsleColors {
    val recessed = Color(0xFF090C0F)
    val textPrimary = Color(0xFFF1F5F9)
    val textSecondary = Color(0xFF94A3B8)
    val hairline = Color(0x14F1F5F9)
    val playGlow = Color(0x4000E5C9)
    val sliderInactiveTrack = Color(0x1FFFFFFF)
    val discRing = Color(0x0DFFFFFF)
    val discArc = Color(0x38FFFFFF)
}

private val colors = darkColorScheme(
    primary = Color(0xFF70FFE5), onPrimary = Color(0xFF00372F),
    primaryContainer = Color(0xFF00E5C9), onPrimaryContainer = Color(0xFF006155),
    inversePrimary = Color(0xFF006B5D), secondary = Color(0xFF44E2CD),
    onSecondary = Color(0xFF003731), secondaryContainer = Color(0xFF03C6B2),
    onSecondaryContainer = Color(0xFF004D44), tertiary = Color(0xFFD1ECFF),
    onTertiary = Color(0xFF00354A), tertiaryContainer = Color(0xFF8CD5FF),
    onTertiaryContainer = Color(0xFF005D7F), error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005), errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6), background = Color(0xFF101419),
    onBackground = Color(0xFFE0E2EA), surface = Color(0xFF101419),
    onSurface = Color(0xFFE0E2EA), surfaceVariant = Color(0xFF31353B),
    onSurfaceVariant = Color(0xFFB9CAC5), surfaceTint = Color(0xFF00DFC4),
    inverseSurface = Color(0xFFE0E2EA), inverseOnSurface = Color(0xFF2D3136),
    outline = Color(0xFF84948F), outlineVariant = Color(0xFF3B4A46),
    surfaceDim = Color(0xFF101419), surfaceBright = Color(0xFF36393F),
    surfaceContainerLowest = Color(0xFF0A0E13), surfaceContainerLow = Color(0xFF181C21),
    surfaceContainer = Color(0xFF1C2025), surfaceContainerHigh = Color(0xFF262A30),
    surfaceContainerHighest = Color(0xFF31353B),
)

private val typography = Typography(
    displayLarge = role(32, 40, FontWeight.Bold, IsleUiFonts.inter),
    headlineLarge = role(28, 36, FontWeight.SemiBold, IsleUiFonts.inter),
    headlineMedium = role(22, 28, FontWeight.SemiBold, IsleUiFonts.inter),
    headlineSmall = role(18, 24, FontWeight.SemiBold, IsleUiFonts.inter),
    titleLarge = role(22, 28, FontWeight.SemiBold, IsleUiFonts.inter),
    titleMedium = role(18, 24, FontWeight.SemiBold, IsleUiFonts.inter),
    titleSmall = role(16, 24, FontWeight.SemiBold, IsleUiFonts.inter),
    bodyLarge = role(16, 24, FontWeight.Normal, IsleUiFonts.inter),
    bodyMedium = role(14, 20, FontWeight.Normal, IsleUiFonts.inter),
    bodySmall = role(12, 16, FontWeight.Normal, IsleUiFonts.inter),
    labelLarge = role(13, 18, FontWeight.Medium, IsleUiFonts.mono, 0.02f),
    labelMedium = role(11, 16, FontWeight.Medium, IsleUiFonts.mono, 0.04f),
    labelSmall = role(10, 14, FontWeight.Medium, IsleUiFonts.mono, 0.05f),
)

private fun role(size: Int, line: Int, weight: FontWeight, family: FontFamily, spacing: Float = 0f) =
    androidx.compose.ui.text.TextStyle(
        fontFamily = family, fontWeight = weight, fontSize = size.sp,
        lineHeight = line.sp, letterSpacing = spacing.sp,
        fontFeatureSettings = if (family == IsleUiFonts.mono) "tnum" else null,
    )

internal object IsleUiFonts {
    val inter = FontFamily(
        Font(R.font.isle_inter_regular, FontWeight.Normal),
        Font(R.font.isle_inter_semibold, FontWeight.SemiBold),
        Font(R.font.isle_inter_bold, FontWeight.Bold),
    )
    val mono = FontFamily(Font(R.font.isle_jetbrains_mono_medium, FontWeight.Medium))
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun IsleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
