package com.bori.core.keyboard.swipe

import com.bori.core.keyboard.predict.KeyProximity
import java.text.Normalizer
import java.util.Random

/**
 * Generates plausible human swipes for a word (used by the Swipe Test Lab): aim near each
 * key with gaussian error, cut corners with a moving average, add jitter. Mirrors
 * synth_path() in tools/glide_sim.py.
 */
class SyntheticSwipe(private val prox: KeyProximity, seed: Long = 7) {
    private val rng = Random(seed)

    /**
     * Returns (xs, ys) in view pixels, or null if the word has no keys. [realistic] adds what
     * real fingers do: a per-swipe offset, sloppier aim, overshoot at the end and variable
     * corner cutting (synth_real() in tools/glide_sim.py).
     */
    fun pathFor(word: String, realistic: Boolean = false): Pair<FloatArray, FloatArray>? {
        val kw = prox.keyWidth
        val kh = prox.keyHeight
        val keys = ArrayList<Char>()
        for (c in word.lowercase()) {
            val k = if (prox.hasKey(c)) c else base(c)
            if (prox.hasKey(k) && (keys.isEmpty() || keys.last() != k)) keys.add(k)
        }
        if (keys.isEmpty()) return null
        val bx = if (realistic) gauss(0.15f) else 0f
        val by = if (realistic) gauss(0.15f) else 0f
        val noise = if (realistic) 0.2f + rng.nextFloat() * 0.15f else 0.22f
        val ax = ArrayList<Float>()
        val ay = ArrayList<Float>()
        for (k in keys) {
            ax.add(prox.centerX(k) / kw + bx + gauss(noise))
            ay.add(prox.centerY(k) / kh + by + gauss(noise * 0.8f))
        }
        if (ax.size == 1) {
            ax.add(ax[0] + 0.3f)
            ay.add(ay[0])
        }
        if (realistic) {
            // Overshoot past the last key in the direction of travel.
            val i = ax.size - 1
            val dx = ax[i] - ax[i - 1]
            val dy = ay[i] - ay[i - 1]
            val dl = kotlin.math.hypot(dx, dy).takeIf { it > 0f } ?: 1f
            val over = rng.nextFloat() * 0.4f
            ax[i] = ax[i] + dx / dl * over
            ay[i] = ay[i] + dy / dl * over
        }
        val smooth = if (realistic) 2 + rng.nextInt(5) else 3
        val jitter = if (realistic) 0.04f else 0.03f
        val px = ArrayList<Float>()
        val py = ArrayList<Float>()
        for (i in 0 until ax.size - 1) {
            val d = kotlin.math.hypot(ax[i + 1] - ax[i], ay[i + 1] - ay[i])
            val n = maxOf(2, (d / 0.08f).toInt())
            for (s in 0 until n) {
                val t = s / n.toFloat()
                px.add(ax[i] + (ax[i + 1] - ax[i]) * t)
                py.add(ay[i] + (ay[i + 1] - ay[i]) * t)
            }
        }
        px.add(ax.last())
        py.add(ay.last())
        val n = px.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val lo = maxOf(0, i - smooth)
            val hi = minOf(n, i + smooth + 1)
            var sx = 0f
            var sy = 0f
            for (j in lo until hi) { sx += px[j]; sy += py[j] }
            xs[i] = (sx / (hi - lo) + gauss(jitter)) * kw
            ys[i] = (sy / (hi - lo) + gauss(jitter)) * kh
        }
        return xs to ys
    }

    private fun gauss(sigma: Float) = (rng.nextGaussian() * sigma).toFloat()

    private fun base(c: Char): Char {
        val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        return if (d.isEmpty()) c else d[0].lowercaseChar()
    }
}
