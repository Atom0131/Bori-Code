package com.bori.app.ui.terminal

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key as KeyName
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bori.app.terminal.WordPredictor
import com.bori.app.ui.theme.BoriColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_EVERY_MS = 50L
private const val CAPS_LOCK_TAP_MS = 350L
private const val ECHO_WAIT_MS = 300L

private sealed interface Act {
    /** Types [text]; a letter follows Shift. */
    data class Type(val text: String) : Act
    data class Code(val code: Int) : Act
    data object Shift : Act
    data object Layer : Act
    data object Phone : Act
}

private class K(
    val label: String,
    val act: Act,
    val weight: Float = 1f,
    val repeats: Boolean = false,
    val description: String = label,
    val small: Boolean = false,
) {
    /** A letter a swipe can pass through. */
    val letter: Char? = (act as? Act.Type)?.text?.singleOrNull()?.takeIf { it in 'a'..'z' }
}

private fun chars(keys: String) = keys.split(' ').map { K(it, Act.Type(it)) }
private fun code(label: String, code: Int, description: String, repeats: Boolean = false, weight: Float = 1f) =
    K(label, Act.Code(code), weight = weight, repeats = repeats, description = description, small = label.length > 1)

private val DEL = code("⌫", KeyEvent.KEYCODE_DEL, "Backspace", repeats = true)
private val PHONE = K("", Act.Phone, description = "Phone keyboard and typing box")
private val NUMBERS = chars("1 2 3 4 5 6 7 8 9 0")
private val F_KEYS = (1..12).map { n -> code("F$n", KeyEvent.KEYCODE_F1 + n - 1, "F$n") }

/** Arrows sit by Enter, so picking from a CLI's menu is ↑ ↓ ↵ with one thumb. */
private val LETTERS = listOf(
    NUMBERS,
    chars("q w e r t y u i o p"),
    chars("a s d f g h j k l") + DEL,
    listOf(K("⇧", Act.Shift, description = "Shift")) + chars("z x c v b n m . /"),
    listOf(
        K("?#", Act.Layer, weight = 1.1f, description = "Symbols"),
        PHONE,
        K("-", Act.Type("-"), weight = 0.9f),
        K(" ", Act.Type(" "), weight = 2.1f, repeats = true, description = "Space"),
        code("←", KeyEvent.KEYCODE_DPAD_LEFT, "Left", repeats = true, weight = 0.85f),
        code("↓", KeyEvent.KEYCODE_DPAD_DOWN, "Down", repeats = true, weight = 0.85f),
        code("↑", KeyEvent.KEYCODE_DPAD_UP, "Up", repeats = true, weight = 0.85f),
        code("→", KeyEvent.KEYCODE_DPAD_RIGHT, "Right", repeats = true, weight = 0.85f),
        K("↵", Act.Code(KeyEvent.KEYCODE_ENTER), weight = 1.5f, description = "Enter"),
    ),
)

