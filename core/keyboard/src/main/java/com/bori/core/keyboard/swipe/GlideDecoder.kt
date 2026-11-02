package com.bori.core.keyboard.swipe

import com.bori.core.keyboard.dict.BinaryDictionary
import com.bori.core.keyboard.predict.KeyProximity
import java.text.Normalizer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a swipe across the keys into likely words. Our own geometric decoder:
 *  1. Candidates: walk the dictionary trie, keeping only words whose first letter is near the
 *     start of the swipe, whose last letter is near its end, and whose other letters the swipe
 *     passes near, in order. "Next index where the path comes near key c" is precomputed, so
 *     each trie step is O(1).
 *  2. Geometry: compare the swipe with each candidate's ideal path (key centre to key centre),
 *     both resampled to the same number of points, by position and by normalised shape.
 *  3. Rescore the best by the language model (previous word, user history).
 * Coordinates are in key units (x / key width, y / key height), like [KeyProximity.distance].
 *
 * Constants are mirrored in tools/glide_sim.py, which is used to tune them; keep in sync.
 * Simulated accuracy (tools/glide_sim.py), top-1 / top-3:
 *   clean swipes, commonest words: en 91% / 99%, es 90.5% / 99%
 *   realistic swipes (finger offset, sloppy aim, overshoot): en 85.5% / 96%, es 84% / 95.5%
 */
class GlideDecoder(private val dict: BinaryDictionary) {

    class Result(val node: Int, val score: Float)

    private val baseCache = HashMap<Char, Char>()

