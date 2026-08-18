package com.hardplay.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle

/**
 * There is exactly one theme. HardPlay is a dark app that shows video; a light
 * mode would be worse in every situation it would ever be used in, and
 * following the system setting would mean maintaining a palette nobody wants.
 * The system dark-theme flag is deliberately not consulted.
 */

private val LocalHardPlayColors = staticCompositionLocalOf<HardPlayColors> {
    error("HardPlayColors not provided — wrap the content in HardPlayTheme { }")
}

private val LocalHardPlayTypography = staticCompositionLocalOf<HardPlayTypography> {
    error("HardPlayTypography not provided — wrap the content in HardPlayTheme { }")
}

private val LocalHardPlayShapes = staticCompositionLocalOf<HardPlayShapes> {
    error("HardPlayShapes not provided — wrap the content in HardPlayTheme { }")
}

/** Token accessors. `HardPlayTheme.colors.accent`, `HardPlayTheme.type.display`, etc. */
object HardPlayTheme {
    val colors: HardPlayColors
        @Composable @ReadOnlyComposable get() = LocalHardPlayColors.current

    val type: HardPlayTypography
        @Composable @ReadOnlyComposable get() = LocalHardPlayTypography.current

    val shapes: HardPlayShapes
        @Composable @ReadOnlyComposable get() = LocalHardPlayShapes.current
}

/**
 * Material3's scheme is populated too, because a few M3 components (bottom
 * sheets, text fields, the ripple) read from it and would otherwise render in
 * default purple. Nothing in this app should reference `MaterialTheme.colorScheme`
 * directly — use `HardPlayTheme.colors`.
 */
private fun materialSchemeFrom(c: HardPlayColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentSunken,
    onPrimaryContainer = c.type,
    secondary = c.muted,
    onSecondary = c.type,
    secondaryContainer = c.surfaceRaised,
    onSecondaryContainer = c.type,
    tertiary = c.accentHigh,
    onTertiary = c.onAccent,
    background = c.bg,
    onBackground = c.type,
    surface = c.surface,
    onSurface = c.type,
    surfaceVariant = c.surfaceRaised,
    onSurfaceVariant = c.typeDim,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surfaceRaised,
    surfaceContainerHighest = c.surfaceRaised,
    surfaceContainerLow = c.bgRaised,
    surfaceContainerLowest = c.bg,
    surfaceTint = c.accent,
    inverseSurface = c.type,
    inverseOnSurface = c.bg,
    error = c.danger,
    onError = c.type,
    errorContainer = c.danger,
    onErrorContainer = c.type,
    outline = c.border,
    outlineVariant = c.hairline,
    scrim = c.scrim,
)

@Composable
fun HardPlayTheme(
    colors: HardPlayColors = HardPlayDarkColors,
    content: @Composable () -> Unit,
) {
    // Ember ripple instead of the default white-on-primary. rippleAlpha is left
    // at the Material default; only the hue changes.
    val rippleConfig = RippleConfiguration(color = colors.accentHigh)

    CompositionLocalProvider(
        LocalHardPlayColors provides colors,
        LocalHardPlayTypography provides HardPlayType,
        LocalHardPlayShapes provides HardPlayShapeSet,
        LocalContentColor provides colors.type,
        LocalTextStyle provides HardPlayType.body.copy(color = colors.type),
        LocalRippleConfiguration provides rippleConfig,
    ) {
        MaterialTheme(
            colorScheme = materialSchemeFrom(colors),
            shapes = MaterialShapes,
            typography = materialTypographyFrom(HardPlayType),
            content = content,
        )
    }
}

/**
 * Maps our scale onto Material's slots so that any M3 component we don't wrap
 * still renders in Archivo rather than Roboto.
 */
private fun materialTypographyFrom(t: HardPlayTypography) = androidx.compose.material3.Typography(
    displayLarge = t.display,
    displayMedium = t.display,
    displaySmall = t.displaySmall,
    headlineLarge = t.displaySmall,
    headlineMedium = t.headline,
    headlineSmall = t.headline,
    titleLarge = t.headline,
    titleMedium = t.title,
    titleSmall = t.titleSmall,
    bodyLarge = t.body,
    bodyMedium = t.body,
    bodySmall = t.bodySmall,
    labelLarge = t.label,
    labelMedium = t.label,
    labelSmall = t.labelSmall,
)

/** Shorthand for the common "dim this text" case. */
@Composable
@ReadOnlyComposable
fun TextStyle.dim(): TextStyle = copy(color = HardPlayTheme.colors.typeDim)

@Composable
@ReadOnlyComposable
fun TextStyle.mutedColor(): TextStyle = copy(color = HardPlayTheme.colors.muted)
