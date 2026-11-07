package dev.avijit.orbithealth.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Material's colour roles mapped onto the Living Skies palette (tokens.md §1), so the bits Material still draws itself
 * (text cursor and selection handles, the pull-to-refresh indicator, ripples, dialogs, switches) match the app rather
 * than the old Glass Orbit hues: primary is the sleep lavender, surfaces are the midnight bases, text is warm ink.
 */
private val OrbitScheme = darkColorScheme(
    primary = OrbitPalette.SleepB,
    onPrimary = OrbitPalette.OnInk,
    primaryContainer = OrbitPalette.TintSleep,
    onPrimaryContainer = OrbitPalette.Ink,
    secondary = OrbitPalette.ReadinessA,
    onSecondary = OrbitPalette.OnInk,
    secondaryContainer = OrbitPalette.TintReadiness,
    onSecondaryContainer = OrbitPalette.Ink,
    tertiary = OrbitPalette.ActivityB,
    onTertiary = OrbitPalette.OnInk,
    tertiaryContainer = OrbitPalette.TintActivity,
    onTertiaryContainer = OrbitPalette.Ink,
    background = OrbitPalette.Base0,
    onBackground = OrbitPalette.Ink,
    surface = OrbitPalette.Surface,
    onSurface = OrbitPalette.Ink,
    surfaceVariant = OrbitPalette.TintNeutral,
    onSurfaceVariant = OrbitPalette.Ink2,
    surfaceTint = OrbitPalette.SleepB,
    inverseSurface = OrbitPalette.Ink,
    inverseOnSurface = OrbitPalette.Base0,
    inversePrimary = OrbitPalette.SleepA,
    error = OrbitPalette.Attention,
    onError = OrbitPalette.OnInk,
    outline = OrbitPalette.HairlineWarm,
    outlineVariant = OrbitPalette.Hairline,
    scrim = OrbitPalette.Base0,
    surfaceBright = OrbitPalette.Base1,
    surfaceDim = OrbitPalette.Base0,
    surfaceContainerLowest = OrbitPalette.Base0,
    surfaceContainerLow = OrbitPalette.Surface,
    surfaceContainer = OrbitPalette.Surface,
    surfaceContainerHigh = OrbitPalette.Base1,
    surfaceContainerHighest = OrbitPalette.TintNeutral,
)

@Composable
fun OrbitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OrbitScheme, typography = OrbitMaterialTypography, content = content)
}
