package com.bori.core.keyboard.theme

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build

/**
 * Draws one key in the theme's look (flat, raised 3D, sunken, outline, floating) and shape,
 * plus the theme's outline and glow. Shared by KeyboardView and the Settings previews, so the
 * previews match the keyboard. No allocations in [draw]; call [prepare] on theme/size change.
 */
class KeyPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = RectF()
    private val face = RectF()
    private var theme = KeyboardTheme.light()
    private var density = 1f
    private var shadowLayer = false
    private var lastRad = 0f
    private val glowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** [width] is the keyboard's width (for the outline gradient); [density] px per dp. */
    fun prepare(theme: KeyboardTheme, width: Float, density: Float) {
        this.theme = theme
        this.density = density
        border.strokeWidth = theme.borderWidthDp * density
        val grad = theme.keyBorderGradient
        border.shader = if (grad != null && width > 0f) {
            LinearGradient(0f, 0f, width, 0f, grad, null, Shader.TileMode.CLAMP)
        } else null
        border.color = grad?.first() ?: theme.keyBorder
        val glowColor = if (grad != null) grad[grad.size / 2] else theme.keyBorder
        // Shadow layers on shapes are hardware-accelerated from Android 9; older phones skip them.
        val layers = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        if (theme.glowDp > 0f && layers) {
            border.setShadowLayer(theme.glowDp * density, 0f, 0f, glowColor)
        } else {
            border.clearShadowLayer()
        }
        shadowLayer = layers
        if (layers) shadow.setShadowLayer(3.5f * density, 0f, 1.5f * density, 0x55000000) else shadow.clearShadowLayer()
        line.strokeWidth = maxOf(1.5f, theme.borderWidthDp) * density
        line.shader = border.shader
        line.color = when {
            grad != null -> grad.first()
            Color.alpha(theme.keyBorder) > 0 -> theme.keyBorder
            else -> (theme.text and 0x00FFFFFF) or (0x8C shl 24)
        }
    }

    /**
     * Draws a key of [color] in [r]. Returns where its label goes (raised keys move down when
     * pressed); the returned rect is reused, so read it before the next call.
     */
    fun draw(c: Canvas, r: RectF, color: Int, pressed: Boolean, accent: Boolean): RectF {
        face.set(r)
        lastHollow = false
        if (!theme.keyShapes && !pressed && !accent) return face
        val t = theme
        shape.set(r)
        val w = r.width()
        val h = r.height()
        if (t.shape == KeyShape.CIRCLE && w < h * 1.35f) {
            val s = minOf(w, h) / 2
            shape.set(r.centerX() - s, r.centerY() - s, r.centerX() + s, r.centerY() + s)
        }
        val m = minOf(shape.width(), shape.height())
        val rad = when (t.shape) {
            KeyShape.ROUNDED -> t.cornerRadiusDp * density
            KeyShape.SQUARE -> 1.5f * density
            KeyShape.PILL, KeyShape.CIRCLE -> m / 2
            KeyShape.SQUIRCLE -> m * 0.32f
        }.coerceAtMost(m / 2)
        lastRad = rad
        face.set(shape)
        fill.color = color
        when (t.look) {
            KeyLook.FLAT -> c.drawRoundRect(shape, rad, rad, fill)
            KeyLook.RAISED -> {
                val d = minOf(2.5f * density, shape.height() * 0.12f)
                if (pressed) {
                    face.top += d
                } else {
                    edge.color = shade(color)
                    c.drawRoundRect(shape, rad, rad, edge)
                    face.bottom -= d
                }
                c.drawRoundRect(face, rad, rad, fill)
            }
            KeyLook.SUNKEN -> {
                val d = minOf(2f * density, shape.height() * 0.1f)
                edge.color = shade(color)
                c.drawRoundRect(shape, rad, rad, edge)
                face.top += d
                c.drawRoundRect(face, rad, rad, fill)
            }
            KeyLook.OUTLINE -> {
                if (pressed || accent) {
                    c.drawRoundRect(shape, rad, rad, fill)
                } else {
                    val half = line.strokeWidth / 2
                    face.inset(half, half)
                    c.drawRoundRect(face, rad, rad, line)
                    face.set(shape)
                    lastHollow = true
                }
                return face
            }
            KeyLook.FLOATING -> {
                // See-through keys would show the shadow through them, so only solid keys float.
                if (!pressed && Color.alpha(color) == 255) {
                    if (shadowLayer) {
                        shadow.color = color
                        c.drawRoundRect(shape, rad, rad, shadow)
                    } else {
                        edge.color = 0x33000000
                        shape.offset(0f, 1.5f * density)
                        c.drawRoundRect(shape, rad, rad, edge)
                        shape.offset(0f, -1.5f * density)
                    }
                }
                c.drawRoundRect(shape, rad, rad, fill)
            }
        }
        if (t.hasOutline) c.drawRoundRect(face, rad, rad, border)
        return face
    }

    /** The last key drawn was only an outline (outline look): lights follow its line. */
    private var lastHollow = false

    /**
     * LED lighting with a gradient (LedLights.framePaint): paints [p] over the face of the key
     * [draw] just drew, so one rainbow runs across all the keys. Outline-look keys get it on
     * their line instead of a fill.
     */
    fun overlay(c: Canvas, p: Paint) {
        if (lastHollow) {
            outline(c, p, line.strokeWidth.coerceAtLeast(1.5f * density))
            return
        }
        p.style = Paint.Style.FILL
        c.drawRoundRect(face, lastRad, lastRad, p)
    }

    /** LED lighting: an outline painted with [p] (a gradient) around the key [draw] just drew. */
    fun outline(c: Canvas, p: Paint, widthPx: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = widthPx
        val half = widthPx / 2
        shape.inset(half, half)
        c.drawRoundRect(shape, lastRad, lastRad, p)
        shape.inset(-half, -half)
        p.style = Paint.Style.FILL
    }

    /** LED lighting: an outline of [color] around the key [draw] just drew. */
    fun outline(c: Canvas, color: Int, widthPx: Float) {
        glowLine.color = color
        glowLine.strokeWidth = widthPx
        val half = widthPx / 2
        shape.inset(half, half)
        c.drawRoundRect(shape, lastRad, lastRad, glowLine)
        shape.inset(-half, -half)
    }

    /** The key's side: darker, or lighter for very dark keys so the edge still shows. */
    private fun shade(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val dark = (r * 299 + g * 587 + b * 114) / 1000 < 40
        fun ch(v: Int) = if (dark) v + ((255 - v) * 0.22f).toInt() else (v * 0.68f).toInt()
        return Color.argb(Color.alpha(color), ch(r), ch(g), ch(b))
    }
}
