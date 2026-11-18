package com.bori.core.keyboard.theme

import android.graphics.Color
import kotlin.random.Random

/**
 * Stage 3.8 "Surprise me": a random theme that is always readable. Colours come from a random
 * hue and colour scheme; every text colour is then pushed toward black or white until it
 * reaches 4.5:1 on what it sits on (3:1 for hints), using ThemeResolver.contrast.
 */
object SurpriseTheme {

    fun make(random: Random = Random.Default): ThemeSpec {
        val r = random
        val dark = r.nextBoolean()
        val hue = r.nextFloat() * 360f
        // Accent hue: same family, next door, opposite, or a third of the way round.
        val accentHue = (hue + listOf(0f, 30f, -30f, 180f, 120f, -120f).random(r) + 360f) % 360f

        val bg = if (dark) hsv(hue, r.range(0.35f, 0.75f), r.range(0.08f, 0.20f))
        else hsv(hue, r.range(0.10f, 0.35f), r.range(0.88f, 0.97f))
        val keyLetter = if (dark) hsv(hue, r.range(0.25f, 0.6f), r.range(0.22f, 0.32f))
        else hsv(hue, r.range(0f, 0.10f), 1f)
        val keyFunction = if (dark) hsv(hue, r.range(0.3f, 0.6f), r.range(0.15f, 0.24f))
        else hsv(hue, r.range(0.12f, 0.30f), r.range(0.80f, 0.90f))
        val keyPressed = shade(keyLetter, if (dark) 0.15f else -0.15f)
        var accent = hsv(accentHue, r.range(0.55f, 0.9f), r.range(0.55f, 0.9f))

        val textBase = if (dark) hsv(hue, 0.08f, 0.95f) else hsv(hue, 0.4f, 0.15f)
        val text = readable(textBase, keyLetter, bg)
        val textFunction = readable(textBase, keyFunction, bg)
        val hint = readableMin(if (dark) hsv(hue, 0.15f, 0.7f) else hsv(hue, 0.2f, 0.45f), 3f, keyLetter)
        // Enter key: white or near-black label, whichever reads better; darken the key if needed.
        var onAccent = best(accent)
        var tries = 0
        while (ThemeResolver.contrast(onAccent, accent) < 4.5f && tries++ < 20) {
            accent = shade(accent, if (onAccent == Color.WHITE) -0.05f else 0.05f)
            onAccent = best(accent)
        }

        // Background: plain, gradient or one of the drawn scenes.
        val bgRoll = r.nextInt(10)
        val gradient = if (bgRoll in 4..7) listOf(bg, if (dark) hsv(accentHue, r.range(0.3f, 0.6f), 0.22f) else hsv(accentHue, r.range(0.15f, 0.35f), 0.93f)) else null
        val stripBacks = gradient ?: listOf(bg)
        var stripText = textBase
        stripText = readable(stripText, *stripBacks.toIntArray())
        val backdrop = if (bgRoll >= 8) BackgroundSpec(type = "scene", scene = Backdrop.SCENES.random(r).first) else null

        val look = KeyLook.entries.random(r)
        val outline = look == KeyLook.OUTLINE || r.nextInt(4) == 0
        val keys = KeyStyle(
            radius = listOf(4f, 8f, 12f, 16f).random(r),
            gapH = listOf(3f, 5f, 7f).random(r),
            gapV = listOf(6f, 8f, 10f).random(r),
            borderWidth = if (outline) listOf(1f, 1.5f, 2f).random(r) else 0f,
            glow = if (outline && dark && r.nextBoolean()) r.range(3f, 8f) else 0f,
            look = look.id,
            shape = KeyShape.entries.random(r).id,
            font = Fonts.ALL.random(r).id,
            hints = true,
        )
        val colors = ThemeColors(
            background = hex(bg),
            keyLetter = hex(keyLetter),
            keyFunction = hex(keyFunction),
            keyPressed = hex(keyPressed),
            accent = hex(accent),
            onAccent = hex(onAccent),
            text = hex(text),
            textFunction = hex(textFunction),
            hint = hex(hint),
            previewBackground = hex(keyLetter),
            previewText = hex(text),
            stripText = hex(stripText),
            backgroundGradient = gradient?.map { hex(it) },
            keyBorder = if (outline) hex(accent) else null,
        )
        return ThemeSpec(
            id = "my_" + System.currentTimeMillis(),
            name = name(r),
            author = "Surprise me",
            category = "Mine",
            light = ThemeVariant(colors = colors, keys = keys, backdrop = backdrop),
        )
    }

    private val FIRST = listOf(
        "Mango", "Coquí", "Midnight", "Coral", "Neon", "Velvet", "Lagoon", "Papaya", "Cosmic", "Guava",
        "Mint", "Ember", "Frost", "Sunny", "Plum", "Jade", "Copper", "Lilac", "Ocean", "Cherry",
    )
    private val SECOND = listOf(
        "Storm", "Breeze", "Dream", "Pop", "Glow", "Wave", "Spark", "Bloom", "Night", "Fizz",
        "Drift", "Party", "Haze", "Rush", "Tide", "Beat",
    )

    private fun name(r: Random) = FIRST.random(r) + " " + SECOND.random(r)

    private fun Random.range(a: Float, b: Float) = a + nextFloat() * (b - a)

    private fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))

    /** Lighter (+) or darker (-) by [dv] in HSV value. */
    private fun shade(c: Int, dv: Float): Int {
        val f = FloatArray(3)
        Color.colorToHSV(c, f)
        f[2] = (f[2] + dv).coerceIn(0f, 1f)
        return Color.HSVToColor(f)
    }

    private fun best(back: Int): Int =
        if (ThemeResolver.contrast(Color.WHITE, back) >= ThemeResolver.contrast(DARK, back)) Color.WHITE else DARK

    /**
     * Moves [text] toward black or white (whichever the first background suits) until it reaches
     * [min] on every one of [backs] (outline keys show letters over the background too).
     */
    private fun readable(text: Int, vararg backs: Int): Int = readableMin(text, 4.5f, *backs)

    private fun readableMin(text: Int, min: Float, vararg backs: Int): Int {
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

    private const val DARK = 0xFF111111.toInt()

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
}
