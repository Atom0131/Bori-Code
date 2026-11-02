package com.bori.app.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.bori.app.data.prefs.TerminalKeysSettings
import com.bori.core.keyboard.clipboard.ClipboardHistory
import com.bori.core.keyboard.dict.Dictionaries
import com.bori.core.keyboard.ime.EditorPolicy
import com.bori.core.keyboard.ime.Feedback
import com.bori.core.keyboard.ime.InputHost
import com.bori.core.keyboard.ime.TypingSession
import com.bori.core.keyboard.layout.Key
import com.bori.core.keyboard.layout.KeyCode
import com.bori.core.keyboard.layout.Language
import com.bori.core.keyboard.layout.LayoutId
import com.bori.core.keyboard.layout.Layouts
import com.bori.core.keyboard.learn.UserStore
import com.bori.core.keyboard.predict.Aggressiveness
import com.bori.core.keyboard.settings.Prefs
import com.bori.core.keyboard.theme.KeyboardTheme
import com.bori.core.keyboard.view.ClipboardPanelView
import com.bori.core.keyboard.view.EmojiPanelView
import com.bori.core.keyboard.view.InputRootView
import com.bori.core.keyboard.view.KeyboardView
import com.bori.core.keyboard.view.ShiftState

/**
 * The type bar's text field: one line of a command or a prompt, sent to the terminal as a whole.
 * Like an app's field seen from a keyboard, it reports cursor and composing-word moves, but only
 * once per turn of the main loop (after an edit has finished), as Android does for an IME.
 */
@SuppressLint("AppCompatCustomView", "ViewConstructor")
class TerminalLineEdit(context: Context) : EditText(context) {
    var onSelection: ((selStart: Int, selEnd: Int, composeStart: Int, composeEnd: Int) -> Unit)? = null
    private var reportPending = false

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (reportPending) return
        reportPending = true
        post {
            reportPending = false
            val t = text ?: return@post
            onSelection?.invoke(
                selectionStart, selectionEnd,
                BaseInputConnection.getComposingSpanStart(t), BaseInputConnection.getComposingSpanEnd(t),
            )
        }
    }
}

/**
 * Bori Keyboard inside the terminal: its own keys, suggestion strip, swipe trail, emoji and
 * clipboard panels ([InputRootView]) and its [TypingSession], typing into the type bar
 * ([edit]) the way the keyboard app types into any app's field, so correction, undo, glide,
 * tap-to-fix and learning all work as they do there. Enter sends the line to the terminal; Enter,
 * Backspace and the cursor slide on an empty bar go to the terminal itself. Keys with Ctrl or Alt
 * pending, and every key in [direct] mode, go straight to the terminal (for single-key prompts).
 * This plays the part of Bori Keyboard's `BoriImeService`.
 */
