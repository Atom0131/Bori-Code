package com.bori.core.keyboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ambient background effects (Stage 3.9): rain, snow, leaves, sun rays, clouds, a thunderstorm,
 * a starfield, aurora, fireflies, confetti, floating hearts and fireworks. Drawn behind the
 * suggestion bar and the keys (InputRootView puts it under them and tells them to stop painting
 * their own background), so it shows in the gaps and through see-through keys.
 *
 * Cheap on purpose: a fixed particle pool (no allocations per frame), frames on the display's
 * vsync (FrameTicker, full refresh rate), and nothing at all while [running] is false (keyboard hidden), the effect is NONE,
 * or [allowed] is false (password fields, phone animations off).
 */
class AmbientView(context: Context) : View(context) {

    enum class Effect(val id: String, val title: String) {
        NONE("none", "None"),
        RAIN("rain", "Rain"),
        SNOW("snow", "Snow"),
        LEAVES("leaves", "Falling leaves"),
        PETALS("petals", "Spring petals"),
        SUNRAYS("sunrays", "Sun rays"),
        CLOUDS("clouds", "Clouds"),
        STORM("storm", "Thunderstorm"),
        STARS("stars", "Starry night"),
        AURORA("aurora", "Aurora"),
        FIREFLIES("fireflies", "Fireflies"),
        CONFETTI("confetti", "Confetti"),
        HEARTS("hearts", "Floating hearts"),
        FIREWORKS("fireworks", "Fireworks"),
        /** A setting, not a drawing: the service swaps in this season's effect (theme 3.10). */
        SEASON("season", "Follows the season"),
        /** A setting too: the service swaps in the current weather's effect (theme 3.10 part 2). */
        WEATHER("weather", "Follows the weather");

