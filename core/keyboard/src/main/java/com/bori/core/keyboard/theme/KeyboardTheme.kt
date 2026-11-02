package com.bori.core.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import java.util.Calendar

/** When the keyboard is dark. NIGHT uses the phone's clock: dark from 7 pm to 7 am. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, NIGHT }

/**
 * Colours and shape for the keyboard. Stage 3 replaces the hard-coded palettes with the
 * JSON ThemeSpec engine; views only ever read this class, so they won't need to change.
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
) {
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
        }

        /** Light or dark palette; uses the Material You accent on Android 12+. */
        fun forContext(context: Context, night: Boolean = isNight(context)): KeyboardTheme {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val accent = context.getColor(
                    if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600,
                )
                return if (night) dark(accent) else light(accent)
            }
            return if (night) dark() else light()
        }
    }
}
