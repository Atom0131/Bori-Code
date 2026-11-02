package com.bori.core.keyboard.dict

import android.content.Context
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read-only word trie with frequencies and next-word bigrams, in the .bdic format written
 * by tools/build_dict.py (see that file for the byte layout). Everything is streamed
 * straight into primitive arrays (no whole-file buffer: the file is ~19 MB), so lookups
 * allocate nothing.
 *
 * Trie keys are lowercase; [wordOf] restores the dictionary's capitalisation ("I", "Spain").
 */
class BinaryDictionary private constructor(r: Reader) {

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

    /**
     * Known typos of common words (typo → word), from assets/dict/<lang>_typos.txt
     * (tools/build_typos.py). Set once by [Dictionaries] before the dictionary is handed out.
     */
    var typos: Map<String, String> = emptyMap()
        internal set

    init {
        val magic = ByteArray(4).also { r.bytes(it) }
        require(String(magic, Charsets.US_ASCII) == "BDIC") { "Not a .bdic file" }
        val version = r.short()
        require(version == 1) { "Unsupported .bdic version $version" }
        r.short()
        nodeCount = r.int()
        logRange = r.float()
        wordCount = r.int()
        val n = nodeCount
        chars = CharArray(n).also { r.chars(it) }
        parent = IntArray(n).also { r.ints(it) }
        first = IntArray(n).also { r.ints(it) }
        childCount = ByteArray(n).also { r.bytes(it) }
        freq = ByteArray(n).also { r.bytes(it) }
        maxFreq = ByteArray(n).also { r.bytes(it) }
        flags = ByteArray(n).also { r.bytes(it) }
        r.align4()
        repeat(r.int()) {
            val node = r.int()
            val len = r.short()
            val b = ByteArray(len).also { r.bytes(it) }
            display[node] = String(b, Charsets.UTF_8)
        }
        r.align4()
        val m = r.int()
        bigramPrev = IntArray(m).also { r.ints(it) }
        bigramStart = IntArray(m + 1).also { r.ints(it) }
        val k = bigramStart[m]
        bigramNext = IntArray(k).also { r.ints(it) }
        bigramCost = ByteArray(k).also { r.bytes(it) }
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

    /** No corpus frequency at all (mostly Wiktionary-only words): weakly valid, never swiped. */
    fun isRare(node: Int): Boolean = flags[node].toInt() and F_RARE != 0

    /**
     * Only weakly valid: a rare word, or one added from Wiktionary (flag 64, swipeable). Typed
     * exactly it stays unless a fix clearly outscores it (Suggester.RARE_TYPED_BONUS).
     */
    fun isWeak(node: Int): Boolean = flags[node].toInt() and (F_RARE or F_WEAK) != 0

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
        private const val F_RARE = 32
        private const val F_WEAK = 64

        /** Puts the commonest words near ln P = -4 (about 2%), like real text. */
        private const val UNIGRAM_OFFSET = 4f

        fun fromAsset(context: Context, path: String): BinaryDictionary =
            context.assets.open(path).use { BinaryDictionary(Reader(it)) }

        /** Reads a "typo<TAB>word" list; missing or unreadable files give an empty map. */
        fun typosFromAsset(context: Context, path: String): Map<String, String> {
            val out = HashMap<String, String>(32768)
            runCatching {
                context.assets.open(path).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("#")) continue
                        val tab = line.indexOf('\t')
                        if (tab > 0 && tab < line.length - 1) out[line.substring(0, tab)] = line.substring(tab + 1)
                    }
                }
            }
            return out
        }
    }

    /** Little-endian reader over a stream with a small reusable chunk buffer. */
    private class Reader(private val input: InputStream) {
        private val buf = ByteBuffer.allocate(CHUNK).order(ByteOrder.LITTLE_ENDIAN).also { it.limit(0) }
        private var pos = 0L

        /** Makes at least [n] (<= CHUNK) bytes available in [buf]. */
        private fun need(n: Int) {
            if (buf.remaining() >= n) return
            buf.compact()
            while (buf.position() < n) {
                val read = input.read(buf.array(), buf.position(), buf.capacity() - buf.position())
                if (read < 0) throw EOFException("Truncated .bdic file")
                buf.position(buf.position() + read)
            }
            buf.flip()
        }

        fun short(): Int { need(2); pos += 2; return buf.short.toInt() and 0xFFFF }
        fun int(): Int { need(4); pos += 4; return buf.int }
        fun float(): Float { need(4); pos += 4; return buf.float }

        fun bytes(out: ByteArray) {
            var off = 0
            while (off < out.size) {
                need(1)
                val k = minOf(buf.remaining(), out.size - off)
                buf.get(out, off, k)
                off += k
            }
            pos += out.size
        }

        fun ints(out: IntArray) {
            var off = 0
            while (off < out.size) {
                need(4)
                val k = minOf(buf.remaining() / 4, out.size - off)
                buf.asIntBuffer().get(out, off, k)
                buf.position(buf.position() + 4 * k)
                off += k
            }
            pos += 4L * out.size
        }

        fun chars(out: CharArray) {
            var off = 0
            while (off < out.size) {
                need(2)
                val k = minOf(buf.remaining() / 2, out.size - off)
                buf.asCharBuffer().get(out, off, k)
                buf.position(buf.position() + 2 * k)
                off += k
            }
            pos += 2L * out.size
        }

        fun align4() {
            val pad = ((4 - (pos and 3)) and 3).toInt()
            if (pad > 0) { need(pad); buf.position(buf.position() + pad); pos += pad }
        }

        private companion object {
            const val CHUNK = 64 * 1024
        }
    }
}