        /** Chosen in Settings but never drawn as such. */
        val isAuto get() = this == SEASON || this == WEATHER

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: NONE
        }
    }

    private var effect = Effect.NONE
    /** 0.2–1: how many particles. */
    private var amount = 0.6f
    /** 0.5–2: how fast things move. */
    private var speed = 1f
    /** Light backgrounds: white snow/rain/stars would vanish, so they use dark ink instead. */
    private var lightBackground = false
    private var allowed = true

    /** Keyboard on screen (the service sets it on window shown / hidden). */
    var running = false
        set(v) {
            field = v
            kick()
        }

    /** Something to draw: InputRootView stops the keys and bar painting over us. */
    val active: Boolean get() = effect != Effect.NONE && !effect.isAuto && allowed

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val heart = Path().apply {
        moveTo(0f, 0.45f)
        cubicTo(-0.55f, 0.1f, -0.5f, -0.45f, 0f, -0.15f)
        cubicTo(0.5f, -0.45f, 0.55f, 0.1f, 0f, 0.45f)
        close()
    }
    private val leaf = Path().apply {
        moveTo(0f, -0.5f)
        quadTo(0.42f, 0f, 0f, 0.5f)
        quadTo(-0.42f, 0f, 0f, -0.5f)
        close()
    }
    private val rnd = java.util.Random()
    private val ticker = FrameTicker(this, "ambient")
    private val hsv = floatArrayOf(0f, 0.75f, 1f)
    /** Vsync time of the last frame drawn (ms), 0 = none yet. */
    private var lastFrame = 0.0
    /** Seconds since the effect started (for waves, twinkles, pulses). */
    private var clock = 0f
    private var flash = 0f
    private var nextFlash = 3f
    private var nextBurst = 0f
    private var boltSeed = 0L
    private var auroraShader: LinearGradient? = null
    private var auroraFor = 0

    // Particle pool (structure of arrays).
    private val px = FloatArray(MAX)
    private val py = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val sz = FloatArray(MAX)
    private val ph = FloatArray(MAX)
    private val rot = FloatArray(MAX)
    private val col = IntArray(MAX)
    private val life = FloatArray(MAX)
    private var count = 0

    init {
        isClickable = false
        isFocusable = false
    }

    /** Never asks for height of its own: it fills whatever InputRootView gives it. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    /** Colours for confetti and fireworks (a holiday's); null = rainbow. */
    private var palette: IntArray? = null

    fun configure(
        effect: Effect,
        amount: Float,
        speed: Float,
        lightBackground: Boolean,
        allowed: Boolean,
        palette: IntArray? = null,
    ) {
        val effect = if (effect.isAuto) Effect.NONE else effect
        val changed = effect != this.effect || lightBackground != this.lightBackground ||
            amount != this.amount || !palette.contentEquals(this.palette)
        this.palette = palette
        this.effect = effect
        this.amount = amount.coerceIn(0.2f, 1f)
        this.speed = speed.coerceIn(0.5f, 2f)
        this.lightBackground = lightBackground
        this.allowed = allowed
        if (changed) {
            count = 0
            clock = 0f
            flash = 0f
            auroraShader = null
        }
        kick()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        count = 0
        auroraShader = null
        kick()
    }

    private fun kick() {
        if (running && active) lastFrame = 0.0 else ticker.cancel()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!running || !active || width == 0 || height == 0) {
            lastFrame = 0.0
            ticker.cancel()
            return
        }
        // Vsync time: every frame steps by exactly one refresh period (smooth at 120 Hz).
        val now = ticker.now()
        val dt = if (lastFrame == 0.0) 0f else ((now - lastFrame).coerceIn(0.0, 100.0) / 1000.0).toFloat() * speed
        lastFrame = now
        clock += dt
        if (count == 0) seed()
        val w = width.toFloat()
        val h = height.toFloat()
        when (effect) {
            Effect.NONE -> return
            Effect.RAIN -> rain(canvas, w, h, dt, 1f)
            Effect.STORM -> storm(canvas, w, h, dt)
            Effect.SNOW -> snow(canvas, w, h, dt)
            Effect.LEAVES, Effect.PETALS -> leaves(canvas, w, h, dt)
            Effect.SEASON, Effect.WEATHER -> return
            Effect.SUNRAYS -> sunRays(canvas, w, h)
            Effect.CLOUDS -> clouds(canvas, w, h, dt)
            Effect.STARS -> stars(canvas, w, h, dt)
            Effect.AURORA -> aurora(canvas, w, h)
            Effect.FIREFLIES -> fireflies(canvas, w, h, dt)
            Effect.CONFETTI -> confetti(canvas, w, h, dt)
            Effect.HEARTS -> hearts(canvas, w, h, dt)
            Effect.FIREWORKS -> fireworks(canvas, w, h, dt)
        }
        // Next frame on the display's vsync, at the screen's full rate.
        ticker.request()
    }

    // ---------------------------------------------------------------- set-up

    private fun n(base: Int) = (base * amount).toInt().coerceIn(1, MAX)

    private fun seed() {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = density
        count = when (effect) {
            Effect.RAIN, Effect.STORM -> n(110)
            Effect.SNOW -> n(90)
            Effect.LEAVES -> n(26)
            Effect.PETALS -> n(30)
            Effect.CLOUDS -> 5
            Effect.STARS -> n(120)
            Effect.FIREFLIES -> n(34)
            Effect.CONFETTI -> n(70)
            Effect.HEARTS -> n(22)
            Effect.FIREWORKS -> 0
            else -> 1
        }
        for (i in 0 until count) {
            px[i] = rnd.nextFloat() * w
            py[i] = rnd.nextFloat() * h
            ph[i] = rnd.nextFloat() * 6.283f
            rot[i] = rnd.nextFloat() * 360f
            life[i] = 1f
            when (effect) {
                Effect.RAIN, Effect.STORM -> {
                    sz[i] = (8f + rnd.nextFloat() * 10f) * d
                    vy[i] = (420f + rnd.nextFloat() * 260f) * d
                    vx[i] = -60f * d
                }
                Effect.SNOW -> {
                    sz[i] = (1.2f + rnd.nextFloat() * 2.4f) * d
                    vy[i] = (18f + rnd.nextFloat() * 30f) * d
                }
                Effect.LEAVES -> {
                    sz[i] = (9f + rnd.nextFloat() * 7f) * d
                    vy[i] = (22f + rnd.nextFloat() * 25f) * d
                    col[i] = LEAF_COLORS[rnd.nextInt(LEAF_COLORS.size)]
                }
                Effect.PETALS -> {
                    sz[i] = (6f + rnd.nextFloat() * 5f) * d
                    vy[i] = (16f + rnd.nextFloat() * 20f) * d
                    col[i] = PETAL_COLORS[rnd.nextInt(PETAL_COLORS.size)]
                }
                Effect.CLOUDS -> {
                    sz[i] = (26f + rnd.nextFloat() * 26f) * d
                    py[i] = h * (0.05f + rnd.nextFloat() * 0.6f)
                    vx[i] = (6f + rnd.nextFloat() * 8f) * d
                }
                Effect.STARS -> sz[i] = (0.6f + rnd.nextFloat() * 1.4f) * d
                Effect.FIREFLIES -> {
                    sz[i] = (1.6f + rnd.nextFloat() * 1.6f) * d
                    vx[i] = (rnd.nextFloat() - 0.5f) * 24f * d
                    vy[i] = (rnd.nextFloat() - 0.5f) * 24f * d
                }
                Effect.CONFETTI -> {
                    sz[i] = (3f + rnd.nextFloat() * 4f) * d
                    vy[i] = (35f + rnd.nextFloat() * 45f) * d
                    col[i] = rainbow()
                }
                Effect.HEARTS -> {
                    sz[i] = (8f + rnd.nextFloat() * 10f) * d
                    vy[i] = -(18f + rnd.nextFloat() * 22f) * d
                    col[i] = HEART_COLORS[rnd.nextInt(HEART_COLORS.size)]
                }
                else -> Unit
            }
        }
        if (effect == Effect.FIREWORKS) nextBurst = 0f
    }

    private fun rainbow(): Int {
        palette?.let { p -> if (p.isNotEmpty()) return p[rnd.nextInt(p.size)] }
        hsv[0] = rnd.nextFloat() * 360f
        return Color.HSVToColor(hsv)
    }

    /** White on dark backgrounds, a dark blue-grey on light ones. */
    private fun ink(alpha: Int): Int = if (lightBackground) Color.argb(alpha, 40, 60, 90) else Color.argb(alpha, 255, 255, 255)

    // ---------------------------------------------------------------- effects

    private fun rain(c: Canvas, w: Float, h: Float, dt: Float, dim: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 1.2f * density
        paint.color = if (lightBackground) Color.argb((110 * dim).toInt(), 60, 90, 140) else Color.argb((120 * dim).toInt(), 200, 225, 255)
        for (i in 0 until count) {
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            if (py[i] > h + sz[i]) {
                py[i] = -sz[i]
                px[i] = rnd.nextFloat() * (w + 40f * density)
            }
            val dx = vx[i] / vy[i] * sz[i]
            c.drawLine(px[i], py[i], px[i] - dx, py[i] - sz[i], paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun storm(c: Canvas, w: Float, h: Float, dt: Float) {
        // Darker sky, rain, and now and then a lightning flash with a bolt.
        paint.color = Color.argb(50, 10, 14, 30)
        c.drawRect(0f, 0f, w, h, paint)
        rain(c, w, h, dt, 1f)
        nextFlash -= dt
        if (nextFlash <= 0f) {
            flash = 1f
            boltSeed = rnd.nextLong()
            nextFlash = 3f + rnd.nextFloat() * 6f
        }
        if (flash > 0f) {
            paint.color = Color.argb((flash * 110).toInt(), 255, 255, 255)
            c.drawRect(0f, 0f, w, h, paint)
            if (flash > 0.55f) bolt(c, w, h)
            flash -= dt * 2.2f
        }
    }

    private fun bolt(c: Canvas, w: Float, h: Float) {
        val r = java.util.Random(boltSeed)
        path.reset()
        var x = w * (0.2f + r.nextFloat() * 0.6f)
        var y = 0f
        path.moveTo(x, y)
        while (y < h * 0.75f) {
            x += (r.nextFloat() - 0.5f) * 36f * density
            y += (10f + r.nextFloat() * 18f) * density
            path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.color = Color.argb((flash * 255).toInt().coerceIn(0, 255), 255, 250, 210)
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun snow(c: Canvas, w: Float, h: Float, dt: Float) {
        paint.color = if (lightBackground) Color.argb(150, 120, 150, 190) else Color.argb(220, 255, 255, 255)
        for (i in 0 until count) {
            ph[i] += dt * 1.4f
            px[i] += sin(ph[i]) * 12f * density * dt
            py[i] += vy[i] * dt
            if (py[i] > h + sz[i]) {
                py[i] = -sz[i]
                px[i] = rnd.nextFloat() * w
            }
            c.drawCircle(px[i], py[i], sz[i], paint)
        }
    }

    private fun leaves(c: Canvas, w: Float, h: Float, dt: Float) {
        for (i in 0 until count) {
            ph[i] += dt * 1.1f
            px[i] += sin(ph[i]) * 30f * density * dt
            py[i] += vy[i] * dt
            rot[i] += cos(ph[i]) * 90f * dt
            if (py[i] > h + sz[i]) {
                py[i] = -sz[i]
                px[i] = rnd.nextFloat() * w
            }
            paint.color = (col[i] and 0x00FFFFFF) or (0xDD shl 24)
            c.save()
            c.translate(px[i], py[i])
            c.rotate(rot[i])
            c.scale(sz[i] * 0.7f, sz[i])
            c.drawPath(leaf, paint)
            c.restore()
        }
    }

    private fun sunRays(c: Canvas, w: Float, h: Float) {
        // Soft wedges from the top-right corner, slowly turning and breathing.
        val cx = w * 0.92f
        val cy = -h * 0.15f
        val len = w * 1.2f
        val rays = 7
        for (k in 0 until rays) {
            val a = (PI * 0.55 + k * (PI * 0.5 / rays) + sin(clock * 0.15) * 0.08).toFloat()
            val spread = 0.06f
            val pulse = 0.5f + 0.5f * sin(clock * 0.8f + k)
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx + cos(a - spread) * len, cy + sin(a - spread) * len)
            path.lineTo(cx + cos(a + spread) * len, cy + sin(a + spread) * len)
            path.close()
            paint.color = Color.argb((18 + 22 * pulse).toInt(), 255, 236, 170)
            c.drawPath(path, paint)
        }
        paint.color = Color.argb(70, 255, 240, 190)
        c.drawCircle(cx, cy + h * 0.1f, h * 0.22f, paint)
    }

    private fun clouds(c: Canvas, w: Float, h: Float, dt: Float) {
        paint.color = if (lightBackground) Color.argb(70, 120, 140, 170) else Color.argb(60, 255, 255, 255)
        for (i in 0 until count) {
            px[i] += vx[i] * dt
            val s = sz[i]
            if (px[i] - s * 2.2f > w) px[i] = -s * 2.2f
            // A cloud: three overlapping puffs on a flat base.
            c.drawCircle(px[i] - s * 0.9f, py[i] + s * 0.25f, s * 0.6f, paint)
            c.drawCircle(px[i], py[i], s * 0.85f, paint)
            c.drawCircle(px[i] + s * 0.95f, py[i] + s * 0.2f, s * 0.65f, paint)
        }
    }

    private fun stars(c: Canvas, w: Float, h: Float, dt: Float) {
        for (i in 0 until count) {
            val tw = 0.5f + 0.5f * sin(clock * (1.2f + (i % 5) * 0.3f) + ph[i])
            paint.color = ink((60 + 180 * tw).toInt())
            c.drawCircle(px[i], py[i], sz[i], paint)
        }
        // Now and then a shooting star (uses particle slot MAX-1 as its state).
        val s = MAX - 1
        if (life[s] <= 0f && rnd.nextFloat() < dt * 0.15f) {
            life[s] = 1f
            px[s] = rnd.nextFloat() * w * 0.7f
            py[s] = rnd.nextFloat() * h * 0.4f
        }
        if (life[s] > 0f) {
            val t = 1f - life[s]
            val x = px[s] + t * w * 0.35f
            val y = py[s] + t * h * 0.25f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * density
            paint.color = ink((life[s] * 230).toInt())
            c.drawLine(x, y, x - w * 0.06f, y - h * 0.045f, paint)
            paint.style = Paint.Style.FILL
            life[s] -= dt * 1.6f
        }
    }

    private fun aurora(c: Canvas, w: Float, h: Float) {
        val key = (w.toInt() shl 1) or (if (lightBackground) 1 else 0)
        if (auroraShader == null || auroraFor != key) {
            auroraShader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Color.rgb(60, 255, 170), Color.rgb(80, 200, 255), Color.rgb(180, 110, 255), Color.rgb(60, 255, 170)),
                null, Shader.TileMode.MIRROR,
            )
            auroraFor = key
        }
        paint.shader = auroraShader
        for (band in 0 until 3) {
            val base = h * (0.18f + band * 0.16f)
            val amp = h * (0.07f + band * 0.02f)
            path.reset()
            path.moveTo(0f, base)
            var x = 0f
            val step = w / 24f
            while (x <= w + step) {
                val y = base + sin(x / w * 6.283f * (1.2f + band * 0.3f) + clock * (0.6f + band * 0.2f)) * amp
                path.lineTo(x, y)
                x += step
            }
            path.lineTo(w, base + h * 0.22f)
            path.lineTo(0f, base + h * 0.22f)
            path.close()
            paint.alpha = if (lightBackground) 40 else 55
            c.drawPath(path, paint)
        }
        paint.shader = null
        paint.alpha = 255
    }

    private fun fireflies(c: Canvas, w: Float, h: Float, dt: Float) {
        for (i in 0 until count) {
            ph[i] += dt
            // Wander: turn a little at random.
            vx[i] += (rnd.nextFloat() - 0.5f) * 40f * density * dt
            vy[i] += (rnd.nextFloat() - 0.5f) * 40f * density * dt
            val lim = 22f * density
            vx[i] = vx[i].coerceIn(-lim, lim)
            vy[i] = vy[i].coerceIn(-lim, lim)
            px[i] = (px[i] + vx[i] * dt + w) % w
            py[i] = (py[i] + vy[i] * dt + h) % h
            val glow = 0.5f + 0.5f * sin(ph[i] * 2.2f + i)
            val r = if (lightBackground) 150 else 214
            val g = if (lightBackground) 170 else 255
            val b = if (lightBackground) 30 else 120
            paint.color = Color.argb((50 * glow).toInt(), r, g, b)
            c.drawCircle(px[i], py[i], sz[i] * 4f, paint)
            paint.color = Color.argb((90 + 160 * glow).toInt(), r, g, b)
            c.drawCircle(px[i], py[i], sz[i], paint)
        }
    }

    private fun confetti(c: Canvas, w: Float, h: Float, dt: Float) {
        for (i in 0 until count) {
            ph[i] += dt * 3f
            px[i] += sin(ph[i]) * 20f * density * dt
            py[i] += vy[i] * dt
            rot[i] += 160f * dt
            if (py[i] > h + sz[i]) {
                py[i] = -sz[i]
                px[i] = rnd.nextFloat() * w
            }
            paint.color = col[i]
            c.save()
            c.rotate(rot[i], px[i], py[i])
            // Flip: the piece looks thinner as it turns.
            val thin = 0.25f + 0.75f * kotlin.math.abs(sin(ph[i]))
            c.drawRect(px[i] - sz[i] / 2, py[i] - sz[i] * thin / 3, px[i] + sz[i] / 2, py[i] + sz[i] * thin / 3, paint)
            c.restore()
        }
    }

    private fun hearts(c: Canvas, w: Float, h: Float, dt: Float) {
        for (i in 0 until count) {
            ph[i] += dt * 1.5f
            px[i] += sin(ph[i]) * 14f * density * dt
            py[i] += vy[i] * dt
            if (py[i] < -sz[i]) {
                py[i] = h + sz[i]
                px[i] = rnd.nextFloat() * w
            }
            // Fade in from the bottom, out near the top.
            val t = (py[i] / h).coerceIn(0f, 1f)
            paint.color = (col[i] and 0x00FFFFFF) or ((180 * kotlin.math.min(1f, t * 2.5f)).toInt() shl 24)
            c.save()
            c.translate(px[i], py[i])
            c.scale(sz[i], sz[i])
            c.drawPath(heart, paint)
            c.restore()
        }
    }

    private fun fireworks(c: Canvas, w: Float, h: Float, dt: Float) {
        nextBurst -= dt
        if (nextBurst <= 0f) {
            burst(w * (0.15f + rnd.nextFloat() * 0.7f), h * (0.15f + rnd.nextFloat() * 0.4f))
            nextBurst = (1.6f + rnd.nextFloat() * 1.6f) / (0.5f + amount)
        }
        var i = 0
        while (i < count) {
            life[i] -= dt * 0.8f
            if (life[i] <= 0f) {
                val last = --count
                if (i != last) moveParticle(last, i)
                continue
            }
            vy[i] += 60f * density * dt
            vx[i] *= 1f - 0.8f * dt
            vy[i] *= 1f - 0.8f * dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            paint.color = (col[i] and 0x00FFFFFF) or ((life[i] * 255).toInt().coerceIn(0, 255) shl 24)
            c.drawCircle(px[i], py[i], sz[i], paint)
            i++
        }
    }

    private fun burst(x: Float, y: Float) {
        val sparks = n(48)
        val color = rainbow()
        for (k in 0 until sparks) {
            if (count >= MAX - 1) return
            val i = count++
            val a = k * 6.283f / sparks
            val s = (70f + rnd.nextFloat() * 50f) * density
            px[i] = x; py[i] = y
            vx[i] = cos(a) * s; vy[i] = sin(a) * s
            sz[i] = (1.4f + rnd.nextFloat()) * density
            col[i] = color
            life[i] = 1f
        }
    }

    private fun moveParticle(from: Int, to: Int) {
        px[to] = px[from]; py[to] = py[from]; vx[to] = vx[from]; vy[to] = vy[from]
        sz[to] = sz[from]; ph[to] = ph[from]; rot[to] = rot[from]; col[to] = col[from]; life[to] = life[from]
    }

    private companion object {
        const val MAX = 200
        val LEAF_COLORS = intArrayOf(
            Color.rgb(214, 96, 36), Color.rgb(232, 158, 40), Color.rgb(190, 60, 40),
            Color.rgb(150, 110, 40), Color.rgb(120, 150, 50),
        )
        val PETAL_COLORS = intArrayOf(
            Color.rgb(255, 183, 197), Color.rgb(255, 209, 220), Color.rgb(248, 200, 220), Color.rgb(255, 240, 245),
            Color.rgb(230, 170, 210),
        )
        val HEART_COLORS = intArrayOf(
            Color.rgb(255, 77, 109), Color.rgb(255, 133, 161), Color.rgb(230, 57, 70), Color.rgb(255, 179, 198),
        )
    }
}
