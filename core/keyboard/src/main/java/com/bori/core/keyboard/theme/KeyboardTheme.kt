package com.bori.core.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import java.util.Calendar

/** When the keyboard is dark. NIGHT uses the phone's clock: dark from 7 pm to 7 am. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, NIGHT, SUN }

/**
 * Colours and shape for the keyboard, resolved from a [ThemeSpec] by [ThemeResolver]. Views
 * only ever read this class. [light] and [dark] are the classic palettes, used as defaults
 * for any colour a theme leaves out.
 */
data class KeyboardTheme(
    val background: Int,
    val keyLetter: Int,
    val keyFunction: Int,
    val keyPressed: Int,
    val accent: Int,
    val onAccent: Int,
    val text: Int,
    val textFunction: Int,
    val hint: Int,
    val previewBackground: Int,
    val previewText: Int,
    val stripText: Int,
    val cornerRadiusDp: Float = 8f,
    val keyGapHDp: Float = 5f,
    val keyGapVDp: Float = 8f,
    /** Top-to-bottom gradient behind the whole keyboard; null = solid [background]. */
    val backgroundGradient: IntArray? = null,
    /** Key outline colour (used when [keyBorderGradient] is null). */
    val keyBorder: Int = Color.TRANSPARENT,
    /** Left-to-right outline gradient across the keyboard. */
    val keyBorderGradient: IntArray? = null,
    val borderWidthDp: Float = 0f,
    val glowDp: Float = 0f,
    /** The user's "Key borders" switch: false draws only labels (pressed/accent keys still fill). */
    val keyShapes: Boolean = true,
    val look: KeyLook = KeyLook.FLAT,
    val shape: KeyShape = KeyShape.ROUNDED,
    /** Key-label font; null = the phone's font. */
    val typeface: Typeface? = null,
    val labelScale: Float = 1f,
    val hints: Boolean = true,
    val labelCase: LabelCase = LabelCase.AUTO,
    /** Picture behind everything (InputRootView draws it); null = colour or gradient. */
    val backdrop: Backdrop? = null,
) {
    /**
     * What a view should fill its own background with: the solid colour, or transparent when
     * InputRootView paints a gradient behind everything.
     */
    val solidBackground: Int
        get() = if (backgroundGradient == null && backdrop == null) background else Color.TRANSPARENT

    val hasOutline: Boolean
        get() = borderWidthDp > 0f && (keyBorderGradient != null || Color.alpha(keyBorder) > 0)


    companion object {
        fun light(accent: Int = Color.parseColor("#1A73E8")) = KeyboardTheme(
            background = Color.parseColor("#E8EAED"),
            keyLetter = Color.WHITE,
            keyFunction = Color.parseColor("#C9CDD3"),
            keyPressed = Color.parseColor("#B0B5BC"),
            accent = accent,
            onAccent = Color.WHITE,
            text = Color.parseColor("#202124"),
            textFunction = Color.parseColor("#202124"),
            hint = Color.parseColor("#80868B"),
            previewBackground = Color.WHITE,
            previewText = Color.parseColor("#202124"),
            stripText = Color.parseColor("#3C4043"),
        )

        fun dark(accent: Int = Color.parseColor("#8AB4F8")) = KeyboardTheme(
            background = Color.parseColor("#202124"),
            keyLetter = Color.parseColor("#3C4043"),
            keyFunction = Color.parseColor("#2D2F31"),
            keyPressed = Color.parseColor("#5F6368"),
            accent = accent,
            onAccent = Color.parseColor("#202124"),
            text = Color.parseColor("#E8EAED"),
            textFunction = Color.parseColor("#E8EAED"),
            hint = Color.parseColor("#9AA0A6"),
            previewBackground = Color.parseColor("#5F6368"),
            previewText = Color.WHITE,
            stripText = Color.parseColor("#E8EAED"),
        )

        fun isNight(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        /** Should the keyboard be dark right now under [mode]? */
        fun wantsDark(context: Context, mode: ThemeMode): Boolean = when (mode) {
            ThemeMode.SYSTEM -> isNight(context)
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.NIGHT -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 19 || it < 7 }
            ThemeMode.SUN -> SunTimes.isDark()
        }

        /** The user's selected theme, light or dark variant. */
        fun forContext(
            context: Context,
            night: Boolean = isNight(context),
            themeId: String = ThemeStore.CLASSIC,
            overrides: ThemeOverrides = ThemeOverrides(),
        ): KeyboardTheme = ThemeResolver.resolve(context, ThemeStore(context).get(themeId), night, overrides)
    }
}
