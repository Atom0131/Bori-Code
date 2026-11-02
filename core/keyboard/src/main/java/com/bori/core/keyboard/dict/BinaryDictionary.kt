package com.bori.core.keyboard.dict

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read-only word trie with frequencies and next-word bigrams, in the .bdic format written
 * by tools/build_dict.py (see that file for the byte layout). Everything is bulk-loaded
 * into primitive arrays, so lookups allocate nothing.
 *
 * Trie keys are lowercase; [wordOf] restores the dictionary's capitalisation ("I", "Spain").
 */
class BinaryDictionary private constructor(buf: ByteBuffer) {

    val nodeCount: Int
    val wordCount: Int
    /** Span, in natural-log units, between the rarest (freq 1) and commonest (255) words. */
    val logRange: Float

    private val chars: CharArray
    private val parent: IntArray
    private val first: IntArray
    private val childCount: ByteArray
    private val freq: ByteArray
    private val maxFreq: ByteArray
    private val flags: ByteArray
    private val display = HashMap<Int, String>()

    private val bigramPrev: IntArray
    private val bigramStart: IntArray
    private val bigramNext: IntArray
    private val bigramCost: ByteArray

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        require(String(magic, Charsets.US_ASCII) == "BDIC") { "Not a .bdic file" }
        val version = buf.short.toInt()
        require(version == 1) { "Unsupported .bdic version $version" }
        buf.short
        nodeCount = buf.int
        logRange = buf.float
        wordCount = buf.int
        val n = nodeCount
        chars = CharArray(n).also { buf.asCharBuffer().get(it); buf.position(buf.position() + 2 * n) }
        parent = ints(buf, n)
        first = ints(buf, n)
        childCount = bytes(buf, n)
        freq = bytes(buf, n)
        maxFreq = bytes(buf, n)
        flags = bytes(buf, n)
        align(buf)
        repeat(buf.int) {
            val node = buf.int
            val len = buf.short.toInt() and 0xFFFF
            val b = ByteArray(len).also { buf.get(it) }
            display[node] = String(b, Charsets.UTF_8)
        }
        align(buf)
        val m = buf.int
        bigramPrev = ints(buf, m)
        bigramStart = ints(buf, m + 1)
        val k = bigramStart[m]
        bigramNext = ints(buf, k)
        bigramCost = bytes(buf, k)
    }

    // ---------------------------------------------------------------- trie access

    fun childStart(node: Int): Int = first[node]
    fun childCount(node: Int): Int = childCount[node].toInt() and 0xFF
    fun charAt(node: Int): Char = chars[node]
    fun parentOf(node: Int): Int = parent[node]

    /** 0 when the node does not end a word, else 1..255. */
    fun freqOf(node: Int): Int = freq[node].toInt() and 0xFF

    /** Highest suggestible frequency anywhere in the node's subtree (for best-first search). */
    fun maxFreqOf(node: Int): Int = maxFreq[node].toInt() and 0xFF

    fun isWord(node: Int): Boolean = freq[node].toInt() != 0

    /** Valid but never offered (offensive words flagged NOSUGGEST in Hunspell). */
    fun isOffensive(node: Int): Boolean = flags[node].toInt() and F_NOSUGGEST != 0

    /** Everyday slang and modern words from the word lists: never "fixed" into another word. */
    fun keepsAsTyped(node: Int): Boolean = flags[node].toInt() and F_KEEP != 0

    /** Ordinary word: not all caps (abbreviations like "GI") and no special display form. */
    fun isPlainWord(node: Int): Boolean = flags[node].toInt() and (F_ALL_CAPS or F_CUSTOM) == 0

    fun child(node: Int, c: Char): Int {
        val start = first[node]
        if (start < 0) return -1
        for (i in start until start + childCount(node)) {
            if (chars[i] == c) return i
        }
        return -1
    }

    /** Node for a lowercase key, or -1. Accepts prefixes; check [isWord] for full words. */
    fun findNode(key: CharSequence): Int {
        var n = ROOT
        for (c in key) {
            n = child(n, c)
            if (n < 0) return -1
        }
        return n
    }

    fun findWord(key: CharSequence): Int = findNode(key).takeIf { it >= 0 && isWord(it) } ?: -1

    fun depthOf(node: Int): Int {
        var d = 0
        var n = node
        while (n > 0) {
            d++
            n = parent[n]
        }
        return d
    }

    /** Lowercase trie key spelled by the path to [node]. */
    fun keyOf(node: Int): String {
        val sb = StringBuilder()
        var n = node
        while (n > 0) {
            sb.append(chars[n])
            n = parent[n]
        }
        return sb.reverse().toString()
    }

    /** The word with its dictionary capitalisation. */
    fun wordOf(node: Int): String {
        display[node]?.let { return it }
        val key = keyOf(node)
        val f = flags[node].toInt()
        return when {
            f and F_ALL_CAPS != 0 -> key.uppercase()
            f and F_CAP_FIRST != 0 -> key.replaceFirstChar { it.uppercaseChar() }
            else -> key
        }
    }

    // ---------------------------------------------------------------- language model

    /** ln P(word), roughly: 0 for the commonest words down to -logRange for the rarest. */
    fun unigramLogP(node: Int): Float = (freqOf(node) / 255f - 1f) * logRange - UNIGRAM_OFFSET

    /** Calls [block] with (nextNode, ln P(next | prev)) for the stored successors of [prev]. */
    inline fun forEachNext(prev: Int, block: (Int, Float) -> Unit) {
        val i = bigramIndex(prev)
        if (i < 0) return
        for (j in bigramRange(i)) block(bigramNextAt(j), bigramLogPAt(j))
    }

    /** ln P(next | prev) when the pair is stored, else null. */
    fun bigramLogP(prev: Int, next: Int): Float? {
        val i = bigramIndex(prev)
        if (i < 0) return null
        for (j in bigramRange(i)) if (bigramNext[j] == next) return bigramLogPAt(j)
        return null
    }

    fun bigramIndex(prev: Int): Int = java.util.Arrays.binarySearch(bigramPrev, prev).takeIf { it >= 0 } ?: -1
    fun bigramRange(i: Int): IntRange = bigramStart[i] until bigramStart[i + 1]
    fun bigramNextAt(j: Int): Int = bigramNext[j]
    fun bigramLogPAt(j: Int): Float = -(bigramCost[j].toInt() and 0xFF) / 16f

    companion object {
        const val ROOT = 0
        private const val F_CAP_FIRST = 1
        private const val F_ALL_CAPS = 2
        private const val F_NOSUGGEST = 4
        private const val F_CUSTOM = 8
        private const val F_KEEP = 16

        /** Puts the commonest words near ln P = -4 (about 2%), like real text. */
        private const val UNIGRAM_OFFSET = 4f

        fun fromAsset(context: Context, path: String): BinaryDictionary {
            val bytes = context.assets.open(path).use { it.readBytes() }
            return BinaryDictionary(ByteBuffer.wrap(bytes))
        }

        private fun ints(buf: ByteBuffer, n: Int): IntArray {
            val a = IntArray(n)
            buf.asIntBuffer().get(a)
            buf.position(buf.position() + 4 * n)
            return a
        }

        private fun bytes(buf: ByteBuffer, n: Int): ByteArray = ByteArray(n).also { buf.get(it) }

        private fun align(buf: ByteBuffer) {
            val p = buf.position()
            buf.position((p + 3) and 3.inv())
        }
    }
}
