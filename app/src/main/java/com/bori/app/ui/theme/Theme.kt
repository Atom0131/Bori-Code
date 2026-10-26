package com.bori.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ObsidianDarkPrimary,
    onPrimary = ObsidianDarkOnPrimary,
    primaryContainer = ObsidianDarkPrimaryContainer,
    onPrimaryContainer = ObsidianDarkOnPrimaryContainer,
    secondary = ObsidianDarkSecondary,
    onSecondary = ObsidianDarkOnSecondary,
    secondaryContainer = ObsidianDarkSecondaryContainer,
    onSecondaryContainer = ObsidianDarkOnSecondaryContainer,
    tertiary = ObsidianDarkTertiary,
    onTertiary = ObsidianDarkOnTertiary,
    tertiaryContainer = ObsidianDarkTertiaryContainer,
    onTertiaryContainer = ObsidianDarkOnTertiaryContainer,
    error = ObsidianDarkError,
    onError = ObsidianDarkOnError,
    errorContainer = ObsidianDarkErrorContainer,
    onErrorContainer = ObsidianDarkOnErrorContainer,
    background = ObsidianDarkBackground,
    onBackground = ObsidianDarkOnBackground,
    surface = ObsidianDarkSurface,
    onSurface = ObsidianDarkOnSurface,
    surfaceVariant = ObsidianDarkSurfaceVariant,
    onSurfaceVariant = ObsidianDarkOnSurfaceVariant,
    surfaceDim = ObsidianDarkSurfaceDim,
    surfaceBright = ObsidianDarkSurfaceBright,
    surfaceContainerLowest = ObsidianDarkSurfaceContainerLowest,
    surfaceContainerLow = ObsidianDarkSurfaceContainerLow,
    surfaceContainer = ObsidianDarkSurfaceContainer,
    surfaceContainerHigh = ObsidianDarkSurfaceContainerHigh,
    surfaceContainerHighest = ObsidianDarkSurfaceContainerHighest,
    outline = ObsidianDarkOutline,
    outlineVariant = ObsidianDarkOutlineVariant,
    inverseSurface = ObsidianDarkInverseSurface,
    inverseOnSurface = ObsidianDarkInverseOnSurface,
    inversePrimary = ObsidianDarkInversePrimary,
    surfaceTint = ObsidianDarkSurfaceTint,
)

private val LightColorScheme = lightColorScheme(
    primary = ObsidianLightPrimary,
    onPrimary = ObsidianLightOnPrimary,
    primaryContainer = ObsidianLightPrimaryContainer,
    onPrimaryContainer = ObsidianLightOnPrimaryContainer,
    secondary = ObsidianLightSecondary,
    onSecondary = ObsidianLightOnSecondary,
    secondaryContainer = ObsidianLightSecondaryContainer,
    onSecondaryContainer = ObsidianLightOnSecondaryContainer,
    tertiary = ObsidianLightTertiary,
    onTertiary = ObsidianLightOnTertiary,
    tertiaryContainer = ObsidianLightTertiaryContainer,
    onTertiaryContainer = ObsidianLightOnTertiaryContainer,
    error = ObsidianLightError,
    onError = ObsidianLightOnError,
    errorContainer = ObsidianLightErrorContainer,
    onErrorContainer = ObsidianLightOnErrorContainer,
    background = ObsidianLightBackground,
    onBackground = ObsidianLightOnBackground,
    surface = ObsidianLightSurface,
    onSurface = ObsidianLightOnSurface,
    surfaceVariant = ObsidianLightSurfaceVariant,
    onSurfaceVariant = ObsidianLightOnSurfaceVariant,
    surfaceDim = ObsidianLightSurfaceDim,
    surfaceBright = ObsidianLightSurfaceBright,
    surfaceContainerLowest = ObsidianLightSurfaceContainerLowest,
    surfaceContainerLow = ObsidianLightSurfaceContainerLow,
    surfaceContainer = ObsidianLightSurfaceContainer,
    surfaceContainerHigh = ObsidianLightSurfaceContainerHigh,
    surfaceContainerHighest = ObsidianLightSurfaceContainerHighest,
    outline = ObsidianLightOutline,
    outlineVariant = ObsidianLightOutlineVariant,
    inverseSurface = ObsidianLightInverseSurface,
    inverseOnSurface = ObsidianLightInverseOnSurface,
    inversePrimary = ObsidianLightInversePrimary,
    surfaceTint = ObsidianLightSurfaceTint,
)

/**
 * Applies the "Obsidian Flux" design system. Dynamic color is intentionally
 * disabled so the app renders the Stitch palette exactly; the scheme still
 * follows the system light/dark setting.
 */
@Composable
fun BoriTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalBoriColors provides if (darkTheme) DarkBoriColors else LightBoriColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}

/**
 * Colors the Material scheme has no role for: the chat canvas and bubbles, notice banners, diffs
 * and the terminal. Read with `BoriTheme.colors`.
 */
@Immutable
data class BoriColors(
    val isDark: Boolean,
    val chatBackground: Color,
    val assistantBubble: Color,
    val subtle: Color,
    val border: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val warningBorder: Color,
    val warningIcon: Color,
    val diffAdded: Color,
    val diffRemoved: Color,
    val terminalBackground: Color,
    val terminalBar: Color,
    val terminalKeys: Color,
    val terminalText: Color,
    val terminalAccent: Color,
)

private val LightBoriColors = BoriColors(
    isDark = false,
    chatBackground = Color(0xFFF9F8F3),
    assistantBubble = Color(0xFFF2F2F7),
    subtle = Color(0xFF64748B),
    border = Color(0xFFE2E8F0),
    warningContainer = Color(0xFFFFFBEB),
    onWarningContainer = Color(0xFF78350F),
    warningBorder = Color(0xFFFDE68A),
    warningIcon = Color(0xFFF59E0B),
    diffAdded = Color(0xFF15803D),
    diffRemoved = Color(0xFFB91C1C),
    terminalBackground = Color(0xFF000000),
    terminalBar = Color(0xFF2D2D2D),
    terminalKeys = Color(0xFF1E1E1E),
    terminalText = Color(0xFFD4D4D4),
    terminalAccent = Color(0xFF4FC1FF),
)

private val DarkBoriColors = LightBoriColors.copy(
    isDark = true,
    chatBackground = Color(0xFF121211),
    assistantBubble = Color(0xFF1F1F1E),
    subtle = Color(0xFF94A3B8),
    border = Color(0xFF2C2C2B),
    warningContainer = Color(0xFF3A2A0A),
    onWarningContainer = Color(0xFFFDE68A),
    warningBorder = Color(0xFF6B4F12),
    diffAdded = Color(0xFF4ADE80),
    diffRemoved = Color(0xFFF87171),
)

val LocalBoriColors = staticCompositionLocalOf { LightBoriColors }

object BoriTheme {
    val colors: BoriColors
        @Composable get() = LocalBoriColors.current
}

/** Gives [content] monospace body styles: the editor's file tree and panels read as code. */
@Composable
fun CodeTypography(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = MaterialTheme.typography.copy(bodyMedium = CodeMedium, bodySmall = CodeSmall),
        content = content,
    )
}

/** Whether the app is drawing dark, which follows Settings → Appearance rather than only the system. */
@Composable
fun isAppInDarkTheme(): Boolean = LocalBoriColors.current.isDark
