package com.bori.core.keyboard.predict

import com.bori.core.keyboard.dict.BinaryDictionary
import java.text.Normalizer
import java.util.PriorityQueue

/**
 * Fuzzy dictionary search: a weighted Damerau–Levenshtein walk over the trie, pruned as soon
 * as every alignment of a branch is too expensive. Substitution costs come from where the
 * user actually touched ([KeyProximity]), so "r" for "e" is cheap and "p" for "e" is not.
 * Accents, apostrophes and doubled letters are cheap edits ("cancion" → "canción",
 * "dont" → "don't", "adress" → "address").
 *
 * Constants are mirrored in tools/suggest_sim.py, which is used to tune them; keep in sync.
 * Not thread-safe: one instance per suggester, used from the main thread.
 */
class SpellCorrector(private val dict: BinaryDictionary) {

    /** Full-word matches: node → edit cost. */
    val matches = HashMap<Int, Float>()
    /** Nodes whose path matches the typed text as a prefix: node → edit cost. */
    val prefixes = HashMap<Int, Float>()

    private var typed = CharArray(0)
    private var typedBase = CharArray(0)
    private var m = 0
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var prox: KeyProximity? = null
    private var limit = 0f
    private var prefixLimit = 0f
    private var maxDepth = 0
    private var rows = Array(0) { FloatArray(0) }
    private val path = CharArray(MAX_DEPTH + 2)
    private val baseCache = HashMap<Char, Char>()

    /**
     * Searches for [input] (lowercase). Results land in [matches] and [prefixes].
     * [maxCostOverride] (if >= 0) replaces the length-based limit; [tapOffset] is where
     * [input] starts inside [composer] (for fragments of a word with a missed space).
     */
    fun search(
        input: String,
        composer: Composer?,
        proximity: KeyProximity?,
        maxCostOverride: Float = -1f,
        tapOffset: Int = 0,
    ) {
        matches.clear()
        prefixes.clear()
        prepare(input, composer, proximity, tapOffset)
        if (m == 0 || m > MAX_DEPTH - 3) return
        limit = if (maxCostOverride >= 0f) maxCostOverride else maxCost(m)
        prefixLimit = minOf(limit, 1f)
        maxDepth = m + 3
        rows = Array(maxDepth + 1) { FloatArray(m + 1) }
        val row0 = rows[0]
        row0[0] = 0f
        for (i in 1..m) row0[i] = row0[i - 1] + delCost(i - 1)
        visit(BinaryDictionary.ROOT, 0)
    }

    /** Edit cost from the prepared input to an arbitrary [word] (used for learned words). */
    fun costTo(word: String): Float {
        val n = word.length
        val r = Array(n + 1) { FloatArray(m + 1) }
        for (i in 1..m) r[0][i] = r[0][i - 1] + delCost(i - 1)
        for (d in 1..n) {
            val c = word[d - 1]
            val pchar = if (d >= 2) word[d - 2] else '\u0000'
            fill(r[d], r[d - 1], if (d >= 2) r[d - 2] else null, c, pchar)
        }
        return r[n][m]
    }

    /** Sets the input for [costTo] without running a trie search. */
    fun prepare(input: String, composer: Composer?, proximity: KeyProximity?, tapOffset: Int = 0) {
        m = input.length
        typed = input.toCharArray()
        typedBase = CharArray(m) { baseOf(typed[it]) }
        xs = FloatArray(m) { composer?.takeIf { c -> tapOffset + it < c.length }?.x(tapOffset + it) ?: Float.NaN }
        ys = FloatArray(m) { composer?.takeIf { c -> tapOffset + it < c.length }?.y(tapOffset + it) ?: Float.NaN }
        prox = proximity
    }

    private fun visit(node: Int, depth: Int) {
        val start = dict.childStart(node)
        if (start < 0) return
        val prevRow = rows[depth]
        val prev2 = if (depth >= 1) rows[depth - 1] else null
        val pchar = if (depth >= 1) path[depth] else '\u0000'
        val row = rows[depth + 1]
        for (child in start until start + dict.childCount(node)) {
            val c = dict.charAt(child)
            val rowMin = fill(row, prevRow, prev2, c, pchar)
            if (rowMin > limit) continue
            val full = row[m]
            if (full <= limit && dict.isWord(child)) {
                val old = matches[child]
                if (old == null || full < old) matches[child] = full
            }
            if ((depth + 1 == m || depth + 1 == m - 1) && full <= prefixLimit) {
                val old = prefixes[child]
                if (old == null || full < old) prefixes[child] = full
            }
            if (depth + 1 < maxDepth) {
                path[depth + 1] = c
                visit(child, depth + 1)
            }
        }
    }

