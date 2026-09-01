package com.varuna.opendash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    primaryContainer = InkSunken,
    onPrimaryContainer = Amber,
    secondary = Sky,
    onSecondary = Ink,
    secondaryContainer = InkSunken,
    onSecondaryContainer = Sky,
    tertiary = Good,
    onTertiary = Ink,
    background = Ink,
    onBackground = Bright,
    surface = Ink,
    onSurface = Bright,
    surfaceVariant = InkRaised,
    onSurfaceVariant = Muted,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkSunken,
    surfaceContainerHighest = InkSunken,
    outline = InkLine,
    outlineVariant = InkLine,
    error = Alarm,
    onError = Ink,
    errorContainer = InkSunken,
    onErrorContainer = Alarm,
)

private val LightScheme = lightColorScheme(
    primary = AmberInk,
    onPrimary = White,
    primaryContainer = PaperSunken,
    onPrimaryContainer = AmberInk,
    secondary = SkyInk,
    onSecondary = White,
    secondaryContainer = PaperSunken,
    onSecondaryContainer = SkyInk,
    tertiary = GoodInk,
    onTertiary = White,
    background = Paper,
    onBackground = Dark,
    surface = Paper,
    onSurface = Dark,
    surfaceVariant = PaperRaised,
    onSurfaceVariant = MutedInk,
    surfaceContainer = PaperRaised,
    surfaceContainerHigh = PaperSunken,
    surfaceContainerHighest = PaperSunken,
    outline = PaperLine,
    outlineVariant = PaperLine,
    error = AlarmInk,
    onError = White,
    errorContainer = PaperSunken,
    onErrorContainer = AlarmInk,
)

/**
 * Numbers are read at a glance while driving, so the value styles are a shade
 * larger and tabular: digits that change width make a gauge jitter.
 */
private val OpenDashTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
    )
}

/** Monospaced digits for anything that updates in place. */
val ValueStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
)

@Composable
fun OpenDashTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = OpenDashTypography,
        content = content,
    )
}
