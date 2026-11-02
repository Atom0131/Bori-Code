package com.bori.core.keyboard.layout

import android.graphics.RectF

class PlacedKey(val key: Key, val visual: RectF, val hit: RectF)

/**
 * Pixel geometry for a layout at a given view size. Rebuilt whenever the view is resized
 * (rotation, fold/unfold, height setting), so hit-testing never uses stale rectangles.
 * Hit rectangles tile the whole keyboard: edge keys absorb padding and row offsets, so
 * there are no dead zones.
 *
 * With a [splitGap] (big screens) each row is cut at the keyboard's middle and the right half
 * moves over by the gap, so each thumb gets phone-sized keys. A key that straddles the middle
 * stays on the left (g, v), except the space bar, which stretches across the gap. The gap
 * itself is shared between the two keys beside it, so it is not a dead zone either.
 */
class KeyGeometry(
    val layout: KeyboardLayout,
    val width: Int,
    val height: Int,
    padH: Float,
    padV: Float,
    gapH: Float,
    gapV: Float,
    splitGap: Float = 0f,
) {
    val rows: List<List<PlacedKey>>
    val keys: List<PlacedKey>
    val rowHeight: Float
    private val padTop = padV

    init {
        val rowCount = layout.rows.size.coerceAtLeast(1)
        rowHeight = (height - 2 * padV) / rowCount
        val maxUnits = layout.rows.maxOfOrNull { r -> r.sumOf { it.width.toDouble() } }?.toFloat() ?: 1f
        val unit = (width - 2 * padH - splitGap) / maxUnits
        val mid = maxUnits / 2f
        rows = layout.rows.mapIndexed { ri, row ->
            val total = row.sumOf { it.width.toDouble() }.toFloat()
            // Slot edges in pixels, before the gaps between keys are taken off.
            var u = (maxUnits - total) / 2f
            val slots = row.map { key ->
                val s = u
                val e = u + key.width
                u = e
                var l = padH + s * unit
                var r = padH + e * unit
                if (splitGap > 0f) {
                    if (key.kind == KeyKind.SPACE && s < mid && e > mid) {
                        r += splitGap
                    } else if ((s + e) / 2f > mid) {
                        l += splitGap
                        r += splitGap
                    }
                }
                floatArrayOf(l, r)
            }
            val top = padV + ri * rowHeight
            row.mapIndexed { ki, key ->
                val (l, r) = slots[ki]
                val visual = RectF(l + gapH / 2, top + gapV / 2, r - gapH / 2, top + rowHeight - gapV / 2)
                val hit = RectF(
                    if (ki == 0) 0f else (slots[ki - 1][1] + l) / 2f,
                    if (ri == 0) 0f else top,
                    if (ki == row.lastIndex) width.toFloat() else (r + slots[ki + 1][0]) / 2f,
                    if (ri == rowCount - 1) height.toFloat() else top + rowHeight,
                )
                PlacedKey(key, visual, hit)
            }
        }
        keys = rows.flatten()
    }

    fun keyAt(x: Float, y: Float): PlacedKey? {
        if (rows.isEmpty()) return null
        val ri = ((y - padTop) / rowHeight).toInt().coerceIn(0, rows.lastIndex)
        val row = rows[ri]
        return row.firstOrNull { x >= it.hit.left && x < it.hit.right }
            ?: row.minByOrNull { kotlin.math.abs(it.visual.centerX() - x) }
    }
}
