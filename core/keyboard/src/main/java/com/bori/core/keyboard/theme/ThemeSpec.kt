package com.bori.core.keyboard.theme

import kotlinx.serialization.Serializable

/**
 * A theme as saved and shared (one JSON file per theme in assets/themes for built-ins,
 * filesDir/themes/<id>/theme.json for the user's own). Stage 3 grows this phase by phase
 * (see .bori/theme-plan.md). Every field is optional with a default, so older files keep
 * loading as fields are added. Never write a slash-star path in a KDoc: Kotlin nests comments.
 *
 * [ThemeResolver] turns a spec into a [KeyboardTheme]; views never read the spec.
 */
@Serializable
data class ThemeSpec(
    val version: Int = 1,
    val id: String,
    val name: String,
    val author: String = "Bori",
    /** Gallery section, e.g. "Simple", "Glow & LED", "Scenes", "Puerto Rico". */
    val category: String = "Simple",
    /** Used in light mode, and in dark mode when there is no [dark] variant. */
    val light: ThemeVariant = ThemeVariant(),
    /** Used in dark mode; null means the theme looks the same in both modes. */
    val dark: ThemeVariant? = null,
) {
    fun variant(night: Boolean): ThemeVariant = if (night) dark ?: light else light
}

@Serializable
data class ThemeVariant(
    val colors: ThemeColors = ThemeColors(),
    val keys: KeyStyle = KeyStyle(),
    /** Picture behind the keyboard (gradient at any angle, scene or photo); beats backgroundGradient. */
    val backdrop: BackgroundSpec? = null,
)

/**
 * Colours as strings. Each one is a list of choices separated by `|`, tried in order:
 * `#RRGGBB` / `#AARRGGBB`, or a Material You token such as `@accent1_600` (Android 12+).
 * Example: `"@accent1_600|#1A73E8"` follows the wallpaper and falls back to blue.
 * A missing colour comes from the classic light or dark palette.
 */
@Serializable
data class ThemeColors(
    val background: String? = null,
    val keyLetter: String? = null,
    val keyFunction: String? = null,
    val keyPressed: String? = null,
    val accent: String? = null,
    val onAccent: String? = null,
    val text: String? = null,
    val textFunction: String? = null,
    val hint: String? = null,
    val previewBackground: String? = null,
    val previewText: String? = null,
    val stripText: String? = null,
    /** Top-to-bottom background gradient (2+ colours); replaces [background] when set. */
    val backgroundGradient: List<String>? = null,
    /** Key outline colour; drawn only when [KeyStyle.borderWidth] > 0. */
    val keyBorder: String? = null,
    /** Left-to-right outline gradient across the keyboard (e.g. a rainbow); beats [keyBorder]. */
    val keyBorderGradient: List<String>? = null,
)

/** Key shape, spacing (dp), look and lettering. Names for look/shape/font: see KeyLook, KeyShape, Fonts. */
@Serializable
data class KeyStyle(
    val radius: Float = 8f,
    val gapH: Float = 5f,
    val gapV: Float = 8f,
    /** Key outline width; 0 = no outline. */
    val borderWidth: Float = 0f,
    /** Neon glow around the outline (Android 9+); 0 = none. */
    val glow: Float = 0f,
    /** flat, raised, sunken, outline or floating. */
    val look: String = "flat",
    /** rounded (uses [radius]), square, pill, circle or squircle. */
    val shape: String = "rounded",
    /** Key fill opacity, 0.1 to 1. */
    val opacity: Float = 1f,
    /** Font id from Fonts (assets/fonts), or "system". */
    val font: String = "system",
    /** Letter size, 0.7 to 1.4 times normal. */
    val labelScale: Float = 1f,
    /** Small number/symbol hints in the key corners. */
    val hints: Boolean = true,
    /** auto (follows Shift), upper or lower. */
    val labelCase: String = "auto",
)
