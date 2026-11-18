package com.bori.core.keyboard.theme

import android.graphics.Color

/**
 * Builds readable theme variants from three colours (main, second, third): text pushed to 4.5:1
 * (3:1 for hints), Enter label white or near-black. Used by holiday looks (Living) and city looks
 * (Places). Same method as SurpriseTheme.
 */
object ThemeMaker {
    fun variant(main: Int, second: Int, third: Int, dark: Boolean, keys: KeyStyle = KeyStyle()): ThemeVariant {
        val hue = hueOf(main)
        val bg = if (dark) hsv(hue, 0.55f, 0.13f) else hsv(hue, 0.14f, 0.96f)
        val bg2 = if (dark) hsv(hueOf(second), 0.45f, 0.20f) else hsv(hueOf(second), 0.12f, 0.92f)
        val keyLetter = if (dark) hsv(hue, 0.40f, 0.25f) else Color.WHITE
        val keyFunction = if (dark) hsv(hue, 0.45f, 0.18f) else hsv(hue, 0.18f, 0.86f)
        var accent = main
        var onAccent = best(accent)
        var tries = 0
        while (ThemeResolver.contrast(onAccent, accent) < 4.5f && tries++ < 20) {
            accent = shade(accent, if (onAccent == Color.WHITE) -0.05f else 0.05f)
            onAccent = best(accent)
        }
        val textBase = if (dark) hsv(hue, 0.06f, 0.96f) else hsv(hue, 0.5f, 0.14f)
        val text = readable(textBase, 4.5f, keyLetter, bg)
        val textFunction = readable(textBase, 4.5f, keyFunction, bg)
        val hint = readable(if (dark) hsv(hue, 0.15f, 0.72f) else hsv(hue, 0.25f, 0.42f), 3f, keyLetter)
        val stripText = readable(textBase, 4.5f, bg, bg2)
        val colors = ThemeColors(
            background = hex(bg),
            keyLetter = hex(keyLetter),
            keyFunction = hex(keyFunction),
            keyPressed = hex(shade(keyLetter, if (dark) 0.15f else -0.15f)),
            accent = hex(accent),
            onAccent = hex(onAccent),
            text = hex(text),
            textFunction = hex(textFunction),
            hint = hex(hint),
            previewBackground = hex(keyLetter),
            previewText = hex(text),
            stripText = hex(stripText),
            backgroundGradient = listOf(hex(bg), hex(bg2)),
            keyBorderGradient = listOf(hex(main), hex(second), hex(third)),
        )
        return ThemeVariant(colors = colors, keys = keys)
    }

    fun hueOf(c: Int): Float = FloatArray(3).also { Color.colorToHSV(c, it) }[0]
    fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
    private fun shade(c: Int, dv: Float): Int {
        val f = FloatArray(3)
        Color.colorToHSV(c, f)
        f[2] = (f[2] + dv).coerceIn(0f, 1f)
        return Color.HSVToColor(f)
    }
    private const val DARK = 0xFF111111.toInt()
    private fun best(back: Int): Int =
        if (ThemeResolver.contrast(Color.WHITE, back) >= ThemeResolver.contrast(DARK, back)) Color.WHITE else DARK

    /** Moves [text] toward black or white until it reaches [min] on all of [backs]. */
    private fun readable(text: Int, min: Float, vararg backs: Int): Int {
        fun ok(c: Int) = backs.all { ThemeResolver.contrast(c, it) >= min }
        if (ok(text)) return text
        val target = best(backs[0])
        for (i in 1..10) {
            val t = i / 10f
            val c = Color.rgb(
                (Color.red(text) + (Color.red(target) - Color.red(text)) * t).toInt(),
                (Color.green(text) + (Color.green(target) - Color.green(text)) * t).toInt(),
                (Color.blue(text) + (Color.blue(target) - Color.blue(text)) * t).toInt(),
            )
            if (ok(c)) return c
        }
        return target
    }
    fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    /**
     * A variant for a drawn scene: see-through keys over [scene] (light: frosted white, dark:
     * tinted glass), so the place shows between and through the keys. ThemeResolver then checks
     * the text against the picture.
     */
    fun sceneVariant(main: Int, second: Int, third: Int, dark: Boolean, scene: String, weather: String?): ThemeVariant {
        val v = variant(main, second, third, dark)
        val hue = hueOf(main)
        val keyLetter = if (dark) (0xB3 shl 24) or (hsv(hue, 0.45f, 0.16f) and 0xFFFFFF) else 0xD9FFFFFF.toInt()
        val keyFunction = if (dark) (0xB3 shl 24) or (hsv(hue, 0.5f, 0.11f) and 0xFFFFFF)
        else (0xC7 shl 24) or (hsv(hue, 0.18f, 0.9f) and 0xFFFFFF)
        return v.copy(
            colors = v.colors.copy(
                keyLetter = hexA(keyLetter),
                keyFunction = hexA(keyFunction),
                backgroundGradient = null,
                keyBorderGradient = null,
            ),
            keys = KeyStyle(radius = 10f, font = "nunito"),
            backdrop = BackgroundSpec(type = "scene", scene = scene, weather = weather),
        )
    }

    private fun hexA(c: Int) = "#%08X".format(c)
}
