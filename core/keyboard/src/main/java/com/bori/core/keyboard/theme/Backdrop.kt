package com.bori.core.keyboard.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.bori.core.keyboard.ime.DiagLog
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A picture behind the whole keyboard (strip + keys), as saved in a theme or chosen by the user
 * in Settings → Themes → Background (Stage 3.4). All fields optional, like the rest of ThemeSpec.
 */
@Serializable
data class BackgroundSpec(
    /** none (plain theme colour), gradient, scene or photo. */
    val type: String = "none",
    /** gradient: 2 or more colours, same syntax as ThemeColors. */
    val colors: List<String>? = null,
    /** gradient direction in degrees: 90 top to bottom, 0 left to right, 45 and 135 diagonals. */
    val angle: Int = 90,
    /** scene: an id from [Backdrop.SCENES]. */
    val scene: String? = null,
    /** scene: sky mood from the weather (clear, cloudy, rain, storm, snow); null = clear. City look, theme 3.10. */
    val weather: String? = null,
    /** photo: file name, next to the theme or (user's pick) in filesDir/backgrounds. */
    val image: String? = null,
    /** photo: 1 (whole photo fits) to 3. */
    val zoom: Float = 1f,
    /** photo: which part shows when it's cropped, 0 to 1. */
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    /** -0.8 (darker) to 0.6 (lighter). */
    val brightness: Float = 0f,
    /** 0 (sharp) to 1 (very soft). */
    val blur: Float = 0f,
    /** How strongly the picture shows over the theme's background colour, 0.2 to 1. */
    val opacity: Float = 1f,
)

/** A resolved background: everything needed to draw it (views read only this). */
data class Backdrop(
    val type: String,
    val colors: List<Int>,
    val angle: Int,
    val scene: String,
    val night: Boolean,
    val imageFile: File?,
    /** The photo's last-modified time, so a replaced photo is never served from the cache. */
    val imageStamp: Long,
    val zoom: Float,
    val focusX: Float,
    val focusY: Float,
    val brightness: Float,
    val blur: Float,
    val opacity: Float,
    /** The theme's background colour, under the picture. */
    val baseColor: Int,
    /** Scene sky mood (clear, cloudy, rain, storm, snow). */
    val weather: String = "clear",
) {
    companion object {
        val SCENES = listOf(
            "mountains" to "Mountains", "beach" to "Beach", "skyline" to "City", "yunque" to "El Yunque",
            "nyc" to "New York", "sanjuan" to "Viejo San Juan", "farm" to "Farmland", "desert" to "Desert",
            "forest" to "Forest & lake", "dots" to "Dot grid",
        )

        /** Ready-made gradients for Settings (the editor in 3.7 adds custom colours). */
        val GRADIENTS = listOf(
            listOf("#FF9A8B", "#FF6A88", "#FF99AC"),
            listOf("#2E3192", "#1BFFFF"),
            listOf("#0F2027", "#203A43", "#2C5364"),
            listOf("#F7971E", "#FFD200"),
            listOf("#8E2DE2", "#4A00E0"),
            listOf("#00B09B", "#96C93D"),
            listOf("#232526", "#414345"),
            listOf("#FBD3E9", "#BB377D"),
            listOf("#89F7FE", "#66A6FF"),
            listOf("#0B486B", "#F56217"),
            listOf("#C6FFDD", "#FBD786", "#F7797D"),
            listOf("#41B3E5", "#FFFFFF", "#E4002B"),
        )

        fun resolve(context: Context, s: BackgroundSpec?, dir: File, night: Boolean, base: Int): Backdrop? {
            if (s == null) return null
            val colors = s.colors?.mapNotNull { ThemeResolver.parseColor(context, it) }.orEmpty()
            val file = s.image?.let { File(dir, File(it).name) }?.takeIf { it.isFile }
            val type = when (s.type) {
                "gradient" -> if (colors.size >= 2) "gradient" else return null
                "scene" -> "scene"
                "photo" -> if (file != null) "photo" else return null
                else -> return null
            }
            return Backdrop(
                type, colors, s.angle, s.scene ?: "mountains", night, file, file?.lastModified() ?: 0L,
                s.zoom.coerceIn(1f, 3f), s.focusX.coerceIn(0f, 1f), s.focusY.coerceIn(0f, 1f),
                s.brightness.coerceIn(-0.8f, 0.6f), s.blur.coerceIn(0f, 1f), s.opacity.coerceIn(0.2f, 1f), base,
                s.weather ?: "clear",
            )
        }
    }
}

/**
 * Draws backdrops into bitmaps the size of the view, cached, so a keyboard draws one bitmap per
 * frame. Main thread only. Photos are decoded at about the size needed (inSampleSize).
 */
object BackdropRenderer {
    private const val MAX_CACHED = 5
    private val cache = LinkedHashMap<String, Bitmap>(8, 0.75f, true)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun get(context: Context, b: Backdrop, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val key = "$b/$w/$h"
        cache[key]?.let { return it }
        val bmp = runCatching { render(context, b, w, h) }
            .onFailure { DiagLog.event("backdrop ${b.type} failed: $it") }
            .getOrNull() ?: return null
        cache[key] = bmp
        // Old bitmaps are dropped, never recycled: a view may still be drawing one.
        while (cache.size > MAX_CACHED) {
            val it = cache.keys.iterator()
            it.next()
            it.remove()
        }
        return bmp
    }

