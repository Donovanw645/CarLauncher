package com.donovan.carlauncher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A launcher that lives on a windscreen is read at a glance, in sunlight, at speed.
// Everything here is tuned for contrast and size rather than subtlety.
val CarBackground = Color(0xFF07090C)
val CarSurface = Color(0xFF11151B)
val CarSurfaceHigh = Color(0xFF1A2027)
val CarOutline = Color(0xFF2B343E)
val CarTextPrimary = Color(0xFFEDF2F7)
val CarTextSecondary = Color(0xFF9AACBB)
val CarDanger = Color(0xFFFF6B6B)
val CarWarn = Color(0xFFFFC14D)

private val CarTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 68.sp,
        lineHeight = 72.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.4.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.6.sp,
    ),
)

@Composable
fun CarLauncherTheme(
    accent: Color = Color(0xFF3DDC97),
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // the launcher is always dark; read for recomposition parity

    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF04150C),
        primaryContainer = accent.copy(alpha = 0.18f),
        onPrimaryContainer = accent,
        secondary = Color(0xFF7FB2FF),
        onSecondary = Color(0xFF03122B),
        background = CarBackground,
        onBackground = CarTextPrimary,
        surface = CarSurface,
        onSurface = CarTextPrimary,
        surfaceVariant = CarSurfaceHigh,
        onSurfaceVariant = CarTextSecondary,
        surfaceContainerHigh = CarSurfaceHigh,
        outline = CarOutline,
        outlineVariant = CarOutline,
        error = CarDanger,
        onError = Color(0xFF2A0606),
        scrim = Color(0xCC000000),
    )

    MaterialTheme(
        colorScheme = scheme,
        typography = CarTypography,
        content = content,
    )
}
