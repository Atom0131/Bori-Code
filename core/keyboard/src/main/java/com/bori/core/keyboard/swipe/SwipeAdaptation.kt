package com.bori.core.keyboard.swipe

import com.bori.core.keyboard.predict.KeyProximity
import java.text.Normalizer
import kotlin.math.hypot

/**
 * Learns where this user's finger really lands relative to each key. Given a swipe and the
 * word it was meant to be, it finds where the path passed each letter's key and returns the
 * offsets (in key units). [GlideDecoder] shifts key centres by the averaged offsets.
 */
object SwipeAdaptation {

    /** Offsets further than this from the key are treated as noise, not habit. */
    private const val MAX_OFFSET = 0.8f

    fun samples(prox: KeyProximity, xs: FloatArray, ys: FloatArray, count: Int, word: String): List<Triple<Char, Float, Float>> {
        if (count < 2) return emptyList()
        val keys = ArrayList<Char>()
        for (c in word.lowercase()) {
            val k = when {
                prox.hasKey(c) -> c
                else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull()?.lowercaseChar()
            } ?: continue
            if (prox.hasKey(k) && (keys.isEmpty() || keys.last() != k)) keys.add(k)
        }
        if (keys.size < 2) return emptyList()
        val kw = prox.keyWidth
        val kh = prox.keyHeight
        val out = ArrayList<Triple<Char, Float, Float>>(keys.size)
        var from = 0
        for ((ki, k) in keys.withIndex()) {
            val cx = prox.centerX(k) / kw
            val cy = prox.centerY(k) / kh
            // The ends are where the finger went down and up; inner letters are the closest pass.
            val idx = when (ki) {
                0 -> 0
                keys.lastIndex -> count - 1
                else -> {
                    var best = from
                    var bestD = Float.MAX_VALUE
                    for (j in from until count) {
                        val d = hypot(xs[j] / kw - cx, ys[j] / kh - cy)
                        if (d < bestD) { bestD = d; best = j }
                    }
                    best
                }
            }
            val dx = xs[idx] / kw - cx
            val dy = ys[idx] / kh - cy
            if (hypot(dx, dy) <= MAX_OFFSET) out.add(Triple(k, dx, dy))
            from = idx
        }
        return out
    }
}