    /** Average colour of the backdrop, for picking readable text (see ThemeResolver). */
    fun averageColor(context: Context, b: Backdrop): Int {
        val bmp = get(context, b, 48, 24) ?: return b.baseColor
        var r = 0L
        var g = 0L
        var bl = 0L
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (p in px) {
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p)
        }
        val n = px.size.coerceAtLeast(1)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    private fun render(context: Context, b: Backdrop, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(b.baseColor)
        val layered = b.opacity < 1f
        if (layered) c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (b.opacity * 255).toInt())
        when (b.type) {
            "gradient" -> drawGradient(c, b, w, h)
            "scene" -> Scenes.draw(c, b.scene, w, h, b.night, b.weather)
            "photo" -> drawPhoto(c, b, w, h)
        }
        if (layered) c.restore()
        if (b.type != "photo" && b.brightness != 0f) {
            // Photos get brightness in their colour filter; others get a wash on top.
            paint.shader = null
            paint.colorFilter = null
            paint.color = if (b.brightness < 0f) Color.BLACK else Color.WHITE
            paint.alpha = (kotlin.math.abs(b.brightness) * 255).toInt()
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.alpha = 255
        }
        return out
    }

    private fun drawGradient(c: Canvas, b: Backdrop, w: Int, h: Int) {
        val a = Math.toRadians(b.angle.toDouble())
        val cx = w / 2f
        val cy = h / 2f
        // Half the length of the line through the centre at this angle that spans the box.
        val half = (kotlin.math.abs(cos(a)) * w + kotlin.math.abs(sin(a)) * h).toFloat() / 2f
        val dx = (cos(a) * half).toFloat()
        val dy = (sin(a) * half).toFloat()
        paint.colorFilter = null
        paint.shader = LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, b.colors.toIntArray(), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
    }

    private fun drawPhoto(c: Canvas, b: Backdrop, w: Int, h: Int) {
        val f = b.imageFile ?: return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val iw = bounds.outWidth
        val ih = bounds.outHeight
        if (iw <= 0 || ih <= 0) return
        // Visible part of the photo (in photo pixels): cover the box, then zoom in.
        val scale = max(w.toFloat() / iw, h.toFloat() / ih) * b.zoom
        val srcW = w / scale
        val srcH = h / scale
        var sample = 1
        // Decode at no less than the size it's drawn at (blur needs even less).
        while (srcW / (sample * 2) >= w && srcH / (sample * 2) >= h) sample *= 2
        val img = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        val sx = img.width.toFloat() / iw
        val sy = img.height.toFloat() / ih
        val left = (iw - srcW) * b.focusX
        val top = (ih - srcH) * b.focusY
        val src = Rect((left * sx).toInt(), (top * sy).toInt(), ((left + srcW) * sx).toInt(), ((top + srcH) * sy).toInt())
        paint.shader = null
        paint.colorFilter = brightnessFilter(b.brightness)
        if (b.blur > 0.01f) {
            // Soft blur: draw small, then stretch back up with filtering (works on every Android).
            val k = 1f + b.blur * 15f
            val sw = (w / k).toInt().coerceAtLeast(2)
            val sh = (h / k).toInt().coerceAtLeast(2)
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(img, src, Rect(0, 0, sw, sh), paint)
            paint.colorFilter = null
            c.drawBitmap(small, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
            small.recycle()
        } else {
            c.drawBitmap(img, src, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
        }
        paint.colorFilter = null
        img.recycle()
    }

    private fun brightnessFilter(v: Float): ColorMatrixColorFilter? {
        if (v == 0f) return null
        val m = if (v < 0f) {
            val s = 1f + v
            ColorMatrix(floatArrayOf(s, 0f, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        } else {
            val s = 1f - v
            val t = 255f * v
            ColorMatrix(floatArrayOf(s, 0f, 0f, 0f, t, 0f, s, 0f, 0f, t, 0f, 0f, s, 0f, t, 0f, 0f, 0f, 1f, 0f))
        }
        return ColorMatrixColorFilter(m)
    }
}

/** Behind the input view: draws the cached backdrop bitmap. */
class BackdropDrawable(private val context: Context, private val b: Backdrop) : Drawable() {
    override fun draw(canvas: Canvas) {
        val r = bounds
        val bmp = BackdropRenderer.get(context, b, r.width(), r.height())
        if (bmp != null) canvas.drawBitmap(bmp, r.left.toFloat(), r.top.toFloat(), null) else canvas.drawColor(b.baseColor)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

/** Procedural scenes, our own drawings (no image files), in a day and a night version. */
internal object Scenes {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas, id: String, w: Int, h: Int, night: Boolean, weather: String = "clear") {
        val W = w.toFloat()
        val H = h.toFloat()
        val rnd = Random(id.hashCode().toLong())
        when (id) {
            "beach" -> beach(c, W, H, night, rnd)
            "skyline" -> skyline(c, W, H, night, rnd)
            "yunque" -> yunque(c, W, H, night, rnd)
            "nyc" -> nyc(c, W, H, night, rnd)
            "sanjuan" -> sanJuan(c, W, H, night, rnd)
            "farm" -> farm(c, W, H, night, rnd)
            "desert" -> desert(c, W, H, night, rnd)
            "forest" -> forest(c, W, H, night, rnd)
            "dots" -> dots(c, W, H, night)
            else -> mountains(c, W, H, night, rnd)
        }
        p.shader = null
        if (id != "dots") weatherSky(c, W, H, weather, night, Random(7L))
        p.shader = null
        p.alpha = 255
    }

    private fun sky(c: Canvas, W: Float, H: Float, top: Int, bottom: Int) {
        p.shader = LinearGradient(0f, 0f, 0f, H, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        p.shader = null
    }

    private fun stars(c: Canvas, W: Float, H: Float, rnd: Random, n: Int) {
        p.color = Color.WHITE
        repeat(n) {
            p.alpha = 90 + rnd.nextInt(150)
            c.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * H * 0.6f, 0.6f + rnd.nextFloat() * 1.6f, p)
        }
        p.alpha = 255
    }

    /** A jagged ridge across the width, [base] = average height from the top. */
    private fun ridge(c: Canvas, W: Float, H: Float, base: Float, rough: Float, color: Int, rnd: Random) {
        val path = Path()
        path.moveTo(0f, H)
        var x = 0f
        var y = base
        path.lineTo(0f, y)
        while (x < W) {
            x += W * (0.04f + rnd.nextFloat() * 0.08f)
            y = (base + (rnd.nextFloat() - 0.5f) * rough).coerceIn(H * 0.05f, H)
            path.lineTo(x, y)
        }
        path.lineTo(W, H)
        path.close()
        p.color = color
        c.drawPath(path, p)
    }

    private fun mountains(c: Canvas, W: Float, H: Float, night: Boolean, rnd: Random) {
        if (night) {
            sky(c, W, H, Color.rgb(11, 20, 48), Color.rgb(43, 58, 103))
            stars(c, W, H, rnd, 70)
            p.color = Color.rgb(236, 236, 214)
            c.drawCircle(W * 0.8f, H * 0.18f, H * 0.07f, p)
        } else {
            sky(c, W, H, Color.rgb(155, 199, 232), Color.rgb(240, 226, 210))
            p.color = Color.rgb(255, 214, 140)
            c.drawCircle(W * 0.78f, H * 0.2f, H * 0.08f, p)
        }
        val layers = if (night) {
            intArrayOf(Color.rgb(42, 53, 88), Color.rgb(30, 39, 69), Color.rgb(20, 27, 51))
        } else {
            intArrayOf(Color.rgb(124, 147, 181), Color.rgb(91, 115, 150), Color.rgb(62, 86, 119))
        }
        ridge(c, W, H, H * 0.38f, H * 0.28f, layers[0], rnd)
        ridge(c, W, H, H * 0.55f, H * 0.22f, layers[1], rnd)
        ridge(c, W, H, H * 0.72f, H * 0.14f, layers[2], rnd)
    }

    private fun beach(c: Canvas, W: Float, H: Float, night: Boolean, rnd: Random) {
        val horizon = H * 0.42f
        val shore = H * 0.7f
        if (night) {
            sky(c, W, horizon, Color.rgb(12, 18, 46), Color.rgb(48, 52, 98))
            stars(c, W, horizon, rnd, 50)
            p.color = Color.rgb(240, 236, 210)
            c.drawCircle(W * 0.25f, horizon * 0.4f, H * 0.06f, p)
        } else {
            sky(c, W, horizon, Color.rgb(120, 196, 238), Color.rgb(214, 238, 250))
            p.color = Color.rgb(255, 224, 150)
            c.drawCircle(W * 0.25f, horizon * 0.45f, H * 0.07f, p)
        }
        p.shader = LinearGradient(
            0f, horizon, 0f, shore,
            if (night) Color.rgb(22, 40, 78) else Color.rgb(37, 150, 200),
            if (night) Color.rgb(16, 58, 88) else Color.rgb(60, 196, 210),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, horizon, W, shore, p)
        p.shader = null
        // Foam line where the sea meets the sand.
        val path = Path()
        path.moveTo(0f, shore)
        var x = 0f
        while (x < W) {
            val nx = x + W * 0.08f
            path.quadTo(x + W * 0.04f, shore - H * 0.03f * (0.5f + rnd.nextFloat()), nx, shore)
            x = nx
        }
        path.lineTo(W, H)
        path.lineTo(0f, H)
        path.close()
        p.color = if (night) Color.rgb(96, 88, 76) else Color.rgb(242, 215, 160)
        c.drawPath(path, p)
    }

    private fun skyline(c: Canvas, W: Float, H: Float, night: Boolean, rnd: Random) {
        if (night) {
            sky(c, W, H, Color.rgb(10, 15, 44), Color.rgb(46, 36, 92))
            stars(c, W, H * 0.5f, rnd, 40)
        } else {
            sky(c, W, H, Color.rgb(255, 179, 124), Color.rgb(106, 76, 147))
        }
        for ((layer, base) in listOf(0.35f, 0.5f).withIndex()) {
            var x = -W * 0.02f
            while (x < W) {
                val bw = W * (0.05f + rnd.nextFloat() * 0.07f)
                val top = H * (base + rnd.nextFloat() * 0.3f)
                p.color = when {
                    night && layer == 0 -> Color.rgb(28, 30, 64)
                    night -> Color.rgb(16, 16, 40)
                    layer == 0 -> Color.rgb(120, 82, 140)
                    else -> Color.rgb(70, 46, 96)
                }
                c.drawRect(x, top, x + bw, H, p)
                if (night && layer == 1) {
                    // Lit windows.
                    p.color = Color.rgb(255, 214, 120)
                    var wy = top + H * 0.03f
                    while (wy < H - H * 0.04f) {
                        var wx = x + bw * 0.15f
                        while (wx < x + bw * 0.85f) {
                            if (rnd.nextFloat() < 0.35f) c.drawRect(wx, wy, wx + bw * 0.12f, wy + H * 0.02f, p)
                            wx += bw * 0.25f
                        }
                        wy += H * 0.05f
                    }
                }
                x += bw + W * 0.004f
            }
        }
    }

    /**
     * El Yunque rainforest (the Coquí theme): misty green peaks, ferns along the bottom; sun by
     * day, moon, stars and fireflies at night.
     */
    private fun yunque(c: Canvas, W: Float, H: Float, night: Boolean, rnd: Random) {
        if (night) {
            sky(c, W, H, Color.rgb(6, 20, 26), Color.rgb(16, 44, 38))
            stars(c, W, H * 0.6f, rnd, 45)
            p.color = Color.rgb(236, 240, 214)
            c.drawCircle(W * 0.82f, H * 0.16f, H * 0.06f, p)
        } else {
            sky(c, W, H, Color.rgb(191, 227, 242), Color.rgb(232, 244, 236))
            p.color = Color.rgb(255, 240, 190)
            p.alpha = 90
            c.drawCircle(W * 0.8f, H * 0.17f, H * 0.12f, p)
            p.alpha = 255
            c.drawCircle(W * 0.8f, H * 0.17f, H * 0.07f, p)
        }
        val layers = if (night) {
            intArrayOf(Color.rgb(22, 54, 42), Color.rgb(14, 40, 30), Color.rgb(8, 28, 20))
        } else {
            intArrayOf(Color.rgb(150, 192, 160), Color.rgb(104, 160, 112), Color.rgb(62, 122, 68))
        }
        ridge(c, W, H, H * 0.36f, H * 0.3f, layers[0], rnd)
        ridge(c, W, H, H * 0.52f, H * 0.22f, layers[1], rnd)
        ridge(c, W, H, H * 0.7f, H * 0.12f, layers[2], rnd)
        // Ferns rising from the bottom edge.
        val fernColor = if (night) Color.rgb(5, 18, 11) else Color.rgb(38, 92, 44)
        var x = W * 0.02f
        while (x < W) {
            val len = H * (0.22f + rnd.nextFloat() * 0.2f)
            val lean = (rnd.nextFloat() - 0.5f) * len * 0.9f
            fern(c, x, H, len, lean, fernColor, rnd)
            x += W * (0.06f + rnd.nextFloat() * 0.07f)
        }
        if (night) {
            // Fireflies: a soft halo and a bright centre.
            repeat(26) {
                val fx = rnd.nextFloat() * W
                val fy = H * (0.3f + rnd.nextFloat() * 0.65f)
                val r = H * (0.006f + rnd.nextFloat() * 0.006f)
                p.color = Color.rgb(214, 255, 120)
                p.alpha = 55
                c.drawCircle(fx, fy, r * 3.5f, p)
                p.alpha = 230
                c.drawCircle(fx, fy, r, p)
            }
            p.alpha = 255
        }
    }

    /** One fern frond: a curved stem from (x0, y0) with leaflets on both sides, shorter toward the tip. */
    private fun fern(c: Canvas, x0: Float, y0: Float, len: Float, lean: Float, color: Int, rnd: Random) {
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = (len * 0.025f).coerceAtLeast(1f)
        val cx = x0 + lean * 0.2f
        val cy = y0 - len * 0.75f
        val ex = x0 + lean
        val ey = y0 - len
        val stem = Path()
        stem.moveTo(x0, y0)
        stem.quadTo(cx, cy, ex, ey)
        c.drawPath(stem, p)
        val n = 9 + rnd.nextInt(4)
        p.strokeWidth = (len * 0.018f).coerceAtLeast(1f)
        for (i in 1 until n) {
            val t = i / n.toFloat()
            val u = 1f - t
            // Point and direction on the quadratic curve.
            val px = u * u * x0 + 2f * u * t * cx + t * t * ex
            val py = u * u * y0 + 2f * u * t * cy + t * t * ey
            var dx = 2f * u * (cx - x0) + 2f * t * (ex - cx)
            var dy = 2f * u * (cy - y0) + 2f * t * (ey - cy)
            val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            dx /= d
            dy /= d
            val leaf = len * (0.05f + 0.2f * u)
            // Leaflets point outward and a little up the stem.
            for (side in intArrayOf(-1, 1)) {
                val lx = -dy * side * 0.85f + dx * 0.5f
                val ly = dx * side * 0.85f + dy * 0.5f
                c.drawLine(px, py, px + lx * leaf, py + ly * leaf, p)
            }
        }
        p.style = Paint.Style.FILL
        p.strokeWidth = 0f
    }

    // Theme 3.10 part 3 (City look): place scenes and the weather sky over any scene.

    private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

    /** Darker, a bit bluer version of [c] for night scenes. */
    private fun dusk(c: Int, f: Float = 0.38f): Int =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f + 18).toInt().coerceAtMost(255))

    private fun sun(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        p.color = color
        p.alpha = 80
        c.drawCircle(x, y, r * 1.7f, p)
        p.alpha = 255
        c.drawCircle(x, y, r, p)
    }

    private fun nightSky(c: Canvas, W: Float, H: Float, rnd: Random, moonX: Float, moonY: Float, r: Float) {
        sky(c, W, H, rgb(9, 15, 40), rgb(44, 48, 94))
        stars(c, W, H, rnd, 45)
        p.color = rgb(238, 236, 214)
        c.drawCircle(moonX, moonY, r, p)
    }

    /**
     * New York: a river with a suspension bridge, a skyline with a stepped spire tower and an
     * arched-crown tower (generic shapes, no real logos), lit windows at night.
     */
    private fun nyc(c: Canvas, W: Float, H: Float, night: Boolean, rnd: Random) {
        val water = H * 0.8f
        if (night) nightSky(c, W, water, rnd, W * 0.12f, H * 0.14f, H * 0.05f)
        else {
            sky(c, W, water, rgb(132, 186, 226), rgb(250, 216, 184))
            sun(c, W * 0.12f, H * 0.16f, H * 0.06f, rgb(255, 226, 160))
        }
        val back = if (night) rgb(30, 34, 72) else rgb(128, 148, 178)
        val mid = if (night) rgb(20, 22, 52) else rgb(88, 106, 140)
        val front = if (night) rgb(12, 14, 36) else rgb(62, 78, 108)
        var x = -W * 0.02f
        while (x < W) {
            val bw = W * (0.03f + rnd.nextFloat() * 0.05f)
            p.color = back
            c.drawRect(x, H * (0.40f + rnd.nextFloat() * 0.22f), x + bw, water, p)
            x += bw
        }
        p.color = mid
        val spireX = W * 0.30f
        val spireW = W * 0.075f
        val top = H * 0.06f
        val h = water - top
        c.drawRect(spireX - spireW / 2, top + h * 0.32f, spireX + spireW / 2, water, p)
        c.drawRect(spireX - spireW * 0.36f, top + h * 0.22f, spireX + spireW * 0.36f, water, p)
        c.drawRect(spireX - spireW * 0.22f, top + h * 0.14f, spireX + spireW * 0.22f, water, p)
        c.drawRect(spireX - spireW * 0.10f, top + h * 0.08f, spireX + spireW * 0.10f, water, p)
        c.drawRect(spireX - spireW * 0.025f, top, spireX + spireW * 0.025f, water, p)
        val decoX = W * 0.52f
        val decoW = W * 0.06f
        val decoTop = H * 0.16f
        val dh = water - decoTop
        c.drawRect(decoX - decoW / 2, decoTop + dh * 0.22f, decoX + decoW / 2, water, p)
        for (i in 0 until 4) {
            val r = decoW * (0.5f - i * 0.1f)
            val y = decoTop + dh * (0.22f - i * 0.045f)
            c.drawArc(RectF(decoX - r, y - r * 0.9f, decoX + r, y + r * 0.9f), 180f, 180f, true, p)
        }
        c.drawRect(decoX - decoW * 0.02f, decoTop - dh * 0.02f, decoX + decoW * 0.02f, decoTop + dh * 0.1f, p)
        x = -W * 0.01f
        while (x < W * 0.68f) {
            val bw = W * (0.04f + rnd.nextFloat() * 0.05f)
            val bt = H * (0.55f + rnd.nextFloat() * 0.15f)
            p.color = front
            c.drawRect(x, bt, x + bw - W * 0.004f, water, p)
            if (night) {
                p.color = rgb(255, 214, 120)
                var wy = bt + H * 0.025f
                while (wy < water - H * 0.03f) {
                    var wx = x + bw * 0.15f
                    while (wx < x + bw * 0.8f) {
                        if (rnd.nextFloat() < 0.4f) c.drawRect(wx, wy, wx + bw * 0.1f, wy + H * 0.015f, p)
                        wx += bw * 0.22f
                    }
                    wy += H * 0.04f
                }
            }
            x += bw
        }
        p.shader = LinearGradient(
            0f, water, 0f, H,
            if (night) rgb(18, 26, 60) else rgb(72, 122, 170),
            if (night) rgb(8, 12, 32) else rgb(40, 84, 130),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, water, W, H, p)
        p.shader = null
        if (night) {
            p.color = rgb(255, 214, 120)
            repeat(30) {
                val rx = rnd.nextFloat() * W * 0.68f
                val ry = water + rnd.nextFloat() * (H - water)
                p.alpha = 60 + rnd.nextInt(90)
                c.drawRect(rx, ry, rx + W * 0.02f, ry + H * 0.004f, p)
            }
            p.alpha = 255
        }
        // Suspension bridge on the right, deck just above the water.
        val deck = water + (H - water) * 0.18f
        val t1 = W * 0.76f
        val t2 = W * 0.95f
        val towerTop = H * 0.42f
        val steel = if (night) rgb(150, 162, 204) else rgb(66, 76, 98)
        p.color = steel
        for (tx in floatArrayOf(t1, t2)) {
            c.drawRect(tx - W * 0.011f, towerTop, tx - W * 0.004f, H, p)
            c.drawRect(tx + W * 0.004f, towerTop, tx + W * 0.011f, H, p)
            c.drawRect(tx - W * 0.011f, towerTop + H * 0.03f, tx + W * 0.011f, towerTop + H * 0.045f, p)
            c.drawRect(tx - W * 0.011f, towerTop + H * 0.18f, tx + W * 0.011f, towerTop + H * 0.195f, p)
        }
        c.drawRect(W * 0.6f, deck, W, deck + H * 0.012f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = (H * 0.006f).coerceAtLeast(1.5f)
        val sag = deck - H * 0.05f
        val ctrl = 2f * sag - towerTop
        val cable = Path()
        cable.moveTo(W * 0.6f, deck)
        cable.quadTo((W * 0.6f + t1) / 2f, deck - H * 0.02f, t1, towerTop)
        cable.quadTo((t1 + t2) / 2f, ctrl, t2, towerTop)
        cable.quadTo(W * 1.02f, towerTop + H * 0.1f, W * 1.05f, deck)
        c.drawPath(cable, p)
        p.strokeWidth = (H * 0.0025f).coerceAtLeast(1f)
        var sx = t1 + W * 0.012f
        while (sx < t2 - W * 0.01f) {
            val t = (sx - t1) / (t2 - t1)
            val u = 1f - t
            val cy = u * u * towerTop + 2f * u * t * ctrl + t * t * towerTop
            c.drawLine(sx, cy, sx, deck, p)
            sx += W * 0.012f
        }
        p.style = Paint.Style.FILL
        p.strokeWidth = 0f
        if (night) {
            p.color = rgb(255, 236, 170)
            var lx = W * 0.62f
            while (lx < W) {
                c.drawCircle(lx, deck - H * 0.004f, H * 0.004f, p)
                lx += W * 0.02f
            }
        }
    }

    /**
     * Viejo San Juan (the Borinquen city look): the sea, a fortress wall with a garita (sentry box)
     * and a row of colourful colonial houses on a blue cobblestone street.
     */
    private fun sanJuan(c: Canvas, W: Float, H: Float, night: Boolean, rnd: Random) {
        val sea = H * 0.5f
        if (night) nightSky(c, W, sea, rnd, W * 0.6f, H * 0.14f, H * 0.05f)
        else {
            sky(c, W, sea, rgb(110, 190, 240), rgb(214, 238, 250))
            sun(c, W * 0.6f, H * 0.16f, H * 0.06f, rgb(255, 230, 150))
        }
        p.shader = LinearGradient(
            0f, sea, 0f, H * 0.8f,
            if (night) rgb(18, 36, 76) else rgb(30, 140, 196),
            if (night) rgb(12, 50, 80) else rgb(56, 190, 206),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, sea, W, H, p)
        p.shader = null
        // Fortress wall and garita on the left.
        val stone = if (night) rgb(74, 66, 66) else rgb(198, 172, 132)
        val wallTop = H * 0.62f
        val wall = Path()
        wall.moveTo(0f, wallTop - H * 0.02f)
        wall.lineTo(W * 0.32f, wallTop)
        wall.lineTo(W * 0.36f, H)
        wall.lineTo(0f, H)
        wall.close()
        p.color = stone
        c.drawPath(wall, p)
        p.color = dusk(stone, 0.8f)
        c.drawRect(0f, wallTop - H * 0.035f, W * 0.32f, wallTop - H * 0.015f, p)
        val gx = W * 0.2f
        val gw = W * 0.05f
        val gTop = H * 0.4f
        p.color = stone
        c.drawRect(gx - gw / 2, gTop, gx + gw / 2, wallTop, p)
        c.drawArc(RectF(gx - gw / 2, gTop - gw * 0.55f, gx + gw / 2, gTop + gw * 0.55f), 180f, 180f, true, p)
        c.drawRect(gx - gw * 0.06f, gTop - gw * 0.75f, gx + gw * 0.06f, gTop - gw * 0.4f, p)
        c.drawRect(gx - gw * 0.65f, wallTop - H * 0.02f, gx + gw * 0.65f, wallTop + H * 0.01f, p)
        p.color = if (night) rgb(20, 18, 24) else rgb(90, 70, 50)
        c.drawRect(gx - gw * 0.1f, gTop + (wallTop - gTop) * 0.25f, gx + gw * 0.1f, gTop + (wallTop - gTop) * 0.6f, p)
        // Colonial houses.
        val colors = intArrayOf(
            rgb(244, 160, 170), rgb(250, 214, 110), rgb(120, 190, 230), rgb(140, 210, 160),
            rgb(246, 140, 100), rgb(200, 160, 230), rgb(255, 190, 120),
        )
        val street = H * 0.93f
        var x = W * 0.34f
        var i = 0
        while (x < W) {
            val hw = W * (0.09f + rnd.nextFloat() * 0.04f)
            val ht = H * (0.42f + rnd.nextFloat() * 0.12f)
            val top = street - ht
            val body = colors[(i + rnd.nextInt(2)) % colors.size]
            p.color = if (night) dusk(body, 0.45f) else body
            c.drawRect(x, top, x + hw, street, p)
            p.color = if (night) dusk(body, 0.3f) else dusk(body, 0.8f)
            c.drawRect(x - W * 0.003f, top, x + hw + W * 0.003f, top + H * 0.025f, p)
            // Arched door and two shuttered windows.
            val door = if (night) rgb(70, 40, 30) else rgb(120, 70, 40)
            p.color = door
            val dw = hw * 0.26f
            val dx = x + hw / 2
            c.drawRect(dx - dw / 2, street - ht * 0.36f, dx + dw / 2, street, p)
            c.drawCircle(dx, street - ht * 0.36f, dw / 2, p)
            val win = if (night) rgb(255, 210, 120) else rgb(70, 110, 120)
            p.color = win
            for (wx in floatArrayOf(x + hw * 0.14f, x + hw * 0.64f)) {
                c.drawRect(wx, top + ht * 0.2f, wx + hw * 0.22f, top + ht * 0.42f, p)
            }
            p.color = if (night) rgb(30, 28, 36) else rgb(250, 250, 245)
            c.drawRect(x + hw * 0.1f, top + ht * 0.44f, x + hw * 0.9f, top + ht * 0.46f, p)
            x += hw
            i++
        }
        p.color = if (night) rgb(30, 40, 66) else rgb(84, 108, 150)
        c.drawRect(W * 0.33f, street, W, H, p)
        p.color = if (night) rgb(40, 52, 84) else rgb(110, 134, 176)
        var cx = W * 0.34f
        while (cx < W) {
            c.drawRect(cx, street + H * 0.02f, cx + W * 0.012f, street + H * 0.035f, p)
            cx += W * 0.02f
        }
    }

    /**
     * Farmland (small farm towns): crop rows running to the horizon, a red barn with a white X
     * door, a silo and a water tower.
     */
    private fun farm(c: Canvas, W: Float, H: Float, night: Boolean, rnd: Random) {
        val horizon = H * 0.5f
        if (night) nightSky(c, W, horizon, rnd, W * 0.85f, H * 0.14f, H * 0.05f)
        else {
            sky(c, W, horizon, rgb(120, 182, 232), rgb(226, 238, 246))
            sun(c, W * 0.85f, H * 0.16f, H * 0.06f, rgb(255, 222, 140))
        }
        ridge(c, W, H, horizon - H * 0.02f, H * 0.04f, if (night) rgb(24, 40, 40) else rgb(118, 160, 110), rnd)
        // Fields in bands, rows converging on a point on the horizon.
        val bands = intArrayOf(rgb(206, 176, 92), rgb(120, 168, 78), rgb(186, 150, 70), rgb(98, 146, 64))
        var y = horizon
        var b = 0
        while (y < H) {
            val bh = (H - horizon) * (0.12f + b * 0.06f)
            p.color = if (night) dusk(bands[b % bands.size], 0.32f) else bands[b % bands.size]
            c.drawRect(0f, y, W, y + bh, p)
            y += bh
            b++
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = (H * 0.003f).coerceAtLeast(1f)
        p.color = if (night) rgb(10, 16, 14) else rgb(90, 110, 50)
        p.alpha = 120
        val vx = W * 0.45f
        var i = -24
        while (i <= 36) {
            val bx = vx + i * W * 0.05f
            c.drawLine(vx + (bx - vx) * 0.04f, horizon + H * 0.01f, bx, H, p)
            i++
        }
        p.alpha = 255
        p.style = Paint.Style.FILL
        p.strokeWidth = 0f
        // Water tower far left.
        val tx = W * 0.14f
        p.color = if (night) rgb(60, 70, 80) else rgb(176, 186, 196)
        p.strokeWidth = (H * 0.004f).coerceAtLeast(1f)
        p.style = Paint.Style.STROKE
        c.drawLine(tx - W * 0.018f, horizon, tx - W * 0.01f, horizon - H * 0.1f, p)
        c.drawLine(tx + W * 0.018f, horizon, tx + W * 0.01f, horizon - H * 0.1f, p)
        p.style = Paint.Style.FILL
        c.drawOval(RectF(tx - W * 0.03f, horizon - H * 0.17f, tx + W * 0.03f, horizon - H * 0.095f), p)
        c.drawArc(RectF(tx - W * 0.012f, horizon - H * 0.19f, tx + W * 0.012f, horizon - H * 0.15f), 180f, 180f, true, p)
        // Round trees along the horizon.
        repeat(7) {
            val rx = W * (0.25f + rnd.nextFloat() * 0.3f)
            p.color = if (night) rgb(14, 28, 22) else rgb(70, 116, 60)
            c.drawCircle(rx, horizon - H * 0.015f, H * (0.02f + rnd.nextFloat() * 0.015f), p)
        }
        // Barn and silo.
        val bx = W * 0.62f
        val bw = W * 0.14f
        val base = H * 0.68f
        val wallH = H * 0.13f
        val red = if (night) rgb(80, 24, 22) else rgb(178, 46, 38)
        p.color = red
        c.drawRect(bx, base - wallH, bx + bw, base, p)
        val roof = Path()
        roof.moveTo(bx - bw * 0.04f, base - wallH)
        roof.lineTo(bx + bw * 0.12f, base - wallH - H * 0.06f)
        roof.lineTo(bx + bw * 0.5f, base - wallH - H * 0.1f)
        roof.lineTo(bx + bw * 0.88f, base - wallH - H * 0.06f)
        roof.lineTo(bx + bw * 1.04f, base - wallH)
        roof.close()
        p.color = if (night) rgb(36, 30, 32) else rgb(90, 60, 56)
        c.drawPath(roof, p)
        val trim = if (night) rgb(150, 150, 150) else Color.WHITE
        val dl = bx + bw * 0.32f
        val dr = bx + bw * 0.68f
        val dt = base - wallH * 0.72f
        p.color = trim
        p.style = Paint.Style.STROKE
        p.strokeWidth = (H * 0.006f).coerceAtLeast(1.5f)
        c.drawRect(dl, dt, dr, base, p)
        c.drawLine(dl, dt, dr, base, p)
        c.drawLine(dr, dt, dl, base, p)
        p.style = Paint.Style.FILL
        p.strokeWidth = 0f
        p.color = if (night) rgb(255, 210, 120) else rgb(250, 250, 240)
        c.drawRect(bx + bw * 0.44f, base - wallH - H * 0.06f, bx + bw * 0.56f, base - wallH - H * 0.025f, p)
        val sx = bx + bw * 1.12f
        val sw = W * 0.045f
        p.color = if (night) rgb(70, 76, 86) else rgb(196, 202, 210)
        c.drawRect(sx, base - H * 0.24f, sx + sw, base, p)
        c.drawArc(RectF(sx, base - H * 0.24f - sw / 2, sx + sw, base - H * 0.24f + sw / 2), 180f, 180f, true, p)
    }

    /** Desert: flat-topped mesas, dunes and saguaro cactus. */
    private fun desert(c: Canvas, W: Float, H: Float, night: Boolean, rnd: Random) {
        val horizon = H * 0.58f
        if (night) nightSky(c, W, horizon, rnd, W * 0.75f, H * 0.15f, H * 0.055f)
        else {
            sky(c, W, horizon, rgb(110, 176, 230), rgb(255, 216, 164))
            sun(c, W * 0.75f, H * 0.2f, H * 0.08f, rgb(255, 232, 160))
        }
        val mesa = if (night) rgb(56, 34, 48) else rgb(186, 96, 62)
        for ((mx, mw, mh) in listOf(Triple(0.08f, 0.22f, 0.2f), Triple(0.45f, 0.16f, 0.14f), Triple(0.7f, 0.26f, 0.24f))) {
            val path = Path()
            val left = W * mx
            val right = W * (mx + mw)
            val top = horizon - H * mh
            path.moveTo(left - W * 0.04f, horizon)
            path.lineTo(left, top)
            path.lineTo(right, top)
            path.lineTo(right + W * 0.04f, horizon)
            path.close()
            p.color = mesa
            c.drawPath(path, p)
            p.color = dusk(mesa, 0.82f)
            c.drawRect(left, top + H * 0.03f, right, top + H * 0.045f, p)
        }
        val sand1 = if (night) rgb(70, 52, 60) else rgb(226, 180, 120)
        val sand2 = if (night) rgb(52, 38, 48) else rgb(210, 158, 98)
        for ((base, color) in listOf(horizon to sand1, H * 0.78f to sand2)) {
            val path = Path()
            path.moveTo(0f, H)
            path.lineTo(0f, base)
            var x = 0f
            while (x < W) {
                val nx = x + W * (0.2f + rnd.nextFloat() * 0.15f)
                path.quadTo((x + nx) / 2f, base - H * (0.03f + rnd.nextFloat() * 0.05f), nx, base)
                x = nx
            }
            path.lineTo(W, H)
            path.close()
            p.color = color
            c.drawPath(path, p)
        }
        val green = if (night) rgb(20, 36, 30) else rgb(58, 120, 70)
        p.color = green
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for ((cx, size) in listOf(0.2f to 0.3f, 0.58f to 0.22f, 0.88f to 0.34f)) {
            val x0 = W * cx
            val h = H * size
            val bottom = H * 0.97f
            p.strokeWidth = h * 0.16f
            c.drawLine(x0, bottom, x0, bottom - h, p)
            p.strokeWidth = h * 0.11f
            val arm1 = Path()
            arm1.moveTo(x0, bottom - h * 0.45f)
            arm1.lineTo(x0 - h * 0.22f, bottom - h * 0.45f)
            arm1.lineTo(x0 - h * 0.22f, bottom - h * 0.75f)
            c.drawPath(arm1, p)
            val arm2 = Path()
            arm2.moveTo(x0, bottom - h * 0.6f)
            arm2.lineTo(x0 + h * 0.2f, bottom - h * 0.6f)
            arm2.lineTo(x0 + h * 0.2f, bottom - h * 0.85f)
            c.drawPath(arm2, p)
        }
        p.style = Paint.Style.FILL
        p.strokeWidth = 0f
        p.strokeCap = Paint.Cap.BUTT
    }

    /** One pine: three stacked triangles on a short trunk, base at (x, y). */
    private fun pine(c: Canvas, x: Float, y: Float, h: Float, color: Int) {
        p.color = color
        c.drawRect(x - h * 0.03f, y - h * 0.12f, x + h * 0.03f, y, p)
        val path = Path()
        for (k in 0 until 3) {
            val tierBottom = y - h * (0.1f + k * 0.26f)
            val tierTop = tierBottom - h * 0.42f
            val half = h * (0.24f - k * 0.05f)
            path.reset()
            path.moveTo(x - half, tierBottom)
            path.lineTo(x, tierTop)
            path.lineTo(x + half, tierBottom)
            path.close()
            c.drawPath(path, p)
        }
    }

    /** Forest & lake: pine shores around a calm lake with reflections. */
    private fun forest(c: Canvas, W: Float, H: Float, night: Boolean, rnd: Random) {
        val shore = H * 0.55f
        if (night) nightSky(c, W, shore, rnd, W * 0.3f, H * 0.14f, H * 0.05f)
        else {
            sky(c, W, shore, rgb(130, 190, 230), rgb(226, 240, 236))
            sun(c, W * 0.3f, H * 0.17f, H * 0.06f, rgb(255, 236, 170))
        }
        ridge(c, W, H, shore - H * 0.1f, H * 0.08f, if (night) rgb(26, 40, 58) else rgb(120, 150, 170), rnd)
        val far = if (night) rgb(12, 30, 26) else rgb(52, 100, 74)
        var x = 0f
        while (x < W) {
            pine(c, x, shore, H * (0.1f + rnd.nextFloat() * 0.06f), far)
            x += W * 0.025f
        }
        val lakeBottom = H * 0.82f
        p.shader = LinearGradient(
            0f, shore, 0f, lakeBottom,
            if (night) rgb(20, 34, 66) else rgb(90, 160, 200),
            if (night) rgb(10, 20, 44) else rgb(50, 118, 160),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, shore, W, lakeBottom, p)
        p.shader = null
        p.color = if (night) rgb(200, 210, 230) else Color.WHITE
        repeat(18) {
            val ly = shore + rnd.nextFloat() * (lakeBottom - shore)
            val lx = rnd.nextFloat() * W
            p.alpha = 40 + rnd.nextInt(60)
            c.drawRect(lx, ly, lx + W * (0.04f + rnd.nextFloat() * 0.08f), ly + H * 0.004f, p)
        }
        p.alpha = 255
        p.color = if (night) rgb(10, 22, 16) else rgb(44, 84, 50)
        c.drawRect(0f, lakeBottom, W, H, p)
        val near = if (night) rgb(6, 16, 12) else rgb(30, 70, 40)
        for (side in 0 until 2) {
            var px = if (side == 0) -W * 0.02f else W * 0.8f
            val end = if (side == 0) W * 0.2f else W * 1.02f
            while (px < end) {
                pine(c, px, H, H * (0.35f + rnd.nextFloat() * 0.2f), near)
                px += W * (0.04f + rnd.nextFloat() * 0.03f)
            }
        }
    }

    /**
     * The weather over a finished scene: a grey wash and clouds when cloudy, darker for rain and
     * storms (with a lightning bolt), a pale sky and snow on the ground when it snows. The moving
     * rain/snow is the Weather background effect's job (AmbientView).
     */
    private fun weatherSky(c: Canvas, W: Float, H: Float, mood: String, night: Boolean, rnd: Random) {
        fun wash(color: Int, topAlpha: Int, bottomAlpha: Int, bottom: Float) {
            p.shader = LinearGradient(
                0f, 0f, 0f, bottom,
                (color and 0xFFFFFF) or (topAlpha shl 24), (color and 0xFFFFFF) or (bottomAlpha shl 24),
                Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, W, bottom, p)
            p.shader = null
        }
        fun clouds(color: Int, alpha: Int, n: Int) {
            p.color = color
            p.alpha = alpha
            repeat(n) {
                val cx = rnd.nextFloat() * W
                val cy = H * (0.04f + rnd.nextFloat() * 0.22f)
                val r = H * (0.05f + rnd.nextFloat() * 0.05f)
                c.drawCircle(cx, cy, r, p)
                c.drawCircle(cx + r * 0.9f, cy + r * 0.2f, r * 0.8f, p)
                c.drawCircle(cx - r * 0.9f, cy + r * 0.25f, r * 0.7f, p)
                c.drawRect(cx - r * 1.5f, cy + r * 0.2f, cx + r * 1.6f, cy + r * 0.9f, p)
            }
            p.alpha = 255
        }
        when (mood) {
            "cloudy" -> {
                wash(if (night) rgb(40, 44, 60) else rgb(170, 178, 190), 150, 0, H * 0.75f)
                clouds(if (night) rgb(70, 76, 98) else rgb(238, 240, 244), 210, 6)
            }
            "rain" -> {
                wash(if (night) rgb(24, 28, 40) else rgb(88, 98, 114), 190, 70, H)
                clouds(if (night) rgb(46, 50, 66) else rgb(140, 148, 162), 220, 8)
            }
            "storm" -> {
                wash(if (night) rgb(14, 16, 26) else rgb(44, 48, 64), 215, 100, H)
                clouds(if (night) rgb(34, 36, 50) else rgb(84, 88, 104), 230, 9)
                val bolt = Path()
                val bx = W * 0.66f
                bolt.moveTo(bx, H * 0.18f)
                bolt.lineTo(bx - W * 0.03f, H * 0.36f)
                bolt.lineTo(bx + W * 0.005f, H * 0.35f)
                bolt.lineTo(bx - W * 0.025f, H * 0.55f)
                p.style = Paint.Style.STROKE
                p.strokeWidth = (H * 0.008f).coerceAtLeast(1.5f)
                p.color = rgb(255, 246, 190)
                p.alpha = 220
                c.drawPath(bolt, p)
                p.style = Paint.Style.FILL
                p.strokeWidth = 0f
                p.alpha = 255
            }
            "snow" -> {
                wash(if (night) rgb(60, 66, 84) else rgb(232, 236, 242), 150, 30, H)
                clouds(if (night) rgb(86, 92, 112) else rgb(246, 248, 250), 200, 6)
                val ground = Path()
                val y0 = H * 0.93f
                ground.moveTo(0f, H)
                ground.lineTo(0f, y0)
                var x = 0f
                while (x < W) {
                    val nx = x + W * (0.08f + rnd.nextFloat() * 0.08f)
                    ground.quadTo((x + nx) / 2f, y0 - H * (0.02f + rnd.nextFloat() * 0.03f), nx, y0)
                    x = nx
                }
                ground.lineTo(W, H)
                ground.close()
                p.color = if (night) rgb(190, 198, 220) else rgb(250, 252, 255)
                c.drawPath(ground, p)
                repeat(60) {
                    p.alpha = 120 + rnd.nextInt(120)
                    c.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * H * 0.9f, H * (0.003f + rnd.nextFloat() * 0.004f), p)
                }
                p.alpha = 255
            }
        }
    }

    private fun dots(c: Canvas, W: Float, H: Float, night: Boolean) {
        c.drawColor(if (night) Color.rgb(14, 14, 14) else Color.rgb(242, 242, 242))
        p.color = if (night) Color.rgb(58, 58, 58) else Color.rgb(205, 205, 205)
        val step = (H / 28f).coerceAtLeast(6f)
        val r = step * 0.16f
        var y = step / 2f
        while (y < H) {
            var x = step / 2f
            while (x < W) {
                c.drawCircle(x, y, r, p)
                x += step
            }
            y += step
        }
    }
}