class BoriKeysController(
    override val context: Context,
    private val terminal: Terminal,
) : InputHost, KeyboardView.Listener, ClipboardPanelView.Listener, EmojiPanelView.Listener {

    interface Terminal {
        /** Types [line] and presses Enter; an empty line is just Enter. */
        fun sendLine(line: String)
        fun key(code: Int)
        fun text(s: String)
        /** One-shot Ctrl or Alt is waiting for the next key. */
        fun modifierPending(): Boolean
        /** The focused pane's cursor sits at a shell prompt (commands aren't autocorrected there). */
        fun atShellPrompt(): Boolean
        fun openSettings()
        fun usePhoneKeyboard()
    }

    private val prefs = Prefs(context, PREFS_FILE)
    private val feedback = Feedback(context, prefs)
    private val user = UserStore.get(context)
    private val dictionaries = dictionaries(context)
    private val clipboard = ClipboardHistory(context)
    private val session = TypingSession(this, prefs, user)
    val root = InputRootView(context)
    val edit = TerminalLineEdit(context)

    /** Keys go straight to the terminal, letter by letter. */
    var direct = false

    /** The type bar was tapped: the keyboard in use should come up. */
    var onBarTapped: (() -> Unit)? = null

    private var info = EditorInfo()
    private var ic: InputConnection? = null
    private var policy = EditorPolicy.DEFAULT
    private var started = false
    private var language = Language.EN
    private var dictLanguage: Language? = null
    private var userLevel = Aggressiveness.NORMAL
    private var layoutId = LayoutId.ALPHA
    private var shift = ShiftState.OFF
    private var lastShiftDownAt = 0L
    private var themeNight: Boolean? = null

    override val currentInputConnection: InputConnection? get() = ic

    init {
        edit.apply {
            isSingleLine = true
            // Commands: no capital first letter. Bori's keys correct, the phone's keyboard may.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, _, _ -> submit(); true }
            // The phone's keyboard on an empty bar: Backspace and Enter reach the terminal.
            setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN || !text.isNullOrEmpty()) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DEL -> { terminal.key(KeyEvent.KEYCODE_DEL); true }
                    KeyEvent.KEYCODE_ENTER -> { submit(); true }
                    else -> false
                }
            }
            setOnTouchListener { _, e ->
                if (e.action == android.view.MotionEvent.ACTION_UP) onBarTapped?.invoke()
                false
            }
            onSelection = { s, e, cs, ce -> if (started) session.onUpdateSelection(s, e, cs, ce); updateAutoShift() }
        }
        clipboard.apply {
            recording = { prefs.clipboardHistory && !prefs.incognito }
            onChanged = {
                if (root.clipboardOpen) root.clipboard.setClips(items())
                session.refresh()
            }
            startListening()
        }
        session.capsProvider = { sentenceCapsNow() }
        session.geometryProvider = { root.keyboard.currentGeometry()?.takeIf { layoutId == LayoutId.ALPHA } }
        session.clipboard = clipboard
        root.keyboard.listener = this
        root.strip.listener = session
        root.clipboard.listener = this
        root.emoji.listener = this
        session.strip = root.strip
        // The terminal's own settings sheet chooses the split; never the keyboard app's prompt.
        prefs.splitAsked = true
    }

    /** The keys are showing: begin typing into the bar (like a keyboard opening on a field). */
    fun start() {
        if (started) return
        started = true
        info = EditorInfo()
        ic = edit.onCreateInputConnection(info)
        policy = EditorPolicy.from(info)
        layoutId = LayoutId.ALPHA
        shift = ShiftState.OFF
        root.showClipboard(false)
        root.showEmoji(false)
        applyTheme()
        applyKeyPrefs()
        session.start(info, policy)
        loadDictionary()
        loadLayout()
        updateAutoShift()
    }

    /** The keys went away (hidden, or the phone's keyboard took over). */
    fun stop() {
        if (!started) return
        root.keyboard.cancelAll()
        root.showClipboard(false)
        root.showEmoji(false)
        session.finish()
        started = false
        ic = null
    }

    fun dispose() {
        stop()
        clipboard.stopListening()
    }

    /** The terminal's keyboard settings, applied to Bori Keyboard's own. */
    fun apply(s: TerminalKeysSettings) {
        prefs.keyHeightScale = s.height
        prefs.splitOnBigScreens = s.split != false
        prefs.splitGap = s.gap
        prefs.splitEdge = s.edge
        prefs.haptics = s.vibration > 0
        if (s.vibration > 0) prefs.vibrationStrength = s.vibration
        prefs.numberRow = s.numberRow
        userLevel = runCatching { Aggressiveness.valueOf(s.autocorrect) }.getOrDefault(Aggressiveness.NORMAL)
        language = Language.fromTag(s.language)
        if (started) {
            applyKeyPrefs()
            loadDictionary()
            loadLayout()
        }
    }

    fun previewVibration(strength: Int) {
        if (strength > 0) feedback.vibrate(strength)
    }

    /** The top bar's Enter: the line if there is one, else Enter in the terminal. */
    fun enter() {
        if (edit.text.isNullOrEmpty()) terminal.key(KeyEvent.KEYCODE_ENTER) else session.onEnter(policy.enterAction ?: EditorInfo.IME_ACTION_SEND)
    }

    /** Text from the top bar's symbol keys. */
    fun typeSymbol(text: String) {
        if (direct || terminal.modifierPending() || !started) terminal.text(text) else session.onText(text, Float.NaN, Float.NaN)
    }

    /** Sends the bar's line and starts a fresh one. */
    private fun submit() {
        val line = edit.text?.toString().orEmpty()
        val wasStarted = started
        stop()
        edit.setText("")
        terminal.sendLine(line)
        if (wasStarted) start()
    }

    private fun applyTheme() {
        val night = KeyboardTheme.wantsDark(context, prefs.themeMode)
        if (themeNight != night) {
            root.applyTheme(KeyboardTheme.forContext(context, night))
            themeNight = night
        }
    }

    private fun applyKeyPrefs() {
        val k = root.keyboard
        k.keyPreviewEnabled = prefs.keyPreview
        k.keyHeightScale = prefs.keyHeightScale
        k.heightRows = if (prefs.numberRow) 5 else 4
        k.splitOnBigScreens = prefs.splitOnBigScreens
        k.setSplitSpacing(prefs.splitGap, prefs.splitEdge)
        k.glideTrail = prefs.glideTrail
        k.spaceCursorEnabled = prefs.spaceCursor
        k.deleteSlideEnabled = prefs.deleteSlide
        k.keyFlicksEnabled = prefs.keyFlicks
    }

    private fun loadDictionary() {
        val lang = language
        if (dictLanguage == lang) {
            dictionaries.background { user.load(lang.tag) }
            return
        }
        dictLanguage = lang
        session.setDictionary(null, lang.locale)
        dictionaries.background { user.load(lang.tag) }
        dictionaries.get(lang) { dict ->
            if (language == lang) {
                session.setDictionary(dict, lang.locale)
                updateGlide()
            }
        }
    }

    private fun loadLayout() {
        // The globe key is the phone's keyboard (for voice typing and the like).
        root.keyboard.setKeyboard(Layouts.build(layoutId, language, policy.variant, true, prefs.numberRow), language.locale)
        updateGlide()
        refreshKeyboardState()
    }

    private fun updateGlide() {
        root.keyboard.glideEnabled = prefs.glideTyping && layoutId == LayoutId.ALPHA && !direct && session.canGlide()
    }

    private fun refreshKeyboardState() {
        val space = if (layoutId != LayoutId.ALPHA) "" else language.displayName
        root.keyboard.setState(shift, EditorPolicy.enterLabel(policy.enterAction), space)
    }

    private fun sentenceCapsNow(): Boolean {
        val ic = ic ?: return false
        return prefs.autoCapitalize && policy.autoCapAllowed && ic.getCursorCapsMode(info.inputType) != 0
    }

    private fun updateAutoShift() {
        if (shift == ShiftState.LOCKED || !started) return
        val next = if (layoutId == LayoutId.ALPHA && sentenceCapsNow()) ShiftState.ON else ShiftState.OFF
        if (next != shift) {
            shift = next
            refreshKeyboardState()
        }
    }

    /** At a shell prompt words are suggested but never replaced: commands and file names stay as typed. */
    private fun syncAutocorrect() {
        val level = if (terminal.atShellPrompt()) Aggressiveness.OFF else userLevel
        if (prefs.autocorrect != level) prefs.autocorrect = level
    }

    // ---------------------------------------------------------------- InputHost

    override fun sendDownUpKeyEvents(keyCode: Int) {
        val toTerminal = edit.text.isNullOrEmpty() && keyCode in TERMINAL_WHEN_EMPTY
        if (toTerminal) {
            terminal.key(keyCode)
            return
        }
        val ic = ic ?: return
        val t = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, flags))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, flags))
    }

    override fun sendKeyChar(c: Char) {
        if (c == '\n') submit() else ic?.commitText(c.toString(), 1)
    }

    override fun toggleClipboard() {
        if (root.clipboardOpen) {
            root.showClipboard(false)
            session.refresh()
            return
        }
        clipboard.capture()
        session.beforeEditorAction()
        root.clipboard.setClips(clipboard.items())
        root.showClipboard(true)
        root.strip.clear()
    }

    override fun openSettings() = terminal.openSettings()

    // ---------------------------------------------------------------- keys

    override fun onKeyPressed(key: Key) {
        feedback.onPress(key)
        syncAutocorrect()
    }

    override fun onKey(key: Key, x: Float, y: Float) {
        val raw = direct || terminal.modifierPending()
        when (val code = key.code) {
            is KeyCode.Text -> typeText(code.text, key.isLetter, x, y, raw)
            is KeyCode.Switch -> {
                layoutId = code.target
                shift = ShiftState.OFF
                loadLayout()
                updateAutoShift()
            }
            KeyCode.Shift -> onShift()
            KeyCode.Delete -> if (raw) terminal.key(KeyEvent.KEYCODE_DEL) else session.onDelete()
            KeyCode.Enter -> if (raw) terminal.key(KeyEvent.KEYCODE_ENTER) else session.onEnter(policy.enterAction)
            KeyCode.Space -> {
                if (raw) terminal.text(" ") else session.onSpace()
                if (layoutId == LayoutId.SYMBOLS) {
                    layoutId = LayoutId.ALPHA
                    loadLayout()
                    updateAutoShift()
                }
            }
            KeyCode.Globe -> terminal.usePhoneKeyboard()
            KeyCode.Emoji -> toggleEmoji()
        }
    }

    override fun onPopupText(text: String) =
        typeText(text, isLetter = false, x = Float.NaN, y = Float.NaN, raw = direct || terminal.modifierPending())

    override fun onKeyLongPress(key: Key): Boolean = false

    override fun onGlide(xs: FloatArray, ys: FloatArray, ts: IntArray, count: Int) {
        syncAutocorrect()
        val case = when (shift) {
            ShiftState.LOCKED -> TypingSession.GlideCase.ALL
            ShiftState.ON -> TypingSession.GlideCase.FIRST
            ShiftState.OFF -> TypingSession.GlideCase.NONE
        }
        session.onGlide(xs, ys, ts, count, case)
        if (shift == ShiftState.ON) {
            shift = ShiftState.OFF
            refreshKeyboardState()
        }
    }

    override fun onCursorMove(steps: Int) = session.moveCursor(steps)

    override fun onDeleteSlide(words: Int) = session.deleteSlide(words)

    override fun onDeleteSlideEnd() = session.deleteSlideEnd()

    private fun typeText(text: String, isLetter: Boolean, x: Float, y: Float, raw: Boolean) {
        val out = if (shift != ShiftState.OFF && isLetter) text.uppercase(language.locale) else text
        if (raw) terminal.text(out) else session.onText(out, x, y)
        if (shift == ShiftState.ON) {
            shift = ShiftState.OFF
            refreshKeyboardState()
        }
    }

    private fun onShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == ShiftState.LOCKED -> ShiftState.OFF
            now - lastShiftDownAt < DOUBLE_TAP_MS -> ShiftState.LOCKED
            shift == ShiftState.ON -> ShiftState.OFF
            else -> ShiftState.ON
        }
        lastShiftDownAt = now
        refreshKeyboardState()
    }

    // ---------------------------------------------------------------- clipboard and emoji panels

    override fun onClipPaste(text: String) {
        root.showClipboard(false)
        session.paste(text)
    }

    override fun onClipLongPress(clip: ClipboardHistory.Clip) {
        val preview = clip.text.trim().replace(Regex("\\s+"), " ").take(24)
        root.strip.showPrompt("“$preview”", if (clip.pinned) "Unpin" else "Pin", "Delete", sticky = false) { pin ->
            if (pin) clipboard.togglePin(clip.text) else clipboard.delete(clip.text)
        }
    }

    override fun onClipAction(action: Int) {
        val ic = ic ?: return
        if (action == ClipboardPanelView.ACTION_CLEAR) {
            clipboard.clearUnpinned()
            return
        }
        session.beforeEditorAction()
        val id = when (action) {
            ClipboardPanelView.ACTION_SELECT_ALL -> android.R.id.selectAll
            ClipboardPanelView.ACTION_CUT -> android.R.id.cut
            ClipboardPanelView.ACTION_COPY -> android.R.id.copy
            else -> android.R.id.paste
        }
        ic.performContextMenuAction(id)
        if (action == ClipboardPanelView.ACTION_PASTE) {
            root.showClipboard(false)
            clipboard.consumeFresh()
            session.refresh()
        }
    }

    private fun toggleEmoji() {
        if (root.emojiOpen) {
            onEmojiClose()
            return
        }
        root.emoji.open(prefs.recentEmoji)
        root.showEmoji(true)
    }

    override fun onEmoji(text: String) {
        feedback.onPress(EMOJI_KEY)
        session.onEmoji(text)
        if (!prefs.incognito && !policy.noLearning) {
            val recent = (listOf(text) + prefs.recentEmoji.filter { it != text }).take(MAX_RECENT_EMOJI)
            prefs.recentEmoji = recent
            root.emoji.setRecents(recent)
        }
    }

    override fun onEmojiDelete() {
        feedback.onPress(DELETE_KEY)
        session.onDelete()
    }

    override fun onEmojiClose() {
        root.showEmoji(false)
        updateAutoShift()
        session.refresh()
    }

    /** Puts the cursor in the bar; with [phoneKeyboard], the phone's keyboard comes up for it. */
    fun focusBar(phoneKeyboard: Boolean) {
        edit.showSoftInputOnFocus = phoneKeyboard
        edit.requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (phoneKeyboard) imm?.showSoftInput(edit, 0) else imm?.hideSoftInputFromWindow(edit.windowToken, 0)
    }

    companion object {
        /** Bori Keyboard's settings file for the terminal (the keyboard app's own is "bori_settings"). */
        private const val PREFS_FILE = "terminal_keyboard"
        private const val DOUBLE_TAP_MS = 300L
        private const val MAX_RECENT_EMOJI = 32
        private val TERMINAL_WHEN_EMPTY = setOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        private val EMOJI_KEY = Key("", KeyCode.Text(""))
        private val DELETE_KEY = Key("⌫", KeyCode.Delete)

        @Volatile private var shared: Dictionaries? = null

        /** One loader for the app, keeping one language in memory (about 20 MB). */
        private fun dictionaries(context: Context): Dictionaries =
            shared ?: synchronized(this) {
                shared ?: Dictionaries(context.applicationContext, maxLanguages = 1).also { shared = it }
            }
    }
}
