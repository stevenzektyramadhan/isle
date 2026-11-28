package io.github.stevenzektyramadhan.isle.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stevenzektyramadhan.isle.R

object IsleColors {
    val background = Color(0xFF121214)
    val recessed = Color(0xFF0C0C0E)
    val textPrimary = Color(0xFFF4F4F5)
    val textSecondary = Color(0xFFA1A1AA)
    val divider = Color(0x14FFFFFF)
    val subtleBorder = Color(0x12FFFFFF)
    val sliderInactiveTrack = Color(0x24FFFFFF)
    val artworkTop = Color(0xFF232329)
    val artworkBottom = Color(0xFF16161A)
    val artworkGroove = Color(0x14FFFFFF)
}

object IsleTypography {
    val tabularTime = TextStyle(
        fontFamily = IsleUiFonts.mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.02.sp,
        fontFeatureSettings = "tnum",
    )

    val tabularDuration = TextStyle(
        fontFamily = IsleUiFonts.mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    )
}

private val colors = darkColorScheme(
    primary = Color(0xFF83CBB8),
    onPrimary = Color(0xFF0E251F),
    primaryContainer = Color(0xFF68B6A2),
    onPrimaryContainer = Color(0xFF081D18),
    inversePrimary = Color(0xFF2B6F5F),
    secondary = Color(0xFFA4D8CA),
    onSecondary = Color(0xFF102822),
    secondaryContainer = Color(0xFF22332E),
    onSecondaryContainer = Color(0xFFCCEBE2),
    tertiary = Color(0xFFC8D5E3),
    onTertiary = Color(0xFF1A2632),
    tertiaryContainer = Color(0xFF2A3440),
    onTertiaryContainer = Color(0xFFE2EBF4),
    error = Color(0xFFF29C94),
    onError = Color(0xFF471210),
    errorContainer = Color(0xFF3A1E1E),
    onErrorContainer = Color(0xFFFAD8D4),
    background = IsleColors.background,
    onBackground = IsleColors.textPrimary,
    surface = IsleColors.background,
    onSurface = IsleColors.textPrimary,
    surfaceVariant = Color(0xFF2A2A30),
    onSurfaceVariant = IsleColors.textSecondary,
    surfaceTint = Color(0xFF83CBB8),
    inverseSurface = Color(0xFFF4F4F5),
    inverseOnSurface = Color(0xFF1C1C20),
    outline = Color(0xFF52525B),
    outlineVariant = Color(0xFF2B2B31),
    surfaceDim = Color(0xFF101012),
    surfaceBright = Color(0xFF2E2E35),
    surfaceContainerLowest = Color(0xFF0C0C0E),
    surfaceContainerLow = Color(0xFF18181B),
    surfaceContainer = Color(0xFF1E1E22),
    surfaceContainerHigh = Color(0xFF26262C),
    surfaceContainerHighest = Color(0xFF303037),
)

private val typography = Typography(
    displayLarge = role(32, 40, FontWeight.Bold, -0.4f),
    headlineLarge = role(28, 34, FontWeight.Bold, -0.3f),
    headlineMedium = role(22, 28, FontWeight.SemiBold, -0.2f),
    headlineSmall = role(18, 24, FontWeight.SemiBold, -0.1f),
    titleLarge = role(20, 26, FontWeight.SemiBold, -0.1f),
    titleMedium = role(16, 22, FontWeight.SemiBold, 0f),
    titleSmall = role(15, 20, FontWeight.SemiBold, 0f),
    bodyLarge = role(16, 24, FontWeight.Normal, 0f),
    bodyMedium = role(14, 20, FontWeight.Normal, 0f),
    bodySmall = role(12, 16, FontWeight.Normal, 0.05f),
    labelLarge = role(14, 20, FontWeight.SemiBold, 0f),
    labelMedium = role(12, 16, FontWeight.Medium, 0.1f),
    labelSmall = role(11, 14, FontWeight.Medium, 0.15f),
)

private fun role(size: Int, line: Int, weight: FontWeight, spacing: Float) =
    TextStyle(
        fontFamily = IsleUiFonts.inter,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = spacing.sp,
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
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun IsleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
