package com.bori.core.keyboard.dict

import android.content.Context

/**
 * Sentence context for prediction: P(word | two or three previous words), in the .ngram format
 * written by tools/build_ngrams.py (see that file for the layout and the maths). It sits on top
 * of the bigrams in [BinaryDictionary]; node ids are the dictionary's.
 *
 * Scoring (Suggester.lm), from the bigram score s2 = ln P2(w | c):
 *   s3 = stored ln P(w | b c), else ln gamma(b c) + s2      (context not stored: s3 = s2)
 *   s4 = stored ln P(w | a b c), else ln gamma(a b c) + s3  (context not stored: s4 = s3)
 */
class NgramModel private constructor(r: BinaryDictionary.Reader, expectedNodes: Int) {

    private class Table(r: BinaryDictionary.Reader) {
        val keys: LongArray
        val start: IntArray
        val next: IntArray
        val cost: ByteArray
        val backoff: ByteArray

        init {
            val m = r.int()
            keys = LongArray(m).also { r.longs(it) }
            start = IntArray(m + 1).also { r.ints(it) }
            val k = start[m]
            next = IntArray(k).also { r.ints(it) }
            cost = ByteArray(k).also { r.bytes(it) }
            backoff = ByteArray(m).also { r.bytes(it) }
            r.align4()
        }

        fun find(key: Long): Int = java.util.Arrays.binarySearch(keys, key).takeIf { it >= 0 } ?: -1

        fun logP(i: Int, node: Int): Float? {
            for (j in start[i] until start[i + 1]) if (next[j] == node) return -(cost[j].toInt() and 0xFF) / 16f
            return null
        }

        fun backoff(i: Int): Float = -(backoff[i].toInt() and 0xFF) / 16f
    }

    private val t3: Table
    private val t4: Table

    /** Stored entries, for the size line in Copy diagnostics. */
    val entryCount: Int get() = t3.next.size + t4.next.size

    init {
        val magic = ByteArray(4).also { r.bytes(it) }
        require(String(magic, Charsets.US_ASCII) == "BNGR") { "Not a .ngram file" }
        val version = r.short()
        require(version == 1) { "Unsupported .ngram version $version" }
        r.short()
        val nodes = r.int()
        // Built against another dictionary: node ids would point at the wrong words.
        require(nodes == expectedNodes) { ".ngram was built for $nodes nodes, dictionary has $expectedNodes" }
        t3 = Table(r)
        t4 = Table(r)
    }

    /**
     * Table rows for a context, oldest word first: [c3] for (b, c), [c4] for (a, b, c);
     * -1 when that context isn't stored. Resolve once per context, then score many words.
     */
    class Resolved(val c3: Int, val c4: Int) {
        val any: Boolean get() = c3 >= 0 || c4 >= 0

        companion object {
            val NONE = Resolved(-1, -1)
        }
    }

    /** [a] may be -1 (only two words of context); [b] may be the root (sentence start). */
    fun resolve(a: Int, b: Int, c: Int): Resolved {
        if (b < 0 || c <= 0) return Resolved.NONE
        val c3 = t3.find(pack(b, c))
        val c4 = if (a >= 0 && b > 0) t4.find((pack(a, b) shl BITS) or c.toLong()) else -1
        return Resolved(c3, c4)
    }

    /** ln P(word | context) given the bigram score [s2] = ln P2(word | c). */
    fun score(r: Resolved, node: Int, s2: Float): Float {
        var s = s2
        if (r.c3 >= 0) s = t3.logP(r.c3, node) ?: (t3.backoff(r.c3) + s)
        if (r.c4 >= 0) s = t4.logP(r.c4, node) ?: (t4.backoff(r.c4) + s)
        return s
    }

    /** Calls [block] with every word stored after the context (longest context first). */
    fun forEachNext(r: Resolved, block: (Int) -> Unit) {
        if (r.c4 >= 0) for (j in t4.start[r.c4] until t4.start[r.c4 + 1]) block(t4.next[j])
        if (r.c3 >= 0) for (j in t3.start[r.c3] until t3.start[r.c3 + 1]) block(t3.next[j])
    }

    companion object {
        private const val BITS = 21

        private fun pack(a: Int, b: Int): Long = (a.toLong() shl BITS) or b.toLong()

        fun fromAsset(context: Context, path: String, dict: BinaryDictionary): NgramModel =
            context.assets.open(path).use { NgramModel(BinaryDictionary.Reader(it), dict.nodeCount) }
    }
}
