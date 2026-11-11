package com.bori.theme.util

import androidx.compose.ui.graphics.Color

// Hand-written for the v2 export: the colour palette the theme resolves accents and room colours
// against. In the habit tracker it is `habitColorPalette` in `ui/util/Extensions.kt`, where habits,
// notebooks and events share it; the values and their order are copied exactly, because the theme
// settings store an index into this list.

/**
 * Accent / room presets. 0–4 vivid, 5–9 pastel (L* 85–90, deliberately C* 13–15 so a pastel room
 * stays quieter than a vivid one under Crystal's room-chroma cap). **Appended, never inserted**:
 * stored settings hold a bare index.
 */
val boriColorPalette = listOf(
    Color(0xFF6750A4), // 0 Deep Purple
    Color(0xFF006C4C), // 1 Emerald Green
    Color(0xFF0061A4), // 2 Azure Blue
    Color(0xFFB3261E), // 3 Ruby Red
    Color(0xFFBC6000), // 4 Amber/Orange
    Color(0xFFD8D2EB), // 5 Lilac        L* 85.3  C* 13.4  h 300
    Color(0xFFBFE0D2), // 6 Seafoam      L* 86.5  C* 13.9  h 167
    Color(0xFFC3D6EC), // 7 Powder Blue  L* 84.9  C* 13.1  h 262
    Color(0xFFEFCBC8), // 8 Blush        L* 84.6  C* 13.7  h  27
    Color(0xFFF0DFC6), // 9 Apricot      L* 89.6  C* 14.5  h  83
)

/** How many entries of [boriColorPalette] are the vivid presets; the rest are pastels. */
const val BORI_COLOR_PRESET_COUNT = 5

/** The habit tracker's name for the palette, so exported code reads unchanged. */
internal val habitColorPalette get() = boriColorPalette
internal const val HABIT_COLOR_PRESET_COUNT = BORI_COLOR_PRESET_COUNT

/** A custom ARGB if there is one, else the palette entry (falling back to the first). */
fun resolveDisplayColor(colorIndex: Int, colorArgb: Int?): Color =
    if (colorArgb != null) Color(colorArgb)
    else boriColorPalette.getOrElse(colorIndex) { boriColorPalette.first() }
