package com.bori.core.keyboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.bori.core.keyboard.theme.KeyboardTheme

/**
 * Transparent layer over the suggestion strip and keyboard. Key previews and long-press
 * popups are drawn here so they can rise above the top key row without a PopupWindow.
 * It never takes touches; the keyboard view keeps receiving the pointer stream.
 */
class KeyOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private class Preview(val rect: RectF, val label: String, val theme: KeyboardTheme)

    class Popup internal constructor(
        val rect: RectF,
        val cellWidth: Float,
        val items: List<String>,
        val theme: KeyboardTheme,
        /** Offset from the source view's coordinates to the overlay's. */
        val dx: Float,
        /** Cell of the default item, and the pressed key's centre (overlay x) it belongs to. */
        private val home: Int,
        private val anchorX: Float,
        var selected: Int = home,
    ) {
        /** The cell the finger points at; it moves one cell per key width slid from the key. */
        internal fun cellFor(x: Float): Int =
            (home + kotlin.math.floor((x - anchorX) / cellWidth + 0.5f).toInt()).coerceIn(0, items.lastIndex)

        fun selectedText(): String? = items.getOrNull(selected)
    }

    private val previews = HashMap<Int, Preview>()
    private var popup: Popup? = null

    // Glide trail: points live in the source view's arrays; we only keep a reference.
    private var trailX: FloatArray? = null
    private var trailY: FloatArray? = null
    private var trailCount = 0
    private var trailDx = 0f
    private var trailDy = 0f
    private var trailColor = 0
    private var trailFadeStart = 0L
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        isClickable = false
        isFocusable = false
    }

    /**
     * Never ask for height: a plain View takes all it is offered, which would stretch the
     * keyboard window to full screen. The parent FrameLayout re-measures this view at the
     * keyboard's exact size afterwards.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            0
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private fun offsetOf(source: View): Pair<Float, Float> {
        val a = IntArray(2)
        val b = IntArray(2)
        source.getLocationInWindow(a)
        getLocationInWindow(b)
        return (a[0] - b[0]).toFloat() to (a[1] - b[1]).toFloat()
    }

    fun showPreview(source: View, pointerId: Int, key: RectF, label: String, theme: KeyboardTheme) {
        val (dx, dy) = offsetOf(source)
        val w = maxOf(key.width() * 1.25f, 44 * density)
        val h = key.height() * 1.15f
        val cx = (key.centerX() + dx).coerceIn(w / 2, width - w / 2)
        val bottom = key.top + dy + key.height() * 0.2f
        previews[pointerId] = Preview(RectF(cx - w / 2, bottom - h, cx + w / 2, bottom), label, theme)
        invalidate()
    }

    fun hidePreview(pointerId: Int) {
        if (previews.remove(pointerId) != null) invalidate()
    }

    fun showPopup(source: View, key: RectF, items: List<String>, theme: KeyboardTheme): Popup {
        val (dx, dy) = offsetOf(source)
        val cell = minOf(maxOf(key.width(), 36 * density), width / items.size.coerceAtLeast(1).toFloat())
        val total = cell * items.size
        val anchor = key.centerX() + dx
        // Start the row at the pressed key so the default (first) item sits under the finger.
        // Near the right edge it grows leftwards instead (reversed), keeping the default there.
        val reversed = anchor - cell / 2 + total > width
        val shown = if (reversed) items.asReversed() else items
        val home = if (reversed) shown.lastIndex else 0
        val left = (anchor + cell / 2 - (home + 1) * cell).coerceIn(0f, (width - total).coerceAtLeast(0f))
        val h = key.height() * 1.05f
        val bottom = key.top + dy + key.height() * 0.1f
        val p = Popup(RectF(left, bottom - h, left + total, bottom), cell, shown, theme, dx, home, anchor)
        popup = p
        previews.clear()
        invalidate()
        return p
    }

    fun updatePopupSelection(p: Popup, sourceX: Float) {
        val idx = p.cellFor(sourceX + p.dx)
        if (idx != p.selected) {
            p.selected = idx
            invalidate()
        }
    }

    fun hidePopup() {
        popup = null
        invalidate()
    }

    fun hideAll() {
        previews.clear()
        popup = null
        trailX = null
        invalidate()
    }

    fun setTrail(source: View, xs: FloatArray, ys: FloatArray, count: Int, color: Int) {
        val (dx, dy) = offsetOf(source)
        trailX = xs
        trailY = ys
        trailCount = count
        trailDx = dx
        trailDy = dy
        trailColor = color
        trailFadeStart = 0L
        invalidate()
    }

    /** Fades the trail out after the finger lifts. */
    fun fadeTrail() {
        if (trailX == null) return
        trailFadeStart = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    private fun drawTrail(canvas: Canvas) {
        val xs = trailX ?: return
        val ys = trailY ?: return
        var fade = 1f
        if (trailFadeStart != 0L) {
            fade = 1f - (android.os.SystemClock.uptimeMillis() - trailFadeStart) / TRAIL_FADE_MS.toFloat()
            if (fade <= 0f) {
                trailX = null
                return
            }
            postInvalidateOnAnimation()
        }
        val n = trailCount
        val first = maxOf(1, n - TRAIL_POINTS)
        val stroke = 5 * density
        for (i in first until n) {
            // Older segments are thinner and more transparent.
            val t = (i - first + 1) / (n - first).toFloat()
            trailPaint.color = trailColor
            trailPaint.alpha = (255 * fade * (0.25f + 0.75f * t)).toInt().coerceIn(0, 255)
            trailPaint.strokeWidth = stroke * (0.4f + 0.6f * t)
            canvas.drawLine(xs[i - 1] + trailDx, ys[i - 1] + trailDy, xs[i] + trailDx, ys[i] + trailDy, trailPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawTrail(canvas)
        val radius = 10 * density
        for (pv in previews.values) {
            drawBubble(canvas, pv.rect, radius, pv.theme.previewBackground)
            textPaint.color = pv.theme.previewText
            textPaint.textSize = pv.rect.height() * 0.5f
            drawCentered(canvas, pv.label, pv.rect.centerX(), pv.rect.centerY())
        }
        val p = popup ?: return
        drawBubble(canvas, p.rect, radius, p.theme.previewBackground)
        textPaint.textSize = p.rect.height() * 0.42f
        p.items.forEachIndexed { i, s ->
            val l = p.rect.left + i * p.cellWidth
            val cell = RectF(l, p.rect.top, l + p.cellWidth, p.rect.bottom)
            if (i == p.selected) {
                selPaint.color = p.theme.accent
                canvas.drawRoundRect(cell.apply { inset(3 * density, 3 * density) }, radius, radius, selPaint)
                textPaint.color = p.theme.onAccent
            } else {
                textPaint.color = p.theme.previewText
            }
            if (s.length > 2) textPaint.textSize = p.rect.height() * 0.26f
            drawCentered(canvas, s, cell.centerX(), cell.centerY())
            textPaint.textSize = p.rect.height() * 0.42f
        }
    }

    private companion object {
        const val TRAIL_POINTS = 120
        const val TRAIL_FADE_MS = 250L
    }

    private fun drawBubble(canvas: Canvas, r: RectF, radius: Float, color: Int) {
        shadowPaint.color = 0x33000000
        canvas.drawRoundRect(r.left, r.top + 2 * density, r.right, r.bottom + 2 * density, radius, radius, shadowPaint)
        bgPaint.color = color
        canvas.drawRoundRect(r, radius, radius, bgPaint)
    }

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float) {
        canvas.drawText(text, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
    }
}
