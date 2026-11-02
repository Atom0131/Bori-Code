package com.bori.app.terminal

import android.util.Log
import com.bori.core.keyboard.learn.UserStore
import com.bori.core.keyboard.predict.Aggressiveness
import com.bori.core.keyboard.predict.Composer
import com.bori.core.keyboard.predict.KeyProximity
import com.bori.core.keyboard.predict.Suggester
import com.bori.core.keyboard.predict.Suggestion
import com.bori.core.keyboard.swipe.SwipeAdaptation

/**
 * Bori Keyboard's typing (spell correction, autocorrect with undo, glide words, learning) on a
 * terminal. A terminal has no editable text: every key goes straight to the shell, so a correction is
 * Backspaces and the new word, sent when the word ends.
 *
 * Correction only happens where it helps: at a coding agent's prompt (Claude Code's input box) it
 * corrects prose. At a shell prompt (bori:~$, $, #) words are commands and file names, so it only
 * suggests: the screen's own words first. A word glued to a path or flag ("/usr", "-la", "a.txt")
 * is typed exactly as it is.
 */
class TerminalTyping(
    private val engine: () -> TerminalWords.Engine?,
    private val out: Output,
) {
    interface Output {
        fun text(s: String)
        fun backspace(n: Int)
        /** The cursor's line, up to the cursor. */
        fun lineBeforeCursor(): String
        /** Words on the screen: file names, commands, errors. */
        fun screenWords(): Set<String>
    }

    var level = Aggressiveness.NORMAL
    /** Where the letter keys are; set by the keyboard each time its geometry changes. */
    var proximity: KeyProximity? = null

    /** Up to three words for the strip; [boldIndex] is what space will commit (or the glided word). */
    var suggestions: List<String> = emptyList()
        private set
    var boldIndex = -1
        private set
    var onChange: () -> Unit = {}

    private val composer = Composer()
    /** The current word was typed at an agent's prompt, so it may be corrected and learned. */
    private var prose = false
    private var glided = false
    private var glideAlternatives = emptyList<String>()
    private var glideCapture: GlideCapture? = null
    private var prevWord: String? = null
    private var sentenceStart = true
    private var undo: Undo? = null
    /** Characters sent since the last correction; a Backspace undoes it only right after. */
    private var lastSent: Char? = null

    private class Undo(val typed: String, val committed: String, val separator: String, val typedIsWord: Boolean)
    private class GlideCapture(val xs: FloatArray, val ys: FloatArray, val count: Int, val prox: KeyProximity, val word: String)

    /** A letter (or an apostrophe inside a word); [x]/[y] are the touch point for tap correction. */
    fun letter(text: String, x: Float, y: Float) {
        undo = null
        val apostrophe = text == "'" || text == "’"
        if (glided && !apostrophe) {
            // A tap after a glided word starts a new word.
            commit(autocorrect = false)
            send(" ")
        }
        if (composer.isEmpty) {
            if (apostrophe || !atWordStart()) {
                send(text)
                return
            }
            prose = !ShellPrompt.REGEX.containsMatchIn(out.lineBeforeCursor())
        }
        composer.add(text, x, y)
        send(text)
        refresh()
    }

    /** Space or punctuation: ends the word, correcting it at an agent's prompt. */
    fun separator(text: String) {
        undo = null
        if (!composer.isEmpty) {
            val committed = commit(autocorrect = text == " " || text in AUTOCORRECT_TRIGGERS)
            send(text)
            if (committed != null) undo = committed.takeIf { it.committed != it.typed }?.let { Undo(it.typed, it.committed, text, it.typedIsWord) }
        } else {
            send(text)
        }
        if (text.any { it in ".!?" }) sentenceStart = true
        refresh()
    }

    fun delete() {
        if (!composer.isEmpty) {
            if (glided) {
                out.backspace(composer.length)
                resetWord()
            } else {
                composer.deleteLast()
                out.backspace(1)
                if (composer.isEmpty) resetWord()
            }
            lastSent = null
            refresh()
            return
        }
        val u = undo
        undo = null
        if (u != null) {
            // Backspace right after a correction puts back what was typed, and keeps it.
            out.backspace(u.committed.length + u.separator.length)
            out.text(u.typed)
            lastSent = u.typed.lastOrNull()
            composer.set(u.typed)
            composer.autocorrectSuppressed = true
            prose = true
            engine()?.let { e -> if (u.typedIsWord) e.user.recordKeep(u.typed) else e.user.recordWord(null, u.typed, UserStore.LEARN_AFTER) }
            refresh()
            return
        }
        out.backspace(1)
        lastSent = null
        refresh()
    }

    /** Enter, Tab, an arrow or a Ctrl/Alt combination: the word ends as typed. */
    fun breakWord(enter: Boolean = false) {
        if (!composer.isEmpty) commit(autocorrect = false)
        undo = null
        lastSent = if (enter) '\n' else null
        if (enter) sentenceStart = true
        refresh()
    }

    /** Raw text that isn't part of a word (a paste, a symbol after Ctrl). */
    fun raw(text: String) {
        breakWord()
        send(text)
    }

    /** A swipe over the letter keys, in the keyboard's pixel coordinates. */
    fun glide(xs: FloatArray, ys: FloatArray, count: Int, caps: Caps) {
        val e = engine() ?: return
        val prox = proximity ?: return
        undo = null
        if (!composer.isEmpty) {
            commit(autocorrect = true)
            send(" ")
        } else if (lastSent?.let { !it.isWhitespace() && it !in NO_SPACE_AFTER } == true) {
            // Gliding right after a word adds the space, like a phone keyboard.
            send(" ")
        }
        val started = System.nanoTime()
        val ctx = context()
        val results = e.decoder.decode(
            xs, ys, count, prox, { e.suggester.languageScore(it, ctx) }, { e.suggester.isBlockedWord(it) },
            offsetX = { e.user.keyOffsetX(it) }, offsetY = { e.user.keyOffsetY(it) },
        )
        Log.d(TAG, "glide: ${results.size} words in ${(System.nanoTime() - started) / 1_000_000} ms")
        if (results.isEmpty()) {
            refresh()
            return
        }
        val locale = e.language.locale
        val words = results.map { r ->
            val w = e.dict.wordOf(r.node)
            when (caps) {
                Caps.ALL -> w.uppercase(locale)
                Caps.FIRST -> w.replaceFirstChar { it.titlecase(locale) }
                Caps.NONE -> w
            }
        }.distinct()
        prose = !ShellPrompt.REGEX.containsMatchIn(out.lineBeforeCursor())
        composer.set(words[0])
        send(words[0])
        glided = true
        glideAlternatives = words.drop(1)
        glideCapture = GlideCapture(xs.copyOf(count), ys.copyOf(count), count, prox, words[0])
        refresh()
    }

    enum class Caps { NONE, FIRST, ALL }

    /** A suggestion tapped: it replaces the word, followed by a space (a glide's other reading swaps in place). */
    fun pick(index: Int) {
        val choice = suggestions.getOrNull(index) ?: return
        undo = null
        val typed = composer.typed
        if (glided) {
            out.backspace(typed.length)
            send(choice)
            glideAlternatives = (glideAlternatives - choice) + typed
            composer.set(choice)
            glideCapture = null
            refresh()
            return
        }
        if (typed.isNotEmpty() && choice.startsWith(typed)) {
            send(choice.substring(typed.length))
        } else {
            out.backspace(typed.length)
            send(choice)
        }
        learn(choice, explicit = true)
        resetWord()
        send(" ")
        refresh()
    }

    private class Committed(val typed: String, val committed: String, val typedIsWord: Boolean)

    /** Ends the word, replacing it with the autocorrection if there is one. */
    private fun commit(autocorrect: Boolean): Committed? {
        val typed = composer.typed
        val e = engine()
        var word = typed
        var typedIsWord = false
        if (autocorrect && prose && !glided && e != null && level != Aggressiveness.OFF) {
            val started = System.nanoTime()
            val auto = e.suggester.suggest(composer, context(), proximity, level).autocorrect
            if (auto != null && auto.word != typed && auto.kind != Suggestion.Kind.TYPED) {
                out.backspace(typed.length)
                send(auto.word)
                word = auto.word
                typedIsWord = e.dict.findWord(typed.lowercase(e.language.locale)) >= 0
                if (typedIsWord) e.user.demote(typed)
                Log.d(TAG, "autocorrect in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        }
        // A glide kept as decoded teaches where this user's finger lands.
        glideCapture?.let { g -> if (glided && g.word == word && e != null) e.user.addKeyOffsetSamples(SwipeAdaptation.samples(g.prox, g.xs, g.ys, g.count, word)) }
        if (prose) learn(word, explicit = false)
        resetWord()
        return Committed(typed, word, typedIsWord)
    }

    private fun learn(word: String, explicit: Boolean) {
        val e = engine() ?: return
        if (!prose || word.isBlank()) return
        var prev = if (sentenceStart) null else prevWord
        for (w in word.split(' ').filter { it.isNotEmpty() }) {
            e.user.recordWord(prev, w, if (explicit) UserStore.LEARN_AFTER else 1)
            prev = w
        }
        prevWord = prev
        sentenceStart = false
    }

    private fun resetWord() {
        composer.reset()
        glided = false
        glideAlternatives = emptyList()
        glideCapture = null
    }

    private fun send(text: String) {
        if (text.isEmpty()) return
        out.text(text)
        lastSent = text.last()
    }

    /** A word starts here: after a space, an opening bracket or quote, or at the start of the line. */
    private fun atWordStart(): Boolean {
        val before = lastSent ?: out.lineBeforeCursor().lastOrNull() ?: return true
        return before.isWhitespace() || before in WORD_OPENERS
    }

    private fun context(): Suggester.Context =
        if (sentenceStart) Suggester.Context(null, sentenceStart = true) else Suggester.Context(prevWord, sentenceStart = false)

    private fun refresh() {
        val e = engine()
        val typed = composer.typed
        when {
            composer.isEmpty || e == null -> { suggestions = emptyList(); boldIndex = -1 }
            glided -> {
                suggestions = listOf(typed) + glideAlternatives.take(2)
                boldIndex = 0
            }
            !prose -> {
                // Commands and file names: what's on the screen, then dictionary completions.
                val screen = out.screenWords().filter { it.length > typed.length && it.startsWith(typed) }.sortedBy { it.length }
                val dict = e.suggester.suggest(composer, Suggester.Context.NONE, proximity, Aggressiveness.OFF).ranked
                    .filter { it.kind == Suggestion.Kind.COMPLETION && it.word.startsWith(typed, ignoreCase = true) }.map { it.word }
                suggestions = (screen + dict).distinct().take(3)
                boldIndex = -1
            }
            else -> {
                val res = e.suggester.suggest(composer, context(), proximity, level)
                val auto = res.autocorrect
                val others = res.ranked.map { it.word }.filter { it != typed && it != auto?.word }
                suggestions = listOfNotNull(auto?.word) + others.take(if (auto != null) 2 else 3)
                boldIndex = if (auto != null) 0 else -1
            }
        }
        onChange()
    }

    private companion object {
        const val TAG = "BoriKeys"
        val AUTOCORRECT_TRIGGERS = setOf(".", ",", "!", "?", ";", ":", ")", "\"")
        const val WORD_OPENERS = "([{\"'`"
        const val NO_SPACE_AFTER = "([{\"'`/@#-_.=~:"
    }
}

/** A shell waiting for a command: Bori's `bori:~/x$ `, or a plain `$ ` / `# ` / `user@host:~$ `. */
object ShellPrompt {
    val REGEX = Regex("^(bori:\\S*[\$#] |\\S*[\$#] |\\S+@\\S+ ?[\$#] )")
}
