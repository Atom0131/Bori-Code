package com.bori.core.keyboard.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.bori.core.keyboard.clipboard.ClipboardHistory
import com.bori.core.keyboard.dict.BinaryDictionary
import com.bori.core.keyboard.layout.KeyGeometry
import com.bori.core.keyboard.learn.UserStore
import com.bori.core.keyboard.predict.Composer
import com.bori.core.keyboard.predict.KeyProximity
import com.bori.core.keyboard.predict.SuggestResult
import com.bori.core.keyboard.predict.Suggester
import com.bori.core.keyboard.predict.Suggestion
import com.bori.core.keyboard.settings.Prefs
import com.bori.core.keyboard.swipe.GlideDecoder
import com.bori.core.keyboard.swipe.SwipeAdaptation
import com.bori.core.keyboard.view.SuggestionStripView
import java.util.Locale

/**
 * All text editing for one input field: the composing word, glide words, suggestions,
 * autocorrect and its undo, punctuation spacing, cursor/delete slides, pasting, fixes for a
 * word the user taps on, and learning. Every edit goes through here so the expected
 * cursor position stays accurate; that is how a cursor move by the user (tap elsewhere) is
 * told apart from the editor echoing our own edits.
 */
class TypingSession(
    private val ime: InputHost,
    private val prefs: Prefs,
    private val user: UserStore,
) : SuggestionStripView.Listener {

    var strip: SuggestionStripView? = null
    /** Whether the next word starts capitalised (drives prediction capitalisation). */
    var capsProvider: () -> Boolean = { false }
    /** Current key geometry, for touch-aware corrections. */
    var geometryProvider: () -> KeyGeometry? = { null }
    var clipboard: ClipboardHistory? = null

    private val composer = Composer()
    private var dictionary: BinaryDictionary? = null
    private var suggester: Suggester? = null
    private var glideDecoder: GlideDecoder? = null

    /** The composing word came from a glide: no autocorrect, backspace removes it whole. */
    private var glideActive = false
    private var glideAlternatives: List<Suggestion> = emptyList()

    /** The last glide, kept until its word is committed so accepted glides can teach offsets. */
    private class GlideCapture(val xs: FloatArray, val ys: FloatArray, val count: Int, val prox: KeyProximity, val word: String)
    private var lastGlide: GlideCapture? = null

    /** The focused field is the Swipe Trainer's: record swipes, learn nothing else. */
    private var trainerField = false

    /** Delete slide: cursor where it started and the text before it, or -1 when idle. */
    private var deleteAnchor = -1
    private var deleteText = ""
    private var locale: Locale = Locale.US
    private var policy = EditorPolicy.DEFAULT

    private var result: SuggestResult? = null
    private var slots: List<Suggestion?> = listOf(null, null, null)
    private var predictions: List<Suggestion> = emptyList()

    /** The last commit added a space by itself (suggestion pick); punctuation may replace it. */
    private var autoSpaced = false
    private var undo: Undo? = null
    private var lastSpaceAt = 0L
    private var resumeAfterDelete = false

    /** [typedIsWord]: the typed text was itself a dictionary word (a real-word fix). */
    private class Undo(val typed: String, val committed: String, val separator: String, val typedIsWord: Boolean)

    /**
     * An undone autocorrection waiting to be confirmed: only committing the restored word
     * unchanged (space, punctuation) keeps it. A backspace after a fix is often the start of
     * an edit (the typo log showed djne→done, stepls→steps undone and then retyped).
     */
    private var pendingKeep: Undo? = null

    /**
     * Tap-to-fix: a word already in the text that the cursor was put on. Its suggestions
     * replace the whole word ([start]..[end] in the editor).
     */
    private class Recorrect(val start: Int, val end: Int, val word: String, val ctx: Suggester.Context, val res: SuggestResult)
    private var recorrect: Recorrect? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recorrectTask = Runnable { findRecorrect() }

    private var proximity: KeyProximity? = null
    private var proximityFor: KeyGeometry? = null

    // ---------------------------------------------------------------- cursor tracking

    private var cursor = -1
    private var composeStart = -1
    private val recentExpected = ArrayDeque<Int>()

    private fun expect(pos: Int) {
        cursor = pos
        recentExpected.addLast(pos)
        while (recentExpected.size > 12) recentExpected.removeFirst()
    }

    /** Moves the expected cursor by [delta], if it is known. */
    private fun moved(delta: Int) {
        if (cursor >= 0) expect((cursor + delta).coerceAtLeast(0))
    }

    private val ic: InputConnection? get() = ime.currentInputConnection

    // ---------------------------------------------------------------- lifecycle

    fun setDictionary(dict: BinaryDictionary?, locale: Locale) {
        this.locale = locale
        dictionary = dict
        dropRecorrect()
        suggester = dict?.let { Suggester(it, user, locale) }
        glideDecoder = dict?.let { GlideDecoder(it) }
        refresh()
    }

    fun start(info: EditorInfo?, policy: EditorPolicy) {
        this.policy = policy
        trainerField = SwipeRecorder.isTrainerField(info, ime.context)
        dropRecorrect()
        resetComposer()
        result = null
        undo = null
        autoSpaced = false
        resumeAfterDelete = false
        recentExpected.clear()
        cursor = info?.initialSelEnd ?: -1
        composeStart = -1
        strip?.setIncognito(!learningAllowed())
        refresh()
    }

    fun finish() {
        dropRecorrect()
        if (!composer.isEmpty) {
            ic?.finishComposingText()
            resetComposer()
        }
        undo = null
        autoSpaced = false
    }

    private fun resetComposer() {
        pendingKeep = null
        composer.reset()
        glideActive = false
        lastGlide = null
        glideAlternatives = emptyList()
    }

    /** Glide typing needs suggestions on and a dictionary loaded. */
    fun canGlide(): Boolean = suggestionsOn && glideDecoder != null

    /** Why glide is or isn't available, for the diagnostics log. */
    fun glideStatus(): String =
        "suggestionsSetting=${prefs.showSuggestions} fieldAllows=${!policy.noSuggestions} " +
            "appNoSuggest=${policy.appNoSuggestions} follow=${prefs.followNoSuggestions} " +
            "dictionary=${dictionary != null} decoder=${glideDecoder != null}"

    private val suggestionsOn: Boolean
        get() = prefs.showSuggestions && !policy.noSuggestions &&
            !(policy.appNoSuggestions && prefs.followNoSuggestions)
    private fun learningAllowed() = !prefs.incognito && !policy.noLearning && !trainerField
    private fun typoLogOn() = prefs.typoLog && learningAllowed()

    // ---------------------------------------------------------------- typing

    /** Text from a key or popup. [x]/[y] are the touch point (NaN when unknown). */
    fun onText(text: String, x: Float, y: Float) {
        val ic = ic ?: return
        lastSpaceAt = 0L
        resumeAfterDelete = false
        val hadFix = dropRecorrect()
        if (suggestionsOn && isWordText(text)) {
            undo = null
            autoSpaced = false
            if (glideActive && !composer.isEmpty && text.none { it == '\'' || it == '’' }) {
                // A tap after a glided word starts a new word.
                ic.beginBatchEdit()
                commitComposing(ic, autocorrect = false, separator = " ")
                commitRaw(ic, " ")
                ic.endBatchEdit()
            }
            if (composer.isEmpty) {
                // Starting a word directly after another word char would split it; only
                // compose at a word boundary.
                val before = ic.getTextBeforeCursor(1, 0)
                if (!before.isNullOrEmpty() && before[0].isLetter()) {
                    commitRaw(ic, text)
                    if (hadFix) refresh()
                    return
                }
                composeStart = cursor
            }
            composer.add(text, x, y)
            setComposing(ic)
            refresh()
            return
        }
        // Separator or symbol: finish the word first (autocorrecting on sentence punctuation).
        if (!composer.isEmpty) commitComposing(ic, autocorrect = text in AUTOCORRECT_TRIGGERS, separator = text)
        // "word |" + "." → "word. |": punctuation replaces the space a suggestion pick added.
        if (text in SPACE_AFTER && autoSpaced) {
            val before = ic.getTextBeforeCursor(1, 0)
            if (before == " ") {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                moved(-1)
                commitRaw(ic, "$text ")
                ic.endBatchEdit()
                autoSpaced = true
                refresh()
                return
            }
        }
        commitRaw(ic, text)
        autoSpaced = false
        refresh()
    }

    fun onSpace() {
        val ic = ic ?: return
        resumeAfterDelete = false
        dropRecorrect()
        val now = SystemClock.uptimeMillis()
        if (!composer.isEmpty) {
            ic.beginBatchEdit()
            commitComposing(ic, autocorrect = true, separator = " ")
            commitRaw(ic, " ")
            ic.endBatchEdit()
            autoSpaced = false
            lastSpaceAt = now
            refresh()
            return
        }
        undo = null
        if (prefs.doubleSpacePeriod && !policy.noSuggestions && now - lastSpaceAt < DOUBLE_SPACE_MS) {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                moved(-1)
                commitRaw(ic, ". ")
                ic.endBatchEdit()
                lastSpaceAt = 0L
                autoSpaced = true
                refresh()
                return
            }
        }
        if (autoSpaced) {
            // A suggestion pick already added the space.
            autoSpaced = false
            lastSpaceAt = now
            return
        }
        commitRaw(ic, " ")
        lastSpaceAt = now
        refresh()
    }

    fun onDelete() {
        val ic = ic ?: return
        lastSpaceAt = 0L
        if (dropRecorrect()) refresh()
        autoSpaced = false
        if (!composer.isEmpty) {
            if (glideActive) resetComposer() else composer.deleteLast()
            if (composer.isEmpty) {
                ic.setComposingText("", 1)
                if (composeStart >= 0) expect(composeStart) else cursor = -1
                ic.finishComposingText()
            } else {
                setComposing(ic)
            }
            refresh()
            return
        }
        val u = undo
        undo = null
        if (u != null) {
            val tail = u.committed + u.separator
            val before = ic.getTextBeforeCursor(tail.length, 0)?.toString()
            if (before == tail) {
                // Backspace right after an autocorrection restores what was typed. Committing it
                // unchanged then keeps it (see pendingKeep).
                ic.beginBatchEdit()
                ic.deleteSurroundingText(tail.length, 0)
                moved(-tail.length)
                composeStart = cursor
                composer.set(u.typed)
                composer.autocorrectSuppressed = true
                setComposing(ic)
                ic.endBatchEdit()
                if (typoLogOn()) {
                    TypoRecorder.record(ime.context, "undo", locale.language, u.typed, u.typed, u.committed, null, null, null)
                }
                pendingKeep = u
                refresh()
                return
            }
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            cursor = -1
        } else {
            // Key events let the editor delete whole grapheme clusters (emoji, accents).
            ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            moved(-1)
            resumeAfterDelete = suggestionsOn
        }
    }

    fun onEnter(action: Int?) {
        val ic = ic ?: return
        lastSpaceAt = 0L
        dropRecorrect()
        if (!composer.isEmpty) commitComposing(ic, autocorrect = true, separator = "")
        autoSpaced = false
        undo = null
        if (action != null) {
            ic.performEditorAction(action)
        } else {
            ime.sendKeyChar('\n')
            moved(1)
        }
        refresh()
    }

    // ---------------------------------------------------------------- committing

    private fun setComposing(ic: InputConnection) {
        ic.setComposingText(composer.typed, 1)
        if (composeStart >= 0) expect(composeStart + composer.length) else cursor = -1
    }

    private fun commitRaw(ic: InputConnection, text: String) {
        ic.commitText(text, 1)
        moved(text.length)
    }

    /** Commits the composing word, autocorrected if wanted, remembering how to undo it. */
    private fun commitComposing(ic: InputConnection, autocorrect: Boolean, separator: String) {
        val typed = composer.typed
        val res = currentResult()
        val auto = if (autocorrect && suggestionsOn && !glideActive) res?.autocorrect else null
        val word = auto?.word ?: typed
        val keep = pendingKeep?.takeIf { it.typed == typed && word == typed }
        val ctx = contextBefore(ic, excludeComposing = true)
        // A glide committed as decoded (no alternative picked) teaches the user's finger offsets.
        lastGlide?.let { g ->
            if (glideActive && g.word == word && prefs.adaptSwipe && !trainerField) {
                user.addKeyOffsetSamples(SwipeAdaptation.samples(g.prox, g.xs, g.ys, g.count, word))
            }
        }
        ic.commitText(word, 1)
        if (composeStart >= 0) expect(composeStart + word.length) else cursor = -1
        composeStart = -1
        resetComposer()
        result = null
        learn(ctx, word, explicit = false)
        if (keep != null) keepUndone(keep)
        val fixed = word != typed && auto != null && auto.kind != Suggestion.Kind.EXACT
        val typedIsWord = fixed && (dictionary?.findWord(typed.lowercase(locale)) ?: -1) >= 0
        // A word that got fixed was a slip; stop boosting it if it was learned ("rhe").
        if (fixed && learningAllowed()) user.demote(typed)
        undo = if (fixed) Undo(typed, word, separator, typedIsWord) else null
    }

    /** The user undid a fix and committed what they typed: they meant it. */
    private fun keepUndone(u: Undo) {
        if (typoLogOn()) TypoRecorder.record(ime.context, "keep", locale.language, u.typed, u.typed, u.committed, null, null, null)
        if (!learningAllowed()) return
        // A real-word fix ("ans" → "and") is only switched off after it is kept twice
        // (KEEP_AFTER). An unknown word is learned and confirmed at once.
        user.recordKeep(u.typed)
        if (!u.typedIsWord) user.recordWord(null, u.typed, UserStore.LEARN_AFTER)
    }

    private fun learn(ctx: Suggester.Context, text: String, explicit: Boolean) {
        if (!learningAllowed() || text.isBlank()) return
        // A missed-space fix commits several words; learn each with its own context.
        var prev = if (ctx.sentenceStart) null else ctx.prevWord
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            user.recordWord(prev, word, if (explicit) UserStore.LEARN_AFTER else 1)
            // Picking an unknown word as typed confirms it: it won't be autocorrected.
            if (explicit) user.recordKeep(word)
            prev = word
        }
    }

    // ---------------------------------------------------------------- glide and slides

    /** Shift state at the start of a glide decides the word's capitalisation. */
    enum class GlideCase { NONE, FIRST, ALL }

    fun onGlide(xs: FloatArray, ys: FloatArray, ts: IntArray, count: Int, case: GlideCase) {
        val ic = ic ?: return
        val decoder = glideDecoder ?: return
        val s = suggester ?: return
        val dict = dictionary ?: return
        val prox = proximity() ?: run {
            DiagLog.event("glide dropped: no key geometry")
            return
        }
        val adapt = prefs.adaptSwipe
        s.blockOffensive = prefs.blockOffensive
        if (trainerField) {
            // The trainer only wants the recording; it shows the result and clears its field
            // itself, so typing the word here would leave stale composing text behind.
            val target = SwipeRecorder.target ?: return
            val results = decoder.decode(
                xs, ys, count, prox, { s.languageScore(it, Suggester.Context.NONE) }, { s.isBlockedWord(it) },
                offsetX = { if (adapt) user.keyOffsetX(it) else 0f },
                offsetY = { if (adapt) user.keyOffsetY(it) else 0f },
            )
            // The trainer knows the intended word: the best possible teacher.
            if (adapt) user.addKeyOffsetSamples(SwipeAdaptation.samples(prox, xs, ys, count, target))
            SwipeRecorder.record(ime.context, locale.language, prox, xs, ys, ts, count, results.map { dict.wordOf(it.node) })
            return
        }
        lastSpaceAt = 0L
        resumeAfterDelete = false
        undo = null
        dropRecorrect()
        ic.beginBatchEdit()
        if (!composer.isEmpty) {
            commitComposing(ic, autocorrect = true, separator = " ")
            commitRaw(ic, " ")
        } else {
            // Gliding right after a word or punctuation adds the space for you.
            val before = ic.getTextBeforeCursor(1, 0)
            if (!before.isNullOrEmpty() && !before[0].isWhitespace() && before[0] !in NO_SPACE_AFTER) {
                commitRaw(ic, " ")
            }
        }
        autoSpaced = false
        val ctx = contextBefore(ic, excludeComposing = false)
        val results = decoder.decode(
            xs, ys, count, prox, { s.languageScore(it, ctx) }, { s.isBlockedWord(it) },
            offsetX = { if (adapt) user.keyOffsetX(it) else 0f },
            offsetY = { if (adapt) user.keyOffsetY(it) else 0f },
            limit = GLIDE_RESULTS,
        )
        DiagLog.event("glide decoded ${results.size} words")
        if (results.isEmpty()) {
            ic.endBatchEdit()
            refresh()
            return
        }
        val words = results.map { r ->
            val w = dict.wordOf(r.node)
            val shown = when (case) {
                GlideCase.ALL -> w.uppercase(locale)
                GlideCase.FIRST -> w.replaceFirstChar { it.titlecase(locale) }
                GlideCase.NONE -> w
            }
            Suggestion(shown, r.score, Suggestion.Kind.CORRECTION)
        }.distinctBy { it.word }.let(::twinFirst)
        composeStart = cursor
        composer.set(words[0].word)
        glideActive = true
        glideAlternatives = words.drop(1)
        lastGlide = GlideCapture(xs.copyOf(count), ys.copyOf(count), count, prox, words[0].word)
        setComposing(ic)
        ic.endBatchEdit()
        refresh()
    }

    /**
     * Doubled letters and accents don't change a swipe's path (late/latte, ten/teen, si/sí),
     * so only the language model tells such twins apart. The best twin of the top word is
     * moved to the first alternative, one tap away, instead of wherever it ranked.
     */
    private fun twinFirst(words: List<Suggestion>): List<Suggestion> {
        if (words.size < 3) return words
        val keys = swipeKeys(words[0].word)
        val twin = words.drop(1).firstOrNull { swipeKeys(it.word) == keys } ?: return words
        return listOf(words[0], twin) + words.drop(1).filter { it !== twin }
    }

    /**
     * The keys a swipe for [word] passes, in order: accents dropped, doubled letters once.
     * ñ has its own key, so it stays (año and ano are different swipes).
     */
    private fun swipeKeys(word: String): String {
        val sb = StringBuilder()
        for (ch in word.lowercase(locale)) {
            val c = if (ch == 'ñ') ch else java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFD)[0]
            if (!c.isLetter()) continue
            if (sb.isEmpty() || sb.last() != c) sb.append(c)
        }
        return sb.toString()
    }

    /** Spacebar slide: moves the cursor by [steps] characters. */
    fun moveCursor(steps: Int) {
        val ic = ic ?: return
        if (steps == 0) return
        if (!composer.isEmpty) {
            ic.finishComposingText()
            resetComposer()
            composeStart = -1
        }
        undo = null
        autoSpaced = false
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { ime.sendDownUpKeyEvents(code) }
        cursor = -1
        recentExpected.clear()
    }

    /** Delete slide: selects the last [words] words before where the slide started. */
    fun deleteSlide(words: Int) {
        val ic = ic ?: return
        if (deleteAnchor < 0) {
            if (!composer.isEmpty) {
                ic.finishComposingText()
                resetComposer()
                composeStart = -1
            }
            val et = ic.getExtractedText(ExtractedTextRequest(), 0)
            deleteAnchor = et?.let { it.startOffset + it.selectionEnd } ?: cursor
            if (deleteAnchor < 0) return
            deleteText = ic.getTextBeforeCursor(DELETE_SLIDE_MAX, 0)?.toString() ?: ""
        }
        val t = deleteText
        var i = t.length
        repeat(words) {
            while (i > 0 && t[i - 1].isWhitespace()) i--
            while (i > 0 && !t[i - 1].isWhitespace()) i--
        }
        ic.setSelection(deleteAnchor - (t.length - i), deleteAnchor)
        cursor = -1
        recentExpected.clear()
    }

    fun deleteSlideEnd() {
        val anchor = deleteAnchor
        deleteAnchor = -1
        deleteText = ""
        val ic = ic ?: return
        if (anchor < 0) return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        undo = null
        autoSpaced = false
        cursor = -1
        recentExpected.clear()
    }

    // ---------------------------------------------------------------- clipboard

    /** Pastes [text] at the cursor, finishing any word being typed first. */
    fun paste(text: String) {
        insert(text)
        clipboard?.consumeFresh()
        refresh()
    }

    /** Types an emoji from the emoji panel; the word in progress is kept as typed. */
    fun onEmoji(text: String) {
        insert(text)
        refresh()
    }

    private fun insert(text: String) {
        val ic = ic ?: return
        dropRecorrect()
        lastSpaceAt = 0L
        resumeAfterDelete = false
        undo = null
        autoSpaced = false
        ic.beginBatchEdit()
        if (!composer.isEmpty) commitComposing(ic, autocorrect = false, separator = "")
        undo = null
        commitRaw(ic, text)
        ic.endBatchEdit()
    }

    /**
     * The clipboard panel is about to run an editor action (select all, cut, copy, paste):
     * finish the word in progress and stop trusting the expected cursor.
     */
    fun beforeEditorAction() {
        dropRecorrect()
        if (!composer.isEmpty) {
            ic?.finishComposingText()
            resetComposer()
            composeStart = -1
            result = null
        }
        undo = null
        autoSpaced = false
        resumeAfterDelete = false
        cursor = -1
        recentExpected.clear()
    }

    override fun onClipboardButton() = ime.toggleClipboard()

    override fun onClipChipPicked() {
        clipboard?.freshClip()?.let { paste(it) }
    }

    // ---------------------------------------------------------------- strip

    override fun onSuggestionPicked(slot: Int) {
        val ic = ic ?: return
        val pick = slots.getOrNull(slot) ?: return
        recorrect?.let {
            applyRecorrect(ic, it, pick)
            return
        }
        lastSpaceAt = 0L
        undo = null
        ic.beginBatchEdit()
        if (!composer.isEmpty) {
            val ctx = contextBefore(ic, excludeComposing = true)
            ic.commitText(pick.word, 1)
            if (composeStart >= 0) expect(composeStart + pick.word.length) else cursor = -1
            composeStart = -1
            // Choosing the literal typing of an unknown word teaches it immediately.
            val explicit = pick.kind == Suggestion.Kind.TYPED && result?.typedValid == false
            if (typoLogOn() && !glideActive) {
                TypoRecorder.record(
                    ime.context, "pick", locale.language, composer.typed, pick.word, result?.autocorrect?.word,
                    if (ctx.sentenceStart) null else ctx.prevWord, composer, proximity(),
                )
            }
            resetComposer()
            result = null
            learn(ctx, pick.word, explicit)
        } else {
            val ctx = contextBefore(ic, excludeComposing = false)
            val before = ic.getTextBeforeCursor(1, 0)
            if (!before.isNullOrEmpty() && isWordChar(before[0])) commitRaw(ic, " ")
            commitRaw(ic, pick.word)
            learn(ctx, pick.word, explicit = false)
        }
        commitRaw(ic, " ")
        ic.endBatchEdit()
        autoSpaced = true
        refresh()
    }

    override fun onSuggestionLongPressed(slot: Int) {
        val s = slots.getOrNull(slot) ?: return
        if (s.kind == Suggestion.Kind.TYPED) return
        strip?.showBlockPrompt(s.word)
    }

    override fun onBlockAnswered(word: String, block: Boolean) {
        if (block) user.block(word)
        refresh()
    }

    override fun onOpenSettings() = ime.openSettings()

    // ---------------------------------------------------------------- selection

    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        val collapsed = newSelStart == newSelEnd
        // The editor reports exactly our composing word ending at the cursor: in sync.
        if (collapsed && !composer.isEmpty && candidatesStart >= 0 && candidatesEnd == newSelStart &&
            candidatesEnd - candidatesStart == composer.length
        ) {
            composeStart = candidatesStart
            if (cursor != newSelStart && newSelStart !in recentExpected) expect(newSelStart)
            return
        }
        if (collapsed && cursor < 0 && composer.isEmpty) {
            cursor = newSelStart
            if (deleteAnchor < 0) {
                dropRecorrect()
                refresh()
                scheduleRecorrect()
            }
            return
        }
        val ours = collapsed && (newSelStart == cursor || newSelStart in recentExpected)
        if (ours) {
            if (newSelStart == cursor) {
                recentExpected.clear()
                if (resumeAfterDelete) {
                    resumeAfterDelete = false
                    resumeComposing(newSelStart)
                    if (composer.isEmpty) refresh()
                }
            }
            return
        }
        // The user moved the cursor or selected text, or the app changed the text.
        cursor = if (collapsed) newSelStart else -1
        recentExpected.clear()
        resumeAfterDelete = false
        if (!composer.isEmpty) {
            ic?.finishComposingText()
            resetComposer()
            composeStart = -1
            result = null
        }
        undo = null
        autoSpaced = false
        dropRecorrect()
        refresh()
        if (collapsed) scheduleRecorrect()
    }

    // ---------------------------------------------------------------- tap-to-fix

    /** Looks for fixes once the cursor settles (a space-bar slide moves it many times). */
    private fun scheduleRecorrect() {
        handler.removeCallbacks(recorrectTask)
        if (prefs.tapToFix && suggestionsOn) handler.postDelayed(recorrectTask, RECORRECT_DELAY_MS)
    }

    /** Forgets the tapped word; true if there was one (the strip needs a refresh). */
    private fun dropRecorrect(): Boolean {
        handler.removeCallbacks(recorrectTask)
        val had = recorrect != null
        recorrect = null
        return had
    }

    /** The word touching the cursor, if any: suggestions for it go in the strip. */
    private fun findRecorrect() {
        val ic = ic ?: return
        val s = suggester ?: return
        val pos = cursor
        if (pos < 0 || !composer.isEmpty || deleteAnchor >= 0 || !suggestionsOn) return
        val before = ic.getTextBeforeCursor(MAX_WORD, 0)?.toString() ?: return
        val after = ic.getTextAfterCursor(MAX_WORD, 0)?.toString() ?: ""
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        var j = 0
        while (j < after.length && isWordChar(after[j])) j++
        // Quotes around the word ('hello') aren't part of it.
        while (i < before.length && !before[i].isLetter()) i++
        while (j > 0 && !after[j - 1].isLetter()) j--
        val left = if (i < before.length) before.substring(i) else ""
        val right = after.substring(0, j)
        val word = left + right
        if (word.isEmpty() || !word[0].isLetter() || !word.last().isLetter() || word.length >= MAX_WORD) return
        if (i == 0 && before.length >= MAX_WORD) return
        s.blockOffensive = prefs.blockOffensive
        val ctx = contextOf(before.substring(0, i))
        val res = s.suggest(Composer().apply { set(word) }, ctx, null, prefs.autocorrect)
        if (res.ranked.isEmpty()) return
        recorrect = Recorrect(pos - left.length, pos + right.length, word, ctx, res)
        refresh()
    }

    /** Swaps the tapped word for [pick]; the cursor goes to the end of the new word. */
    private fun applyRecorrect(ic: InputConnection, r: Recorrect, pick: Suggestion) {
        dropRecorrect()
        if (pick.word == r.word) {
            refresh()
            return
        }
        // Both the region change and the commit echo back; neither is a user cursor move.
        if (cursor >= 0) expect(cursor)
        ic.beginBatchEdit()
        ic.setComposingRegion(r.start, r.end)
        ic.commitText(pick.word, 1)
        ic.endBatchEdit()
        expect(r.start + pick.word.length)
        if (typoLogOn()) {
            TypoRecorder.record(ime.context, "tapfix", locale.language, r.word, pick.word, r.res.autocorrect?.word, r.ctx.prevWord, null, null)
        }
        learn(r.ctx, pick.word, explicit = pick.kind == Suggestion.Kind.TYPED)
        refresh()
    }

    /** After backspacing to the end of a word, offers corrections for it again. */
    private fun resumeComposing(pos: Int) {
        val ic = ic ?: return
        if (!suggestionsOn || suggester == null) return
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && isWordChar(after[0])) return
        val before = ic.getTextBeforeCursor(MAX_WORD, 0)?.toString() ?: return
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        val word = before.substring(i)
        if (word.isEmpty() || word.length >= MAX_WORD || !word[0].isLetter()) return
        composeStart = pos - word.length
        if (composeStart < 0) return
        ic.setComposingRegion(composeStart, pos)
        composer.set(word)
        refresh()
    }

    // ---------------------------------------------------------------- suggestions

    private fun proximity(): KeyProximity? {
        val g = geometryProvider() ?: return proximity
        if (g !== proximityFor) {
            proximityFor = g
            proximity = KeyProximity(g)
        }
        return proximity
    }

    private fun currentResult(): SuggestResult? {
        if (composer.isEmpty || glideActive) return null
        result?.let { if (it.typed == composer.typed) return it }
        val s = suggester ?: return null
        val ic = ic ?: return null
        s.blockOffensive = prefs.blockOffensive
        return s.suggest(composer, contextBefore(ic, excludeComposing = true), proximity(), prefs.autocorrect)
            .also { result = it }
    }

    /** Recomputes suggestions (or next-word predictions) and updates the strip. */
    fun refresh() {
        val strip = strip ?: return
        strip.setIncognito(!learningAllowed())
        strip.setClip(null)
        if (!suggestionsOn || suggester == null) {
            slots = listOf(null, null, null)
            strip.clear()
            return
        }
        recorrect?.let {
            if (composer.isEmpty) {
                showResult(strip, it.res)
                return
            }
            recorrect = null
        }
        if (!composer.isEmpty && glideActive) {
            val alts = glideAlternatives.filter { it.word != composer.typed }
            val best = Suggestion(composer.typed, 0f, Suggestion.Kind.EXACT)
            slots = listOf(alts.getOrNull(0), best, alts.getOrNull(1))
            strip.setItems(slots.mapIndexed { i, sg -> sg?.let { SuggestionStripView.Item(it.word, bold = i == 1) } })
            return
        }
        if (!composer.isEmpty) {
            val res = currentResult() ?: return
            showResult(strip, res)
            return
        }
        val ic = ic
        predictions = if (prefs.nextWordPrediction && ic != null) {
            suggester?.predict(contextBefore(ic, excludeComposing = false), capsProvider()) ?: emptyList()
        } else {
            emptyList()
        }
        slots = listOf(predictions.getOrNull(1), predictions.getOrNull(0), predictions.getOrNull(2))
        strip.setItems(slots.mapIndexed { i, s -> s?.let { SuggestionStripView.Item(it.word, bold = i == 1) } })
        if (prefs.clipboardChip) strip.setClip(clipboard?.freshClip())
    }

    private fun showResult(strip: SuggestionStripView, res: SuggestResult) {
        val typed = Suggestion(res.typed, 0f, Suggestion.Kind.TYPED)
        val auto = res.autocorrect
        val others = res.ranked.filter { it.word != res.typed && it !== auto }
        val items: List<SuggestionStripView.Item?>
        if (auto != null) {
            slots = listOf(typed, auto, others.getOrNull(0))
            items = listOf(
                SuggestionStripView.Item(res.typed, quoted = true),
                SuggestionStripView.Item(auto.word, bold = true),
                others.getOrNull(0)?.let { SuggestionStripView.Item(it.word) },
            )
        } else {
            slots = listOf(others.getOrNull(0), typed, others.getOrNull(1))
            items = listOf(
                others.getOrNull(0)?.let { SuggestionStripView.Item(it.word) },
                SuggestionStripView.Item(res.typed, bold = res.typedValid, quoted = !res.typedValid),
                others.getOrNull(1)?.let { SuggestionStripView.Item(it.word) },
            )
        }
        strip.setItems(items)
    }

    /** The word before the cursor (or sentence start) for next-word context. */
    private fun contextBefore(ic: InputConnection, excludeComposing: Boolean): Suggester.Context {
        val raw = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: return Suggester.Context.NONE
        val text = if (excludeComposing && !composer.isEmpty && raw.length >= composer.length) {
            raw.substring(0, raw.length - composer.length)
        } else {
            raw
        }
        return contextOf(text)
    }

    /** Next-word context from the text before a word. */
    private fun contextOf(text: String): Suggester.Context {
        val t = text.trimEnd(' ')
        if (t.isEmpty() || t.last() in ".!?\n¡¿") return Suggester.Context(null, sentenceStart = true)
        if (!isWordChar(t.last())) return Suggester.Context.NONE
        var i = t.length
        while (i > 0 && isWordChar(t[i - 1])) i--
        return Suggester.Context(t.substring(i), sentenceStart = false)
    }

    private fun isWordText(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.all { it.isLetter() }) return true
        // Apostrophes join words ("don't") but don't start them.
        return !composer.isEmpty && text.all { it == '\'' || it == '’' }
    }

    private fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '’'

    companion object {
        private const val DOUBLE_SPACE_MS = 500L
        private const val CONTEXT_CHARS = 64
        private const val MAX_WORD = 32
        private val AUTOCORRECT_TRIGGERS = setOf(".", ",", "!", "?", ";", ":", ")", "\"", "…")
        /** Punctuation that sticks to the previous word and is followed by a space. */
        private val SPACE_AFTER = setOf(".", ",", "!", "?", ";", ":")
        /** No automatic space is added before a glided word after these. */
        private const val NO_SPACE_AFTER = "([{\"'¿¡/@#-_\n"
        private const val DELETE_SLIDE_MAX = 2000
        private const val RECORRECT_DELAY_MS = 150L
        /** Glide results asked for: a few more than the strip shows, so a twin can be found. */
        private const val GLIDE_RESULTS = 8
    }
}