private val NAV = listOf(
    code("Home", KeyEvent.KEYCODE_MOVE_HOME, "Home"),
    code("End", KeyEvent.KEYCODE_MOVE_END, "End"),
    code("PgUp", KeyEvent.KEYCODE_PAGE_UP, "Page up"),
    code("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, "Page down"),
)

private val SYMBOLS = listOf(
    F_KEYS,
    chars("! @ # $ % ^ & * ( )"),
    chars("` ' \" : ; [ ] { }") + DEL,
    NAV + code("Del", KeyEvent.KEYCODE_FORWARD_DEL, "Delete") + chars("< > = _ \\"),
    listOf(K("abc", Act.Layer, weight = 1.25f, description = "Letters"), PHONE) + chars("~ | + , ?") +
        K(" ", Act.Type(" "), weight = 1.5f, repeats = true, description = "Space") +
        K("↵", Act.Code(KeyEvent.KEYCODE_ENTER), weight = 1.25f, description = "Enter"),
)

/** A row split in two for a wide keyboard: each half sits at its edge, under a thumb. */
private class Halves(val left: List<K>, val right: List<K>)

private fun space(weight: Float) = K(" ", Act.Type(" "), weight = weight, repeats = true, description = "Space")
private fun enter(weight: Float) = K("↵", Act.Code(KeyEvent.KEYCODE_ENTER), weight = weight, description = "Enter")
private fun halves(row: List<K>, at: Int) = Halves(row.take(at), row.drop(at))

private val LETTERS_SPLIT = listOf(
    halves(LETTERS[0], 5),
    halves(LETTERS[1], 5),
    halves(LETTERS[2], 5),
    halves(LETTERS[3], 5),
    // Space on both sides, the arrows and Enter on the right.
    Halves(
        listOf(LETTERS[4][0], PHONE, LETTERS[4][2], space(2.2f)),
        listOf(space(1f)) + LETTERS[4].subList(4, 8).map { code(it.label, (it.act as Act.Code).code, it.description, repeats = true, weight = 0.75f) } + enter(1f),
    ),
)

private val SYMBOLS_SPLIT = listOf(
    halves(SYMBOLS[0], 6),
    halves(SYMBOLS[1], 5),
    halves(SYMBOLS[2], 5),
    halves(SYMBOLS[3], 5),
    Halves(SYMBOLS[4].take(5), SYMBOLS[4].drop(5).take(2) + space(1.75f) + enter(1.25f)),
)

/** From this width the keys split in two, as on an unfolded foldable. */
private val SPLIT_FROM = 600.dp

private val TOP = listOf(
    code("ESC", KeyEvent.KEYCODE_ESCAPE, "Escape"),
    code("TAB", KeyEvent.KEYCODE_TAB, "Tab"),
)
private val ARROWS = listOf(
    code("←", KeyEvent.KEYCODE_DPAD_LEFT, "Left", repeats = true),
    code("↓", KeyEvent.KEYCODE_DPAD_DOWN, "Down", repeats = true),
    code("↑", KeyEvent.KEYCODE_DPAD_UP, "Up", repeats = true),
    code("→", KeyEvent.KEYCODE_DPAD_RIGHT, "Right", repeats = true),
)
/** With the phone's keyboard: the keys it lacks, and the symbols it hides a layer away. */
private val SYSTEM_EXTRAS = NAV + chars("/ - | ~ _ \\")

private enum class ShiftState { OFF, ONCE, LOCK }

/** Where the letter keys are, for decoding a swipe, and the trail being drawn. */
private class SwipeBoard {
    val centers = HashMap<Char, Offset>()
    var keyWidth = 0f
    var trail by mutableStateOf<List<Offset>>(emptyList())
}

private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '-'

/**
 * The terminal's keyboard: a top row (Esc, Tab, Ctrl, Alt, arrows) that's always there, and below it
 * Bori's own compact keys with a suggestion strip and swipe typing. [showKeys] opens them. With
 * [systemKeyboard] the phone's keyboard is used instead: a typing box (where the phone's own
 * prediction and swipe work; Enter sends the line) and a short row of what that keyboard lacks.
 * Ctrl and Alt are one-shot: they apply to the next key. Shift does the same, and a double tap locks it.
 */
@Composable
fun TerminalKeyboard(
    showKeys: Boolean,
    systemKeyboard: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    colors: BoriColors,
    predictor: WordPredictor,
    screenWords: () -> Set<String>,
    isEchoed: (String) -> Boolean,
    focusLineBox: Int,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKeyCode: (code: Int, shift: Boolean) -> Unit,
    onCodePoint: (Int) -> Unit,
    onSendLine: (String) -> Unit,
    onShowKeys: (Boolean) -> Unit,
    onSystemKeyboard: (Boolean) -> Unit,
) {
    var shift by remember { mutableStateOf(ShiftState.OFF) }
    var lastShiftTap by remember { mutableLongStateOf(0L) }
    var symbols by remember { mutableStateOf(false) }
    // The word being typed (as sent), so suggestions can complete it and Backspace can shorten it.
    var word by remember { mutableStateOf("") }
    // Set while [word] came from a swipe: Backspace removes it whole, a suggestion replaces it.
    var swiped by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var lastSent by remember { mutableStateOf<Char?>(null) }
    val board = remember { SwipeBoard() }
    val scope = rememberCoroutineScope()
    val modifiers = rememberUpdatedState(ctrl || alt)
    // Read once per word: scanning the screen on every key would slow typing.
    var screenCache by remember { mutableStateOf<Set<String>?>(null) }
    fun screen(): Set<String> = screenCache ?: screenWords().also { screenCache = it }

    fun send(text: String) {
        text.codePoints().forEach(onCodePoint)
        text.lastOrNull()?.let { lastSent = it }
    }

    fun backspace(n: Int) = repeat(n) { onKeyCode(KeyEvent.KEYCODE_DEL, false) }

    fun refresh() {
        suggestions = if (word.isEmpty()) emptyList() else predictor.suggest(word, screen())
    }

    fun endWord(learn: Boolean) {
        val done = word
        // Checked a moment later: the shell's echo of the last letters may still be on its way.
        if (learn && done.isNotEmpty()) scope.launch {
            delay(ECHO_WAIT_MS)
            predictor.learn(done, isEchoed(done))
        }
        word = ""
        screenCache = null
        swiped = false
        suggestions = emptyList()
    }

    fun press(act: Act) {
        when (act) {
            is Act.Type -> {
                val upper = shift != ShiftState.OFF && act.text.length == 1 && act.text[0].isLetter()
                val text = if (upper) act.text.uppercase() else act.text
                val c = text.singleOrNull()
                when {
                    // Ctrl+C and friends aren't words.
                    modifiers.value -> endWord(learn = false)
                    c != null && isWordChar(c) -> {
                        if (swiped) endWord(learn = true)
                        word += c
                    }
                    else -> endWord(learn = true)
                }
                send(text)
                if (shift == ShiftState.ONCE) shift = ShiftState.OFF
                refresh()
            }
            is Act.Code -> {
                when (act.code) {
                    KeyEvent.KEYCODE_DEL -> if (swiped && word.isNotEmpty()) {
                        backspace(word.length)
                        endWord(learn = false)
                        return
                    } else {
                        word = word.dropLast(1)
                    }
                    KeyEvent.KEYCODE_ENTER -> endWord(learn = true)
                    else -> endWord(learn = false)
                }
                onKeyCode(act.code, shift != ShiftState.OFF)
                // Backspace and the arrows leave the character before the cursor unknown.
                lastSent = if (act.code == KeyEvent.KEYCODE_ENTER) '\n' else null
                if (shift == ShiftState.ONCE) shift = ShiftState.OFF
                refresh()
            }
            Act.Shift -> {
                val now = System.currentTimeMillis()
                shift = when {
                    shift == ShiftState.ONCE && now - lastShiftTap < CAPS_LOCK_TAP_MS -> ShiftState.LOCK
                    shift == ShiftState.OFF -> ShiftState.ONCE
                    else -> ShiftState.OFF
                }
                lastShiftTap = now
            }
            Act.Layer -> symbols = !symbols
            Act.Phone -> onSystemKeyboard(true)
        }
    }

    fun pick(choice: String) {
        if (swiped) {
            // Another reading of the swipe: swap it in, keep the rest on offer.
            backspace(word.length)
            send(choice)
            suggestions = (suggestions - choice) + word
            word = choice
            return
        }
        if (choice.startsWith(word)) send(choice.substring(word.length)) else { backspace(word.length); send(choice) }
        word = choice
        endWord(learn = true)
        send(" ")
    }

    fun swipe(path: List<Offset>) {
        // Decoded here, on the touch, so words can't arrive out of order (it takes a few ms).
        val found = predictor.decodeSwipe(path, board.centers, board.keyWidth)
        if (found.isEmpty()) return
        val shown = found.map {
            when (shift) {
                ShiftState.LOCK -> it.uppercase()
                ShiftState.ONCE -> it.replaceFirstChar { c -> c.uppercase() }
                ShiftState.OFF -> it
            }
        }
        if (word.isNotEmpty()) endWord(learn = true)
        // Swiped words follow a word with a space, like a phone keyboard.
        if (lastSent?.let(::isWordChar) == true) send(" ")
        send(shown.first())
        word = shown.first()
        swiped = true
        suggestions = shown.drop(1).take(3)
        if (shift == ShiftState.ONCE) shift = ShiftState.OFF
    }

    Column(modifier = Modifier.fillMaxWidth().background(colors.terminalKeys)) {
        if (showKeys && !systemKeyboard) {
            SuggestionStrip(suggestions, colors, onPick = ::pick)
        }
        Row(modifier = Modifier.fillMaxWidth().height(40.dp)) {
            val top: @Composable RowScope.(K) -> Unit = { k ->
                Key(k.label, null, k.description, 1f, false, k.repeats, colors, small = true, mono = true) { press(k.act) }
            }
            TOP.forEach { top(it) }
            Key("CTRL", null, "Control", 1f, ctrl, false, colors, small = true, mono = true, onPress = onToggleCtrl)
            Key("ALT", null, "Alt", 1f, alt, false, colors, small = true, mono = true, onPress = onToggleAlt)
            ARROWS.forEach { top(it) }
            when {
                systemKeyboard -> Key(null, Icons.Default.Terminal, "Use Bori's terminal keys", 1f, false, false, colors) { onSystemKeyboard(false) }
                showKeys -> Key(null, Icons.Default.KeyboardHide, "Hide keys", 1f, false, false, colors) { onShowKeys(false) }
                else -> Key(null, Icons.Default.Keyboard, "Show keys", 1f, false, false, colors) { onShowKeys(true) }
            }
        }
        if (systemKeyboard) {
            Row(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                SYSTEM_EXTRAS.forEach { k ->
                    Key(k.label, null, k.description, 1f, false, false, colors, small = true, mono = true) { press(k.act) }
                }
            }
            LineBox(colors, focusLineBox, onSendLine, onKeyCode)
        } else if (showKeys) {
            var origin by remember { mutableStateOf(Offset.Zero) }
            Column(
                modifier = Modifier
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .drawWithContent {
                        drawContent()
                        val trail = board.trail
                        if (trail.size > 1) {
                            val path = Path().apply {
                                moveTo(trail[0].x - origin.x, trail[0].y - origin.y)
                                trail.drop(1).forEach { lineTo(it.x - origin.x, it.y - origin.y) }
                            }
                            drawPath(
                                path,
                                colors.terminalAccent.copy(alpha = 0.6f),
                                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    },
            ) {
                val letters = !symbols
                val keyRow: @Composable RowScope.(K) -> Unit = { k ->
                    val upper = shift != ShiftState.OFF
                    val label = if (upper && k.act is Act.Type) k.label.uppercase() else k.label
                    Key(
                        label = label,
                        icon = if (k.act == Act.Phone) Icons.Default.Language else null,
                        description = k.description,
                        weight = k.weight,
                        active = k.act == Act.Shift && upper,
                        repeats = k.repeats,
                        colors = colors,
                        small = k.small,
                        bold = k.act == Act.Shift && shift == ShiftState.LOCK,
                        filled = true,
                        swipeLetter = if (letters) k.letter else null,
                        board = board,
                        onSwipe = ::swipe,
                    ) { press(k.act) }
                }
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth >= SPLIT_FROM) {
                        // Each half about as wide as the cover screen's keyboard, at its edge.
                        val half = minOf(maxWidth * 0.4f, 380.dp)
                        Column {
                            (if (symbols) SYMBOLS_SPLIT else LETTERS_SPLIT).forEach { row ->
                                Row(modifier = Modifier.fillMaxWidth().height(42.dp)) {
                                    Row(modifier = Modifier.width(half).fillMaxHeight()) { row.left.forEach { keyRow(it) } }
                                    Spacer(Modifier.weight(1f))
                                    Row(modifier = Modifier.width(half).fillMaxHeight()) { row.right.forEach { keyRow(it) } }
                                }
                            }
                        }
                    } else {
                        Column {
                            (if (symbols) SYMBOLS else LETTERS).forEach { row ->
                                Row(modifier = Modifier.fillMaxWidth().height(42.dp)) { row.forEach { keyRow(it) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Up to three suggestions; tapping one finishes the word. Kept at a fixed height so the keys don't jump. */
@Composable
private fun SuggestionStrip(suggestions: List<String>, colors: BoriColors, onPick: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val s = suggestions.getOrNull(i)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (s != null) Modifier.clickable { onPick(s) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (s != null) {
                    Text(
                        s,
                        color = colors.terminalText,
                        fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
            if (i < 2) Box(Modifier.width(1.dp).height(20.dp).background(colors.terminalText.copy(alpha = 0.2f)))
        }
    }
}

/**
 * A line of text typed on the phone's keyboard, with its own prediction and swipe (the terminal
 * itself turns them off). Enter sends it; Enter or Backspace in an empty box go to the terminal.
 */
@Composable
private fun LineBox(
    colors: BoriColors,
    focusRequest: Int,
    onSendLine: (String) -> Unit,
    onKeyCode: (code: Int, shift: Boolean) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    val send = {
        onSendLine(text)
        text = ""
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = colors.terminalText, fontSize = 15.sp),
            cursorBrush = SolidColor(colors.terminalAccent),
            keyboardOptions = KeyboardOptions(
                // Commands: no capital first letter, no "corrected" flags. Suggestions and swipe still work.
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || text.isNotEmpty()) return@onPreviewKeyEvent false
                    when (e.key) {
                        KeyName.Backspace -> { onKeyCode(KeyEvent.KEYCODE_DEL, false); true }
                        KeyName.Enter -> { send(); true }
                        else -> false
                    }
                }
                .clip(RoundedCornerShape(8.dp))
                .background(colors.terminalText.copy(alpha = 0.09f))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text("Type here; Enter sends it to the terminal", color = colors.terminalText.copy(alpha = 0.45f), fontSize = 15.sp, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        IconButton(onClick = { send() }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to terminal", tint = colors.terminalAccent)
        }
    }
}

/**
 * One key, with a haptic tick on touch. Most keys act on touch down and [repeats] keys repeat while
 * held. A letter key ([swipeLetter]) acts on release instead, so a swipe can start on it: moving
 * half a key away draws a trail and hands the path to [onSwipe].
 */
@Composable
private fun RowScope.Key(
    label: String?,
    icon: ImageVector?,
    description: String,
    weight: Float,
    active: Boolean,
    repeats: Boolean,
    colors: BoriColors,
    small: Boolean = false,
    mono: Boolean = false,
    bold: Boolean = active,
    filled: Boolean = false,
    swipeLetter: Char? = null,
    board: SwipeBoard? = null,
    onSwipe: (List<Offset>) -> Unit = {},
    onPress: () -> Unit,
) {
    val view = LocalView.current
    val press by rememberUpdatedState(onPress)
    val swipeTo by rememberUpdatedState(onSwipe)
    var pressed by remember { mutableStateOf(false) }
    val origin = remember { arrayOf(Offset.Zero) }
    val swipes = swipeLetter != null && board != null
    val fill = when {
        pressed -> colors.terminalAccent.copy(alpha = 0.35f)
        filled -> colors.terminalText.copy(alpha = 0.09f)
        else -> colors.terminalKeys
    }
    val tint = if (active) colors.terminalAccent else colors.terminalText
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .onGloballyPositioned { c ->
                origin[0] = c.positionInRoot()
                if (swipeLetter != null && board != null) {
                    board.centers[swipeLetter] = origin[0] + Offset(c.size.width / 2f, c.size.height / 2f)
                    board.keyWidth = c.size.width.toFloat()
                }
            }
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .pointerInput(repeats, swipes) {
                // The repeat lives in this scope, so it ends with the gesture even if the key changes.
                coroutineScope { awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (!swipes) press()
                    val repeat = if (repeats) {
                        launch {
                            delay(REPEAT_DELAY_MS)
                            while (isActive) {
                                press()
                                delay(REPEAT_EVERY_MS)
                            }
                        }
                    } else null
                    // The padding above shifts this key's coordinates by 2dp; close enough for a swipe.
                    val start = origin[0]
                    val path = arrayListOf(start + down.position)
                    var swiping = false
                    var released = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        if (swipes && board != null) {
                            if (!swiping && (change.position - down.position).getDistance() > board.keyWidth * 0.5f) {
                                swiping = true
                                pressed = false
                            }
                            if (swiping) {
                                path += start + change.position
                                board.trail = path.toList()
                                change.consume()
                            }
                        }
                    }
                    repeat?.cancel()
                    pressed = false
                    if (swiping) {
                        board?.trail = emptyList()
                        swipeTo(path)
                    } else if (swipes && released) {
                        press()
                    }
                } }
            }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { press(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        } else if (label != null) {
            Text(
                label,
                color = tint,
                fontFamily = if (mono) FontFamily.Monospace else null,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (small) 12.sp else 17.sp,
                maxLines = 1,
            )
        }
    }
}