    /**
     * Decodes a swipe given in view pixels. [languageScore] ranks words by context and
     * [isBlocked] filters words that must not be offered.
     */
    fun decode(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        prox: KeyProximity,
        languageScore: (Int) -> Float,
        isBlocked: (Int) -> Boolean,
        limit: Int = 6,
        /** Learned finger offset per key, in key units (see SwipeAdaptation). */
        offsetX: (Char) -> Float = { 0f },
        offsetY: (Char) -> Float = { 0f },
    ): List<Result> {
        if (count < 2) return emptyList()
        val kw = prox.keyWidth
        val kh = prox.keyHeight
        // Key layout in key units.
        val letters = prox.letters.toCharArray()
        if (letters.isEmpty()) return emptyList()
        val keyIndex = HashMap<Char, Int>(letters.size * 2)
        val kx = FloatArray(letters.size)
        val ky = FloatArray(letters.size)
        letters.forEachIndexed { i, c ->
            keyIndex[c] = i
            kx[i] = prox.centerX(c) / kw + offsetX(c)
            ky[i] = prox.centerY(c) / kh + offsetY(c)
        }

        val path = thin(xs, ys, count, kw, kh)
        val px = path.first
        val py = path.second
        val p = px.size
        // A finger that runs off the edge (past "a", "q", "p") still means the edge key.
        val minX = kx.min() - EDGE_MARGIN
        val maxX = kx.max() + EDGE_MARGIN
        val minY = ky.min() - EDGE_MARGIN
        val maxY = ky.max() + EDGE_MARGIN
        for (i in 0 until p) {
            px[i] = px[i].coerceIn(minX, maxX)
            py[i] = py[i].coerceIn(minY, maxY)
        }

        // nextNear[k][j]: first path index >= j within RADIUS of key k, or p if none.
        val nextNear = Array(letters.size) { k ->
            val arr = IntArray(p + 1) { p }
            for (j in p - 1 downTo 0) {
                arr[j] = if (hypot(px[j] - kx[k], py[j] - ky[k]) <= RADIUS) j else arr[j + 1]
            }
            arr
        }

        val candidates = ArrayList<Int>()
        val search = Search(letters.size, keyIndex, kx, ky, nextNear, px[0], py[0], px[p - 1], py[p - 1], p, candidates)
        search.visit(BinaryDictionary.ROOT, 0, -1, 0)

        // Geometry.
        val userX = FloatArray(RESAMPLE)
        val userY = FloatArray(RESAMPLE)
        resample(px, py, p, userX, userY)
        val userNX = FloatArray(RESAMPLE)
        val userNY = FloatArray(RESAMPLE)
        normalize(userX, userY, userNX, userNY)
        val userLen = pathLength(px, py, p)

        val idealX = FloatArray(MAX_WORD)
        val idealY = FloatArray(MAX_WORD)
        val rsX = FloatArray(RESAMPLE)
        val rsY = FloatArray(RESAMPLE)
        val nX = FloatArray(RESAMPLE)
        val nY = FloatArray(RESAMPLE)
        val geo = ArrayList<Result>(candidates.size)
        for (node in candidates) {
            if (isBlocked(node)) continue
            val n = idealPath(node, keyIndex, kx, ky, idealX, idealY)
            if (n == 0) continue
            resample(idealX, idealY, n, rsX, rsY)
            var loc = 0f
            for (i in 0 until RESAMPLE) loc += hypot(userX[i] - rsX[i], userY[i] - rsY[i])
            loc /= RESAMPLE
            var shape = 0f
            if (n > 1 && userLen > 1f) {
                normalize(rsX, rsY, nX, nY)
                for (i in 0 until RESAMPLE) shape += hypot(userNX[i] - nX[i], userNY[i] - nY[i])
                shape /= RESAMPLE
            }
            // The start and end of a swipe are its most reliable points ("food" vs "did").
            val ends = hypot(userX[0] - idealX[0], userY[0] - idealY[0]) +
                hypot(userX[RESAMPLE - 1] - idealX[n - 1], userY[RESAMPLE - 1] - idealY[n - 1])
            geo.add(Result(node, -(W_LOCATION * loc + W_SHAPE * shape + W_ENDS * ends)))
        }
        geo.sortByDescending { it.score }
        return geo.asSequence()
            .take(MAX_GEO_KEEP)
            .map { Result(it.node, GEO_WEIGHT * it.score + languageScore(it.node)) }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    /** Trie walk that collects candidate words (see class comment). */
    private inner class Search(
        val keys: Int,
        val keyIndex: Map<Char, Int>,
        val kx: FloatArray,
        val ky: FloatArray,
        val nextNear: Array<IntArray>,
        val sx: Float,
        val sy: Float,
        val ex: Float,
        val ey: Float,
        val p: Int,
        val out: MutableList<Int>,
    ) {
        fun visit(node: Int, idx: Int, lastKey: Int, depth: Int) {
            val start = dict.childStart(node)
            if (start < 0) return
            for (child in start until start + dict.childCount(node)) {
                val k = keyOf(dict.charAt(child), keyIndex)
                var j: Int
                var key: Int
                when {
                    k < 0 -> { j = idx; key = lastKey } // apostrophe, hyphen: no key, free
                    k == lastKey -> { j = idx; key = k } // doubled letter: no movement needed
                    depth == 0 -> {
                        if (hypot(kx[k] - sx, ky[k] - sy) > RADIUS_ENDS) continue
                        j = 0; key = k
                    }
                    else -> {
                        j = nextNear[k][idx]
                        if (j >= p) continue
                        key = k
                    }
                }
                // Rare words (no corpus frequency) are typed, never swiped.
                if (depth >= 1 && key >= 0 && dict.isWord(child) && !dict.isOffensive(child) && !dict.isRare(child) &&
                    hypot(kx[key] - ex, ky[key] - ey) <= RADIUS_ENDS
                ) {
                    out.add(child)
                }
                if (depth < MAX_WORD - 1 && dict.childCount(child) > 0) visit(child, j, key, depth + 1)
            }
        }
    }

    /** Key for a trie char: its own key, else its accent-free base's key, else -1. */
    private fun keyOf(c: Char, keyIndex: Map<Char, Int>): Int {
        keyIndex[c]?.let { return it }
        val base = baseCache.getOrPut(c) {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            if (d.isEmpty()) c else d[0].lowercaseChar()
        }
        return keyIndex[base] ?: -1
    }

    private fun idealPath(node: Int, keyIndex: Map<Char, Int>, kx: FloatArray, ky: FloatArray, outX: FloatArray, outY: FloatArray): Int {
        val word = dict.keyOf(node)
        var n = 0
        var last = -1
        for (c in word) {
            val k = keyOf(c, keyIndex)
            if (k < 0 || k == last) continue
            if (n == outX.size) break
            outX[n] = kx[k]
            outY[n] = ky[k]
            n++
            last = k
        }
        return n
    }

    companion object {
        const val RESAMPLE = 40
        const val RADIUS = 1.3f
        const val RADIUS_ENDS = 1.3f
        const val W_LOCATION = 6.0f
        const val W_SHAPE = 5.0f
        const val W_ENDS = 2.0f
        const val GEO_WEIGHT = 2.0f
        const val MAX_GEO_KEEP = 80
        const val STEP = 0.25f
        const val MAX_WORD = 24
        /** Path points are clamped to the key area plus this margin (key units). */
        const val EDGE_MARGIN = 0.5f

        /** Converts to key units and keeps one point per [STEP] key sizes. */
        fun thin(xs: FloatArray, ys: FloatArray, count: Int, kw: Float, kh: Float): Pair<FloatArray, FloatArray> {
            val ox = ArrayList<Float>(count)
            val oy = ArrayList<Float>(count)
            ox.add(xs[0] / kw)
            oy.add(ys[0] / kh)
            for (i in 1 until count) {
                val x = xs[i] / kw
                val y = ys[i] / kh
                if (hypot(x - ox.last(), y - oy.last()) >= STEP) {
                    ox.add(x)
                    oy.add(y)
                }
            }
            val lx = xs[count - 1] / kw
            val ly = ys[count - 1] / kh
            if (ox.last() != lx || oy.last() != ly) {
                ox.add(lx)
                oy.add(ly)
            }
            return ox.toFloatArray() to oy.toFloatArray()
        }

        /** Resamples a polyline of [n] points to [outX].size evenly spaced points. */
        fun resample(xs: FloatArray, ys: FloatArray, n: Int, outX: FloatArray, outY: FloatArray) {
            val m = outX.size
            if (n == 1) {
                outX.fill(xs[0]); outY.fill(ys[0]); return
            }
            val total = pathLength(xs, ys, n)
            if (total == 0f) {
                outX.fill(xs[0]); outY.fill(ys[0]); return
            }
            val step = total / (m - 1)
            var i = 0
            var acc = 0f
            var seg = hypot(xs[1] - xs[0], ys[1] - ys[0])
            for (k in 0 until m) {
                val target = k * step
                while (i < n - 2 && acc + seg < target) {
                    acc += seg
                    i++
                    seg = hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
                }
                val t = if (seg == 0f) 0f else min(1f, (target - acc) / seg)
                outX[k] = xs[i] + (xs[i + 1] - xs[i]) * t
                outY[k] = ys[i] + (ys[i + 1] - ys[i]) * t
            }
        }

        /** Centres on the centroid and scales the larger bounding-box side to 1. */
        fun normalize(xs: FloatArray, ys: FloatArray, outX: FloatArray, outY: FloatArray) {
            var cx = 0f
            var cy = 0f
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in xs.indices) {
                cx += xs[i]; cy += ys[i]
                minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
                minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
            }
            cx /= xs.size
            cy /= xs.size
            val s = max(max(maxX - minX, maxY - minY), 1e-6f)
            for (i in xs.indices) {
                outX[i] = (xs[i] - cx) / s
                outY[i] = (ys[i] - cy) / s
            }
        }

        fun pathLength(xs: FloatArray, ys: FloatArray, n: Int): Float {
            var l = 0f
            for (i in 0 until n - 1) l += hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
            return l
        }
    }
}
