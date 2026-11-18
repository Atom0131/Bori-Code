package com.bori.core.keyboard.theme

import android.content.Context
import android.graphics.Color
import android.os.Build
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Turns a [ThemeSpec] into the [KeyboardTheme] the views draw with. */
object ThemeResolver {

    fun resolve(
        context: Context,
        spec: ThemeSpec,
        night: Boolean,
        o: ThemeOverrides = ThemeOverrides(),
    ): KeyboardTheme {
        val v = spec.variant(night)
        val k = v.keys
        val opacity = (k.opacity * o.keyOpacity).coerceIn(0.1f, 1f)
        fun faded(color: Int): Int =
            if (opacity >= 1f) color else (color and 0x00FFFFFF) or ((Color.alpha(color) * opacity).toInt() shl 24)
        val base = if (night) KeyboardTheme.dark() else KeyboardTheme.light()
        val c = v.colors
        fun col(value: String?, fallback: Int) = value?.let { parseColor(context, it) } ?: fallback
        fun gradient(values: List<String>?): IntArray? =
            values?.mapNotNull { parseColor(context, it) }?.takeIf { it.size >= 2 }?.toIntArray()
        // Background picture: the user's choice, else the theme's. "none" means a plain colour.
        val bgSpec = o.backdrop ?: v.backdrop
        val bgDir = if (o.backdrop != null) File(context.filesDir, "backgrounds")
        else File(File(context.filesDir, "themes"), ThemeStore.safeId(spec.id))
        val plain = bgSpec?.type == "none"
        val theme = KeyboardTheme(
            background = col(c.background, base.background),
            keyLetter = faded(col(c.keyLetter, base.keyLetter)),
            keyFunction = faded(col(c.keyFunction, base.keyFunction)),
            keyPressed = col(c.keyPressed, base.keyPressed),
            accent = col(c.accent, base.accent),
            onAccent = col(c.onAccent, base.onAccent),
            text = col(c.text, base.text),
            textFunction = col(c.textFunction, base.textFunction),
            hint = col(c.hint, base.hint),
            previewBackground = col(c.previewBackground, base.previewBackground),
            previewText = col(c.previewText, base.previewText),
            stripText = col(c.stripText, base.stripText),
            cornerRadiusDp = v.keys.radius.coerceIn(0f, 40f),
            keyGapHDp = v.keys.gapH.coerceIn(0f, 24f),
            keyGapVDp = v.keys.gapV.coerceIn(0f, 24f),
            backgroundGradient = if (plain) null else gradient(c.backgroundGradient),
            keyBorder = col(c.keyBorder, Color.TRANSPARENT),
            keyBorderGradient = gradient(c.keyBorderGradient),
            borderWidthDp = v.keys.borderWidth.coerceIn(0f, 6f),
            glowDp = v.keys.glow.coerceIn(0f, 16f),
            keyShapes = o.keyShapes,
            look = KeyLook.of(o.look ?: k.look),
            shape = KeyShape.of(o.shape ?: k.shape),
            typeface = Fonts.typeface(context, o.font ?: k.font),
            labelScale = (k.labelScale * o.labelScale).coerceIn(0.6f, 1.6f),
            hints = k.hints && o.hints,
            labelCase = LabelCase.of(o.labelCase ?: k.labelCase),
        )
        val backdrop = Backdrop.resolve(context, bgSpec, bgDir, night, theme.background) ?: return theme
        return readable(theme.copy(backdrop = backdrop, backgroundGradient = null), BackdropRenderer.averageColor(context, backdrop))
    }

    /**
     * Over a picture, text that would be hard to read (below 4.5:1, 3:1 for hints) switches to
     * near-black or white, judged against the picture's average colour (blended under see-through
     * keys). Solid keys keep the theme's own text colours.
     */
    private fun readable(t: KeyboardTheme, avg: Int): KeyboardTheme {
        fun on(fill: Int) = blend(fill, avg)
        fun fix(text: Int, back: Int, min: Float): Int {
            if (contrast(text, back) >= min) return text
            return if (contrast(DARK_TEXT, back) >= contrast(Color.WHITE, back)) DARK_TEXT else Color.WHITE
        }
        val letter = on(t.keyLetter)
        val function = on(t.keyFunction)
        return t.copy(
            stripText = fix(t.stripText, avg, 4.5f),
            text = fix(t.text, letter, 4.5f),
            textFunction = fix(t.textFunction, function, 4.5f),
            hint = fix(t.hint, letter, 3f),
        )
    }

    private const val DARK_TEXT = 0xFF111111.toInt()

    private fun blend(fg: Int, bg: Int): Int {
        val a = Color.alpha(fg) / 255f
        fun ch(f: Int, b: Int) = (f * a + b * (1 - a)).toInt()
        return Color.rgb(ch(Color.red(fg), Color.red(bg)), ch(Color.green(fg), Color.green(bg)), ch(Color.blue(fg), Color.blue(bg)))
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    /** WCAG contrast ratio of two opaque colours. */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return ((max(la, lb) + 0.05) / (min(la, lb) + 0.05)).toFloat()
    }

    /** First usable choice in a `|`-separated colour value, or null. */
    fun parseColor(context: Context, value: String): Int? {
        for (choice in value.split('|')) {
            val s = choice.trim()
            val c = when {
                s.startsWith("@") -> dynamicColor(context, s.substring(1))
                s.startsWith("#") -> runCatching { Color.parseColor(s) }.getOrNull()
                else -> null
            }
            if (c != null) return c
        }
        return null
    }

    private val TOKEN = Regex("(accent[123]|neutral[12])_(0|10|50|[1-9]00|1000)")

    /** Material You colour such as `accent1_600` (android.R.color.system_accent1_600). */
    private fun dynamicColor(context: Context, token: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !TOKEN.matches(token)) return null
        return runCatching {
            val id = android.R.color::class.java.getField("system_$token").getInt(null)
            context.getColor(id)
        }.getOrNull()
    }
}
