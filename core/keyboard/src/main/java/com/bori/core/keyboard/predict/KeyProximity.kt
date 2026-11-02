package com.bori.core.keyboard.predict

import com.bori.core.keyboard.layout.KeyCode
import com.bori.core.keyboard.layout.KeyGeometry
import com.bori.core.keyboard.layout.KeyKind

/**
 * Where each letter sits on the current keyboard, so corrections can tell a near-miss
 * ("r" for "e") from an unrelated letter. Rebuilt whenever key geometry changes.
 */
class KeyProximity(geometry: KeyGeometry) {
    private val cx = HashMap<Char, Float>()
    private val cy = HashMap<Char, Float>()
    val keyWidth: Float
    val keyHeight: Float

    init {
        var w = 0f
        var n = 0
        for (pk in geometry.keys) {
            val code = pk.key.code
            if (pk.key.kind != KeyKind.LETTER || code !is KeyCode.Text || code.text.length != 1) continue
            val c = code.text[0].lowercaseChar()
            cx[c] = pk.visual.centerX()
            cy[c] = pk.visual.centerY()
            w += pk.hit.width()
            n++
        }
        // Key size = spacing between neighbouring letter centres. Edge keys' hit areas are
        // wider than the keys, so averaging hit widths would overstate it. The median ignores
        // the jump across a split keyboard's middle.
        val spacings = ArrayList<Float>()
        for (row in geometry.rows) {
            val xs = row.filter { it.key.kind == KeyKind.LETTER }.map { it.visual.centerX() }.sorted()
            for (i in 1 until xs.size) spacings.add(xs[i] - xs[i - 1])
        }
        spacings.sort()
        keyWidth = when {
            spacings.isNotEmpty() -> spacings[spacings.size / 2]
            n > 0 -> w / n
            else -> 1f
        }
        keyHeight = geometry.rowHeight.coerceAtLeast(1f)
    }

    fun hasKey(c: Char): Boolean = cx.containsKey(c)

    /** Every letter that has its own key. */
    val letters: Set<Char> get() = cx.keys
    fun centerX(c: Char): Float = cx[c] ?: Float.NaN
    fun centerY(c: Char): Float = cy[c] ?: Float.NaN

    /** Distance from (x, y) to the centre of [c]'s key, in key sizes; NaN if [c] has no key. */
    fun distance(x: Float, y: Float, c: Char): Float {
        val kx = cx[c] ?: return Float.NaN
        val ky = cy[c] ?: return Float.NaN
        val dx = (x - kx) / keyWidth
        val dy = (y - ky) / keyHeight
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
