package com.bori.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.bori.theme.isDark
import com.bori.theme.BoriThemedRoot
import com.bori.theme.components.crystalScaffoldColor
import com.bori.theme.settings.BoriThemeSettings

/**
 * Bori Code's theme: the Bori theme engine (`:core:theme`, shared with the Bori Habit Tracker), plus
 * the few colours Material has no role for ([BoriColors]).
 *
 * Material 3, Expressive and Crystal all come from [settings]; Crystal draws its colour field behind
 * the whole window and screens put glass on it through the engine's tiers (`crystalCardSurface` and
 * friends). Build UI from `MaterialTheme.colorScheme` roles and those tiers, never from raw colours.
 */
@Composable
fun BoriCodeTheme(
    settings: BoriThemeSettings,
    content: @Composable () -> Unit,
) {
    val dark = settings.isDark()
    BoriThemedRoot(settings) {
        val scheme = MaterialTheme.colorScheme
        val colors = boriColors(dark, scheme.onSurfaceVariant, scheme.outlineVariant, scheme.surfaceContainerHigh)
            .copy(chatBackground = crystalScaffoldColor())
        CompositionLocalProvider(LocalBoriColors provides colors, content = content)
    }
}

/**
 * Colors the Material scheme has no role for: diffs, a warning tone and the terminal. Read with
 * `BoriTheme.colors`. Everything else is a `MaterialTheme.colorScheme` role.
 */
@Immutable
data class BoriColors(
    val isDark: Boolean,
    /** The window behind a screen: transparent under Crystal so the field shows. */
    val chatBackground: Color,
    val assistantBubble: Color,
    val subtle: Color,
    val border: Color,
    val diffAdded: Color,
    val diffRemoved: Color,
    val terminalBackground: Color,
    val terminalBar: Color,
    val terminalKeys: Color,
    val terminalText: Color,
    val terminalAccent: Color,
)

private fun boriColors(dark: Boolean, subtle: Color, border: Color, assistant: Color) = BoriColors(
    isDark = dark,
    chatBackground = Color.Transparent,
    assistantBubble = assistant,
    subtle = subtle,
    border = border,
    diffAdded = if (dark) Color(0xFF4ADE80) else Color(0xFF15803D),
    diffRemoved = if (dark) Color(0xFFF87171) else Color(0xFFB91C1C),
    // The terminal canvas stays black in every theme: it is a native view full of ANSI colours.
    terminalBackground = Color(0xFF000000),
    terminalBar = Color(0xFF2D2D2D),
    terminalKeys = Color(0xFF1E1E1E),
    terminalText = Color(0xFFD4D4D4),
    terminalAccent = Color(0xFF4FC1FF),
)

val LocalBoriColors = staticCompositionLocalOf {
    boriColors(false, Color(0xFF64748B), Color(0xFFE2E8F0), Color(0xFFF2F2F7))
}

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