    /** Fills one DP row for candidate char [c]; returns the row minimum. */
    private fun fill(row: FloatArray, prevRow: FloatArray, prev2: FloatArray?, c: Char, pchar: Char): Float {
        val ins = insCost(c, pchar)
        row[0] = prevRow[0] + ins
        var min = row[0]
        for (i in 1..m) {
            var v = prevRow[i - 1] + subCost(i - 1, c)
            val a = prevRow[i] + ins
            if (a < v) v = a
            val b = row[i - 1] + delCost(i - 1)
            if (b < v) v = b
            if (prev2 != null && i >= 2 && typed[i - 1] == pchar && typed[i - 2] == c) {
                val t = prev2[i - 2] + TRANSPOSE
                if (t < v) v = t
            }
            row[i] = v
            if (v < min) min = v
        }
        return min
    }

    private fun subCost(i: Int, c: Char): Float {
        val t = typed[i]
        if (c == t) return 0f
        val extra = if (i == 0) SUB_FIRST_EXTRA else 0f
        val bc = baseOf(c)
        if (bc == typedBase[i]) return SUB_ACCENT + extra
        val p = prox ?: return 1f + extra
        var x = xs[i]
        var y = ys[i]
        if (x.isNaN() || y.isNaN()) {
            x = p.centerX(typedBase[i])
            y = p.centerY(typedBase[i])
        }
        if (x.isNaN()) return 1f + extra
        val d = p.distance(x, y, bc)
        if (d.isNaN()) return 1f + extra
        return (0.35f + 0.4f * d).coerceIn(0.45f, 1.1f) + extra
    }

    private fun insCost(c: Char, pchar: Char): Float = when {
        c == '\'' -> INS_APOSTROPHE
        c == pchar -> INS_DOUBLE
        else -> INS_OTHER
    }

    private fun delCost(i: Int): Float = when {
        typed[i] == '\'' -> DEL_APOSTROPHE
        i > 0 && typed[i] == typed[i - 1] -> DEL_DOUBLE
        i == 0 -> DEL_OTHER + DEL_FIRST_EXTRA
        else -> DEL_OTHER
    }

    private fun baseOf(c: Char): Char {
        if (c in 'a'..'z') return c
        return baseCache.getOrPut(c) {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            if (d.isEmpty()) c else d[0].lowercaseChar()
        }
    }

    /**
     * Up to [k] most frequent suggestible words below [node] that are longer than
     * [minDepth] characters, best first. Calls [block] with (wordNode, depth).
     */
    fun topDescendants(node: Int, depth: Int, k: Int, minDepth: Int, block: (Int, Int) -> Unit) {
        val queue = PriorityQueue<LongArray>(16) { a, b -> java.lang.Long.compare(b[0], a[0]) }
        // Entry: [priority, node, depth, isWord]; words sort ahead of subtrees of equal priority.
        queue.add(longArrayOf(dict.maxFreqOf(node).toLong() shl 1, node.toLong(), depth.toLong(), 0))
        var found = 0
        while (found < k) {
            val e = queue.poll() ?: break
            val n = e[1].toInt()
            val dep = e[2].toInt()
            if (e[3] == 1L) {
                block(n, dep)
                found++
                continue
            }
            if (dict.isWord(n) && !dict.isOffensive(n) && dep > minDepth) {
                queue.add(longArrayOf((dict.freqOf(n).toLong() shl 1) or 1, n.toLong(), dep.toLong(), 1))
            }
            val start = dict.childStart(n)
            if (start < 0) continue
            for (ch in start until start + dict.childCount(n)) {
                val mf = dict.maxFreqOf(ch)
                if (mf > 0) queue.add(longArrayOf(mf.toLong() shl 1, ch.toLong(), dep + 1L, 0))
            }
        }
    }

    companion object {
        const val SUB_ACCENT = 0.15f
        const val SUB_FIRST_EXTRA = 0.15f
        const val INS_APOSTROPHE = 0.1f
        const val INS_OTHER = 1.0f
        const val INS_DOUBLE = 0.5f
        const val DEL_APOSTROPHE = 0.3f
        const val DEL_DOUBLE = 0.5f
        const val DEL_OTHER = 0.9f
        const val DEL_FIRST_EXTRA = 0.3f
        const val TRANSPOSE = 0.65f
        const val MAX_DEPTH = 48

        fun maxCost(n: Int): Float = when {
            n <= 2 -> 1.0f
            n <= 4 -> 1.3f
            else -> 2.0f
        }
    }
}
