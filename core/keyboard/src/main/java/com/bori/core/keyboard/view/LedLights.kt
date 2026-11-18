package com.bori.core.keyboard.view

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import com.bori.core.keyboard.layout.PlacedKey
import kotlin.math.sin

/**
 * Rainbow wave, row rainbow and rainbow breathing are drawn as ONE continuous gradient across the
 * keyboard ([framePaint], Session 43): colours run through each key face into the next, like
 * Razer Chroma, instead of one flat colour per key. Moving the wave only moves the shader's
 * matrix, so a frame allocates nothing.
 *
 * LED lighting for the keys (Stage 3.5): rainbow wave, breathing, reactive (keys light up when
 * pressed and fade), colour cycle and row gradient. KeyboardView asks [colorFor] for every key
 * while drawing; the alpha of the result is the strength (0 = no light on that key). Works on
 * top of any theme. No allocations per frame except the reactive map entry on a key press.
 */
class LedLights {
    enum class Mode(val id: String, val title: String, val animated: Boolean) {
        OFF("off", "Off", false),
        WAVE("wave", "Rainbow wave", true),
        BREATHING("breathing", "Breathing", true),
        REACTIVE("reactive", "Light up on press", false),
        CYCLE("cycle", "Colour cycle", true),
        ROWS("rows", "Row rainbow", false);

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: OFF
        }
    }

    enum class Direction(val id: String, val title: String) {
        LTR("ltr", "Left to right"),
        RTL("rtl", "Right to left"),
        DIAGONAL("diagonal", "Diagonal");

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: LTR
        }
    }

    enum class Target(val id: String, val title: String) {
        KEYS("keys", "Keys"),
        LETTERS("letters", "Letters"),
        OUTLINE("outline", "Outlines");

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: KEYS
        }
    }

    var mode = Mode.OFF
        private set
    var target = Target.KEYS
        private set
    /** Fixed light colour; null = rainbow across the keyboard. */
    private var color: Int? = null
    private var speed = 1f
    private var brightness = 0.6f
    /** False in password fields and with system animations off. */
    private var allowed = true
    private val hsv = floatArrayOf(0f, 0.85f, 1f)
    private val pressedAt = HashMap<PlacedKey, Long>()
    /** How many rainbows fit across the keyboard (1 = exactly one, edge to edge). */
    private var size = 1f
    private var direction = Direction.LTR

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderLength = 0f
    private var shaderVertical = false

    fun configure(
        mode: Mode,
        target: Target,
        color: Int?,
        speed: Float,
        brightness: Float,
        allowed: Boolean,
        size: Float = 1f,
        direction: Direction = Direction.LTR,
    ) {
        this.size = size.coerceIn(0.5f, 3f)
        this.direction = direction
        shader = null
        this.mode = mode
        this.target = target
        this.color = color
        this.speed = speed.coerceIn(0.2f, 3f)
        this.brightness = brightness.coerceIn(0.1f, 1f)
        this.allowed = allowed
        pressedAt.clear()
    }

    val active: Boolean get() = allowed && mode != Mode.OFF

    /** Another frame is needed: an animated mode, or reactive keys still fading. */
    val animating: Boolean get() = active && (mode.animated || pressedAt.isNotEmpty())

    fun onPress(pk: PlacedKey, now: Long) {
        if (active && mode == Mode.REACTIVE) pressedAt[pk] = now
    }

    /** Geometry changed: the old keys are gone. */
    fun clearPresses() = pressedAt.clear()

    /**
     * This frame's gradient paint for the continuous modes, or null when the mode lights keys one
     * by one ([colorFor]). Its alpha is the light's strength; the shader carries the colours.
     */
    fun framePaint(width: Float, height: Float, nowMs: Double): Paint? {
        if (!active || width <= 0f || height <= 0f) return null
        val strength: Float
        when (mode) {
            Mode.WAVE, Mode.ROWS -> strength = 1f
            Mode.BREATHING -> {
                if (color != null) return null
                strength = breath(nowMs)
            }
            else -> return null
        }
        val vertical = mode == Mode.ROWS
        val span = if (vertical) height else width
        val length = span / size
        if (shader == null || length != shaderLength || vertical != shaderVertical) {
            shader = LinearGradient(
                0f, 0f, if (vertical) 0f else length, if (vertical) length else 0f,
                RAINBOW, null, Shader.TileMode.REPEAT,
            )
            shaderLength = length
            shaderVertical = vertical
        }
        matrix.reset()
        if (mode == Mode.WAVE) {
            // One rainbow length every 3 s at speed 1; the colours flow, the keys stay put.
            val offset = phase(nowMs, 3.0) * length
            when (direction) {
                Direction.LTR -> matrix.setTranslate(offset, 0f)
                Direction.RTL -> matrix.setTranslate(-offset, 0f)
                Direction.DIAGONAL -> {
                    matrix.setRotate(35f)
                    matrix.postTranslate(offset, 0f)
                }
            }
        }
        shader!!.setLocalMatrix(matrix)
        paint.shader = shader
        paint.alpha = (brightness * strength * 255f).toInt().coerceIn(0, 255)
        return paint
    }

    /** The light on [pk] (in row [row] of [rows]); x is its centre, [width] the keyboard's. */
    fun colorFor(pk: PlacedKey, row: Int, rows: Int, x: Float, width: Float, nowMs: Double): Int {
        if (!active) return 0
        var strength = 1f
        val c = when (mode) {
            Mode.OFF -> return 0
            Mode.WAVE -> hue(x / width.coerceAtLeast(1f) * 360f + phase(nowMs, 3.0) * 360f)
            Mode.CYCLE -> hue(phase(nowMs, 6.0) * 360f)
            Mode.ROWS -> hue(row * 300f / maxOf(1, rows - 1))
            Mode.BREATHING -> {
                strength = breath(nowMs)
                color ?: hue(x / width.coerceAtLeast(1f) * 360f)
            }
            Mode.REACTIVE -> {
                val at = pressedAt[pk] ?: return 0
                val age = ((nowMs - at) / (700.0 / speed)).toFloat().coerceAtLeast(0f)
                if (age >= 1f) {
                    pressedAt.remove(pk)
                    return 0
                }
                strength = 1f - age
                color ?: hue(x / width.coerceAtLeast(1f) * 360f)
            }
        }
        val a = (brightness * strength * 255f).toInt().coerceIn(0, 255)
        return (c and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * Where we are in a cycle of [periodS] seconds (at speed 1), 0..1. Done in Double: the clock is
     * milliseconds since boot, and as a Float it only changes in 16–32 ms steps after a few days,
     * which made the wave move at ~30–60 steps a second whatever the screen did (Session 44).
     */
    private fun phase(nowMs: Double, periodS: Double): Float =
        ((nowMs / 1000.0 * speed / periodS) % 1.0).toFloat()

    /** Breathing strength 0.2..1, one breath every ~2.5 s at speed 1. */
    private fun breath(nowMs: Double): Float =
        0.2f + 0.8f * (0.5f + 0.5f * sin(phase(nowMs, 2.513) * 6.2832f))

    private fun hue(h: Float): Int {
        hsv[0] = ((h % 360f) + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    companion object {
        /** 13 evenly spaced hues, last = first, so the repeating gradient has no seam. */
        private val RAINBOW = IntArray(13) { Color.HSVToColor(floatArrayOf((it * 30f) % 360f, 0.85f, 1f)) }

        /** [base] with [light] mixed in by the light's alpha; see-through keys get at least that alpha. */
        fun mix(base: Int, light: Int): Int {
            val a = Color.alpha(light) / 255f
            fun ch(b: Int, l: Int) = (b * (1 - a) + l * a).toInt()
            val alpha = maxOf(Color.alpha(base), Color.alpha(light))
            return Color.argb(
                alpha,
                ch(Color.red(base), Color.red(light)),
                ch(Color.green(base), Color.green(light)),
                ch(Color.blue(base), Color.blue(light)),
            )
        }
    }
}
