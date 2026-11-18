package com.bori.core.keyboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Key-press effects over the keys (Stage 3.5): ripple, pop, sparkle, bubbles, hearts, confetti,
 * fireworks, emoji burst, light wave, finger glow. A fixed pool of particles (no allocations
 * while animating); it only redraws while particles are alive, so it costs nothing when idle.
 * Off in password fields and with system animations off (the service decides, see [allowed]).
 */
class EffectsView(context: Context) : View(context) {

    enum class Effect(val id: String, val title: String) {
        NONE("none", "None"),
        RIPPLE("ripple", "Ripple"),
        POP("pop", "Pop"),
        SPARKLE("sparkle", "Sparkle"),
        BUBBLES("bubbles", "Bubbles"),
        HEARTS("hearts", "Hearts"),
        CONFETTI("confetti", "Confetti"),
        FIREWORKS("fireworks", "Fireworks"),
        EMOJI("emoji", "Emoji burst"),
        WAVE("wave", "Light wave"),
        GLOW("glow", "Finger glow");

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: NONE
        }
    }

    var effect = Effect.NONE
    /** Fixed colour; null = rainbow. Theme colour is passed in as a fixed colour by the service. */
    var color: Int? = null
    var allowed = true

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val heart = Path().apply {
        moveTo(0f, 0.45f)
        cubicTo(-0.55f, 0.1f, -0.5f, -0.45f, 0f, -0.15f)
        cubicTo(0.5f, -0.45f, 0.55f, 0.1f, 0f, 0.45f)
        close()
    }
    private val hsv = floatArrayOf(0f, 0.8f, 1f)
    private val rnd = java.util.Random()
    private var lastFrame = 0L

    // Particle pool (structure of arrays).
    private val kind = IntArray(MAX)
    private val px = FloatArray(MAX)
    private val py = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val grav = FloatArray(MAX)
    private val age = FloatArray(MAX)
    private val dur = FloatArray(MAX)
    private val size = FloatArray(MAX)
    private val grow = FloatArray(MAX)
    private val col = IntArray(MAX)
    private val rot = FloatArray(MAX)
    private val vr = FloatArray(MAX)
    private var alive = 0

    init {
        isClickable = false
        isFocusable = false
    }

    /** Same rule as KeyOverlayView: never ask for height unless told exactly. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    /** A key at [key] was pressed at ([x], [y]) (this view's coordinates = the keyboard's). */
    fun burst(x: Float, y: Float, key: RectF) {
        if (!allowed || effect == Effect.NONE) return
        val kw = key.width().coerceAtLeast(8f * density)
        val d = density
        when (effect) {
            Effect.NONE -> return
            Effect.RIPPLE -> {
                spawn(RING, x, y, 0f, 0f, 0f, 420f, kw * 0.25f, kw * 1.1f, pick())
                spawn(RING, x, y, 0f, 0f, 0f, 560f, kw * 0.1f, kw * 0.8f, pick())
            }
            Effect.WAVE -> spawn(RING, x, y, 0f, 0f, 0f, 750f, kw * 0.4f, width * 0.5f, pick())
            Effect.POP -> spawn(DISC, x, y, 0f, 0f, 0f, 260f, kw * 0.35f, kw * 0.35f, pick())
            Effect.GLOW -> spawn(GLOW, x, y, 0f, 0f, 0f, 480f, kw * 0.9f, kw * 0.2f, pick())
            Effect.SPARKLE -> repeat(8) {
                val a = rnd.nextFloat() * 6.283f
                val s = (0.08f + rnd.nextFloat() * 0.18f) * d
                spawn(STAR, x, y, cos(a) * s, sin(a) * s, 0f, 520f, (4f + rnd.nextFloat() * 4f) * d, 0f, pick())
            }
            Effect.BUBBLES -> repeat(5) {
                spawn(BUBBLE, x + (rnd.nextFloat() - 0.5f) * kw * 0.6f, y, (rnd.nextFloat() - 0.5f) * 0.04f * d,
                    -(0.06f + rnd.nextFloat() * 0.07f) * d, 0f, 900f, (4f + rnd.nextFloat() * 5f) * d, 0f, pick())
            }
            Effect.HEARTS -> repeat(4) {
                spawn(HEART, x + (rnd.nextFloat() - 0.5f) * kw * 0.6f, y, (rnd.nextFloat() - 0.5f) * 0.05f * d,
                    -(0.07f + rnd.nextFloat() * 0.08f) * d, 0f, 950f, (9f + rnd.nextFloat() * 6f) * d, 0f, pick())
            }
            Effect.CONFETTI -> repeat(14) {
                spawn(CONFETTI, x, y, (rnd.nextFloat() - 0.5f) * 0.5f * d, -(0.15f + rnd.nextFloat() * 0.25f) * d,
                    0.0012f * d, 900f, (3f + rnd.nextFloat() * 4f) * d, 0f, pick())
            }
            Effect.FIREWORKS -> repeat(18) {
                val a = it * 6.283f / 18f
                val s = (0.16f + rnd.nextFloat() * 0.08f) * d
                spawn(DOT, x, y, cos(a) * s, sin(a) * s, 0.0005f * d, 780f, (2f + rnd.nextFloat() * 1.6f) * d, 0f, pick())
            }
            Effect.EMOJI -> repeat(4) {
                spawn(EMOJI, x, y, (rnd.nextFloat() - 0.5f) * 0.2f * d, -(0.1f + rnd.nextFloat() * 0.1f) * d,
                    0.0003f * d, 900f, (18f + rnd.nextFloat() * 8f) * d, 0f, rnd.nextInt(EMOJIS.size))
            }
        }
        if (lastFrame == 0L) lastFrame = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    fun clear() {
        alive = 0
        invalidate()
    }

    private fun pick(): Int = color ?: run {
        hsv[0] = rnd.nextFloat() * 360f
        Color.HSVToColor(hsv)
    }

    private fun spawn(k: Int, x: Float, y: Float, svx: Float, svy: Float, g: Float, life: Float, s: Float, gr: Float, c: Int) {
        // Pool full: the oldest particle makes room.
        val i = if (alive < MAX) alive++ else 0
        kind[i] = k; px[i] = x; py[i] = y; vx[i] = svx; vy[i] = svy; grav[i] = g
        age[i] = 0f; dur[i] = life; size[i] = s; grow[i] = gr; col[i] = c
        rot[i] = rnd.nextFloat() * 360f; vr[i] = (rnd.nextFloat() - 0.5f) * 0.6f
    }

    override fun onDraw(canvas: Canvas) {
        if (alive == 0) {
            lastFrame = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(0L, 50L).toFloat()
        lastFrame = now
        var i = 0
        while (i < alive) {
            age[i] += dt
            if (age[i] >= dur[i]) {
                // Remove by moving the last particle here.
                val last = --alive
                if (i != last) copy(last, i)
                continue
            }
            vy[i] += grav[i] * dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            rot[i] += vr[i] * dt
            draw(canvas, i, age[i] / dur[i])
            i++
        }
        if (alive > 0) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun draw(c: Canvas, i: Int, t: Float) {
        val fade = 1f - t
        val a = (fade * 255f).toInt().coerceIn(0, 255)
        val base = col[i]
        paint.style = Paint.Style.FILL
        paint.color = (base and 0x00FFFFFF) or (a shl 24)
        val s = size[i]
        when (kind[i]) {
            RING -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f * density * fade + 0.5f
                c.drawCircle(px[i], py[i], s + (grow[i] - s) * easeOut(t), paint)
            }
            DISC -> {
                paint.alpha = (a * 0.6f).toInt()
                c.drawCircle(px[i], py[i], s * (1f + 0.6f * easeOut(t)), paint)
            }
            GLOW -> {
                for (k in 3 downTo 1) {
                    paint.alpha = (a * 0.18f).toInt()
                    c.drawCircle(px[i], py[i], s * k / 3f, paint)
                }
            }
            STAR -> {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 1.6f * density
                val r = s * (0.6f + 0.4f * fade)
                c.save()
                c.rotate(rot[i], px[i], py[i])
                c.drawLine(px[i] - r, py[i], px[i] + r, py[i], paint)
                c.drawLine(px[i], py[i] - r, px[i], py[i] + r, paint)
                c.restore()
            }
            BUBBLE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.4f * density
                c.drawCircle(px[i], py[i], s, paint)
                paint.style = Paint.Style.FILL
                paint.alpha = (a * 0.5f).toInt()
                c.drawCircle(px[i] - s * 0.35f, py[i] - s * 0.35f, s * 0.22f, paint)
            }
            HEART -> {
                c.save()
                c.translate(px[i] + sin(age[i] / 140f) * 3f * density, py[i])
                c.scale(s, s)
                c.drawPath(heart, paint)
                c.restore()
            }
            CONFETTI -> {
                c.save()
                c.rotate(rot[i], px[i], py[i])
                c.drawRect(px[i] - s / 2, py[i] - s / 4, px[i] + s / 2, py[i] + s / 4, paint)
                c.restore()
            }
            DOT -> c.drawCircle(px[i], py[i], s, paint)
            EMOJI -> {
                textPaint.textSize = s
                textPaint.alpha = a
                c.drawText(EMOJIS[col[i] % EMOJIS.size], px[i], py[i] + s / 3f, textPaint)
            }
        }
    }

    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)

    private fun copy(from: Int, to: Int) {
        kind[to] = kind[from]; px[to] = px[from]; py[to] = py[from]; vx[to] = vx[from]; vy[to] = vy[from]
        grav[to] = grav[from]; age[to] = age[from]; dur[to] = dur[from]; size[to] = size[from]
        grow[to] = grow[from]; col[to] = col[from]; rot[to] = rot[from]; vr[to] = vr[from]
    }

    private companion object {
        const val MAX = 192
        const val RING = 0
        const val DISC = 1
        const val GLOW = 2
        const val STAR = 3
        const val BUBBLE = 4
        const val HEART = 5
        const val CONFETTI = 6
        const val DOT = 7
        const val EMOJI = 8
        val EMOJIS = arrayOf("😀", "🎉", "❤️", "✨", "🔥", "😎", "🥳", "🌺")
    }
}
