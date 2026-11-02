package com.bori.core.keyboard.predict

import java.util.Locale

/**
 * The word currently being typed (shown as composing text), with the touch point of every
 * character so corrections can weigh near-misses.
 */
class Composer {
    private val sb = StringBuilder()
    private var xs = FloatArray(32)
    private var ys = FloatArray(32)

    /** Set when the user undid an autocorrection of this word; it won't be corrected again. */
    var autocorrectSuppressed = false

    val typed: String get() = sb.toString()
    val length: Int get() = sb.length
    val isEmpty: Boolean get() = sb.isEmpty()

    fun add(text: String, x: Float, y: Float) {
        for ((i, c) in text.withIndex()) {
            ensure(sb.length + 1)
            xs[sb.length] = if (i == 0) x else Float.NaN
            ys[sb.length] = if (i == 0) y else Float.NaN
            sb.append(c)
        }
    }

    fun deleteLast() {
        if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
        if (sb.isEmpty()) autocorrectSuppressed = false
    }

    fun set(word: String) {
        reset()
        add(word, Float.NaN, Float.NaN)
        for (i in word.indices) { xs[i] = Float.NaN; ys[i] = Float.NaN }
    }

    fun reset() {
        sb.setLength(0)
        autocorrectSuppressed = false
    }

    fun x(i: Int): Float = xs[i]
    fun y(i: Int): Float = ys[i]

    private val firstUpper: Boolean get() = sb.isNotEmpty() && sb[0].isUpperCase()
    private val allUpper: Boolean
        get() = sb.length > 1 && sb.all { !it.isLetter() || it.isUpperCase() } && sb.any { it.isLetter() }

    /** Gives a dictionary word the capitalisation the user typed ("Teh" → "The", "NASA"). */
    fun applyCase(word: String, locale: Locale): String = when {
        allUpper -> word.uppercase(locale)
        firstUpper -> word.replaceFirstChar { it.titlecase(locale) }
        else -> word
    }

    private fun ensure(n: Int) {
        if (n > xs.size) {
            xs = xs.copyOf(n * 2)
            ys = ys.copyOf(n * 2)
        }
    }
}
