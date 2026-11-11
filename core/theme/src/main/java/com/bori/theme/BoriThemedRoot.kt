package com.bori.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bori.theme.components.CrystalRoot
import com.bori.theme.components.crystalScaffoldColor
import com.bori.theme.components.crystalScaffoldContentColor
import com.bori.theme.settings.BoriThemeSettings
import com.bori.theme.settings.ThemeMode

// Hand-written for the v2 export. The habit tracker's `RhythmThemedRoot` reads the same values from
// its DataStore-backed ThemePreferences; this takes them as one plain value instead.

/** Whether [settings] resolve to dark right now. */
@Composable
fun BoriThemeSettings.isDark(): Boolean = when (themeMode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * The whole theme for one window: [BoriTheme], then [CrystalRoot] (the field and the blur source that
 * glass panels read), then a [Surface] that is transparent under Crystal so the field shows.
 *
 * Every window needs its own: a dialog Activity or a widget-config screen has its own composition,
 * and the field installed by the main window cannot show through there. (Sheets and dialogs inside a
 * window use `CrystalWindowContent`, see `BoriSheet`.)
 *
 * @param seedColorOverride bypasses the accent setting (the habit tracker's widget screens use it).
 */
@Composable
fun BoriThemedRoot(
    settings: BoriThemeSettings,
    seedColorOverride: Color? = null,
    content: @Composable () -> Unit,
) {
    val dark = settings.isDark()
    BoriTheme(
        darkTheme = dark,
        isAmoled = settings.amoled,
        seedColor = seedColorOverride ?: accentSeed(
            settings.themeStyle, settings.crystalBackground, settings.crystalMesh,
            settings.accentColorIndex, settings.accentColorArgb, settings.accentIsDefault,
        ),
        themeStyle = settings.themeStyle,
        crystalStyle = settings.crystalStyle,
        crystalIntensity = settings.crystalIntensity,
        crystalBackground = settings.crystalBackground,
        crystalMesh = settings.crystalMesh,
        crystalMeshCustomSeed = settings.crystalMeshCustomArgb?.let { Color(it) },
        crystalMeshCustomChroma = settings.crystalMeshCustomChroma,
        crystalMeshCustomColours = settings.crystalMeshCustomColors.map { Color(it) },
        crystalRoom = crystalRoomColor(settings.crystalRoomIndex, settings.crystalRoomArgb, dark, settings.amoled),
        darkContrastLevel = settings.darkContrastLevel,
        boriken = settings.borikenMode,
        borikenField = settings.borikenField,
        borikenAccent = settings.borikenAccent,
    ) {
        CrystalRoot {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = crystalScaffoldColor(),
                contentColor = crystalScaffoldContentColor(),
                content = content,
            )
        }
    }
}
