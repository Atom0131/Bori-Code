package com.bori.app.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bori.app.data.prefs.TerminalKeysSettings
import com.bori.app.terminal.TerminalTyping
import com.bori.app.ui.theme.BoriColors
import com.bori.core.keyboard.layout.Key
import com.bori.core.keyboard.layout.KeyCode
import com.bori.core.keyboard.layout.KeyGeometry
import com.bori.core.keyboard.layout.KeyKind
import com.bori.core.keyboard.layout.KeyboardLayout
import com.bori.core.keyboard.layout.LayoutId
import com.bori.core.keyboard.layout.PlacedKey
import com.bori.core.keyboard.predict.KeyProximity
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_EVERY_MS = 50L
private const val CAPS_LOCK_TAP_MS = 350L

/** From this width the keys split in two, as on an unfolded foldable (Bori Keyboard's BigScreen.MIN_DP). */
private const val SPLIT_FROM_DP = 600

private fun text(c: String, kind: KeyKind = KeyKind.LETTER, width: Float = 1f) = Key(c, KeyCode.Text(c), width, kind)
private fun row(keys: String, kind: KeyKind = KeyKind.LETTER) = keys.split(' ').map { text(it, kind) }

private val NUMBERS = row("1 2 3 4 5 6 7 8 9 0", KeyKind.FUNCTION)

/** A normal phone keyboard: Shift and Backspace beside z…m, and ?123 ⌨ , space . Enter at the bottom (⌨ is the phone's keyboard). */
private fun letters(numberRow: Boolean) = KeyboardLayout(
    LayoutId.ALPHA,
    listOfNotNull(
        NUMBERS.takeIf { numberRow },
        row("q w e r t y u i o p"),
        row("a s d f g h j k l"),
        listOf(Key("⇧", KeyCode.Shift, 1.5f, KeyKind.FUNCTION)) + row("z x c v b n m") +
            Key("⌫", KeyCode.Delete, 1.5f, KeyKind.FUNCTION),
        listOf(
            Key("?123", KeyCode.Switch(LayoutId.SYMBOLS), 1.5f, KeyKind.FUNCTION),
            Key("⌨", KeyCode.Globe, 1f, KeyKind.FUNCTION),
            text(",", KeyKind.FUNCTION),
            Key("", KeyCode.Space, 4f, KeyKind.SPACE),
            text(".", KeyKind.FUNCTION),
            Key("↵", KeyCode.Enter, 1.5f, KeyKind.ENTER),
        ),
    ),
)

/** Digits always on top, then every symbol a shell uses. */
private val SYMBOLS = KeyboardLayout(
    LayoutId.SYMBOLS,
    listOf(
        NUMBERS,
        row("! @ # $ % ^ & * ( )", KeyKind.FUNCTION),
        row("` ~ - _ = + [ ] { }", KeyKind.FUNCTION),
        row("\\ | ; : ' \" < >", KeyKind.FUNCTION) + Key("⌫", KeyCode.Delete, 2f, KeyKind.FUNCTION),
        listOf(
            Key("abc", KeyCode.Switch(LayoutId.ALPHA), 1.5f, KeyKind.FUNCTION),
            Key("⌨", KeyCode.Globe, 1f, KeyKind.FUNCTION),
            text("/", KeyKind.FUNCTION),
            Key("", KeyCode.Space, 4f, KeyKind.SPACE),
            text("?", KeyKind.FUNCTION),
            Key("↵", KeyCode.Enter, 1.5f, KeyKind.ENTER),
        ),
    ),
)

/** A key on the top bar: a terminal key code, or text. */
private class BarKey(val label: String, val description: String, val code: Int? = null, val text: String? = null, val repeats: Boolean = false)

private fun barCode(label: String, code: Int, description: String, repeats: Boolean = false) = BarKey(label, description, code = code, repeats = repeats)

/** Every key a phone keyboard lacks, in one row: the terminal keys, then the symbols shells use most. */
private val BAR = listOf(
    barCode("ESC", KeyEvent.KEYCODE_ESCAPE, "Escape"),
    barCode("TAB", KeyEvent.KEYCODE_TAB, "Tab"),
) to listOf(
    barCode("←", KeyEvent.KEYCODE_DPAD_LEFT, "Left", repeats = true),
    barCode("↓", KeyEvent.KEYCODE_DPAD_DOWN, "Down", repeats = true),
    barCode("↑", KeyEvent.KEYCODE_DPAD_UP, "Up", repeats = true),
    barCode("→", KeyEvent.KEYCODE_DPAD_RIGHT, "Right", repeats = true),
    barCode("Home", KeyEvent.KEYCODE_MOVE_HOME, "Home"),
    barCode("End", KeyEvent.KEYCODE_MOVE_END, "End"),
    barCode("PgUp", KeyEvent.KEYCODE_PAGE_UP, "Page up", repeats = true),
    barCode("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, "Page down", repeats = true),
) + "/ - | ~ _ :".split(' ').map { BarKey(it, it, text = it) }

private enum class ShiftState { OFF, ONCE, LOCK }

/**
 * The terminal's keyboard. The top bar is always there: Esc, Tab, Ctrl, Alt, the arrows, Home/End,
 * PgUp/PgDn, the symbols shells use and settings, then (always in reach) the auto-enter bot and Enter. Below it, when [showKeys], a
 * normal phone keyboard with a suggestion strip, spell correction and glide typing ([typing], Bori
 * Keyboard's engine), drawn from one [KeyGeometry] so taps, swipes and correction agree on where
 * every key is. Its size, split and vibration come from [settings]. With [systemKeyboard] the
 * phone's keyboard is used instead, typing into a box whose Enter sends the line.
 * Ctrl and Alt are one-shot: they apply to the next key. Shift does the same, and a double tap locks it.
 */
@Composable
fun TerminalKeyboard(
    showKeys: Boolean,
    systemKeyboard: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    colors: BoriColors,
    settings: TerminalKeysSettings,
    onSettings: (TerminalKeysSettings) -> Unit,
    typing: TerminalTyping,
    /** [typing]'s current suggestions, and which one space will commit (-1: none). */
    suggestions: List<String>,
    boldIndex: Int,
    /** Auto-enter for the focused tab: on/off, or null when it can't run there. */
    autoEnter: Boolean?,
    onToggleAutoEnter: () -> Unit,
    autoEnterTotals: () -> String,
    focusLineBox: Int,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKeyCode: (code: Int, shift: Boolean) -> Unit,
    onSendLine: (String) -> Unit,
    onShowKeys: (Boolean) -> Unit,
    onSystemKeyboard: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val haptics = remember { KeyHaptics(context) }
    // While a slider is being dragged, its value shows at once; it's saved when the drag ends.
    var live by remember { mutableStateOf<TerminalKeysSettings?>(null) }
    val s = live ?: settings
    val buzz = rememberUpdatedState { haptics.tap(s.vibration) }
    var settingsOpen by remember { mutableStateOf(false) }
    val modifiers = rememberUpdatedState(ctrl || alt)

    // A terminal key from the bar: the word being typed ends as it is.
    val sendCode: (Int) -> Unit = { code ->
        typing.breakWord(enter = code == KeyEvent.KEYCODE_ENTER)
        onKeyCode(code, false)
    }

    Column(modifier = Modifier.fillMaxWidth().background(colors.terminalKeys)) {
        if (showKeys && !systemKeyboard) {
            SuggestionStrip(suggestions, boldIndex, colors) { i -> buzz.value(); typing.pick(i) }
        }
        TopBar(
            ctrl = ctrl,
            alt = alt,
            colors = colors,
            showKeys = showKeys,
            systemKeyboard = systemKeyboard,
            autoEnter = autoEnter,
            buzz = { buzz.value() },
            onCode = sendCode,
            onText = { t -> typing.raw(t) },
            onToggleCtrl = onToggleCtrl,
            onToggleAlt = onToggleAlt,
            onToggleAutoEnter = onToggleAutoEnter,
            onSettings = { settingsOpen = true },
            onShowKeys = onShowKeys,
            onSystemKeyboard = onSystemKeyboard,
        )
        if (systemKeyboard) {
            LineBox(colors, focusLineBox, onSendLine, onKeyCode)
        } else if (showKeys) {
            KeyPad(s, colors, typing, modifiers, buzz = { buzz.value() }, onKeyCode = onKeyCode, onPhone = { onSystemKeyboard(true) })
        }
    }

    if (settingsOpen) {
        KeysSettingsSheet(
            settings = s,
            onChange = { live = it },
            onDone = { done -> live = null; onSettings(done) },
            onPreviewVibration = { haptics.tap(it) },
            autoEnterTotals = autoEnterTotals,
            onDismiss = {
                live?.let(onSettings)
                live = null
                settingsOpen = false
            },
        )
    }
}

@Composable
private fun TopBar(
    ctrl: Boolean,
    alt: Boolean,
    colors: BoriColors,
    showKeys: Boolean,
    systemKeyboard: Boolean,
    autoEnter: Boolean?,
    buzz: () -> Unit,
    onCode: (Int) -> Unit,
    onText: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleAutoEnter: () -> Unit,
    onSettings: () -> Unit,
    onShowKeys: (Boolean) -> Unit,
    onSystemKeyboard: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        val bar: @Composable RowScope.(BarKey) -> Unit = { k ->
            BarButton(k.label, null, k.description, false, k.repeats, colors, buzz) {
                if (k.code != null) onCode(k.code) else k.text?.let(onText)
            }
        }
        // Scrolls on a narrow screen; Enter and the keyboard toggle stay put on the right.
        Row(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
            BAR.first.forEach { bar(it) }
            BarButton("CTRL", null, "Control", ctrl, false, colors, buzz, onPress = onToggleCtrl)
            BarButton("ALT", null, "Alt", alt, false, colors, buzz, onPress = onToggleAlt)
            BAR.second.forEach { bar(it) }
            BarButton(null, Icons.Default.Settings, "Keyboard settings", false, false, colors, buzz, onPress = onSettings)
        }
        // The auto-enter bot, always in reach (full screen too); lit while it's on.
        if (autoEnter != null) {
            BarButton(
                null, Icons.Default.SmartToy, if (autoEnter) "Turn auto-enter off" else "Turn auto-enter on",
                autoEnter, false, colors, buzz, onPress = onToggleAutoEnter,
            )
        }
        BarButton(null, Icons.AutoMirrored.Filled.KeyboardReturn, "Enter", false, false, colors, buzz, wide = true) {
            onCode(KeyEvent.KEYCODE_ENTER)
        }
        when {
            systemKeyboard -> BarButton(null, Icons.Default.Terminal, "Use Bori's terminal keys", false, false, colors, buzz) { onSystemKeyboard(false) }
            showKeys -> BarButton(null, Icons.Default.KeyboardHide, "Hide keys", false, false, colors, buzz) { onShowKeys(false) }
            else -> BarButton(null, Icons.Default.Keyboard, "Show keys", false, false, colors, buzz) { onShowKeys(true) }
        }
    }
}

/** One finger on the keys: where it went down, and the swipe if it's become one. */
private class Track(val id: Long, val key: PlacedKey?, val start: Offset) {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    var swiping = false
    var repeat: Job? = null
    var acted = false
}

/**
 * The letter or symbol keys, drawn from a [KeyGeometry] and read from raw touches: a tap types the
 * key under the finger (no dead zones between keys), a swipe from a letter glides a word, and a
 * second finger going down types the first finger's key at once, so two thumbs can roll.
 */
@Composable
private fun KeyPad(
    s: TerminalKeysSettings,
    colors: BoriColors,
    typing: TerminalTyping,
    modifiers: androidx.compose.runtime.State<Boolean>,
    buzz: () -> Unit,
    onKeyCode: (code: Int, shift: Boolean) -> Unit,
    onPhone: () -> Unit,
) {
    var shift by remember { mutableStateOf(ShiftState.OFF) }
    var lastShiftTap by remember { mutableLongStateOf(0L) }
    var symbols by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf<PlacedKey?>(null) }
    var trail by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    val configuration = LocalConfiguration.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Sizes as in Bori Keyboard (KeyboardView): split only on a big screen and a wide keyboard;
        // the halves sit off the edges, where thumbs can't reach well.
        val bigScreen = configuration.smallestScreenWidthDp >= SPLIT_FROM_DP && maxWidth.value >= SPLIT_FROM_DP
        val split = s.split ?: bigScreen
        val alpha = remember(s.numberRow) { letters(s.numberRow) }
        val layout = if (symbols) SYMBOLS else alpha
        // 54 dp rows, but never more than a tenth of the screen's height each.
        val rowDp = minOf(54f * s.height, (configuration.screenHeightDp * 0.1f).coerceAtLeast(36f))
        val heightDp = rowDp * alpha.rows.size + 8f
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { heightDp.dp.roundToPx() }
        val geometry = remember(layout, widthPx, heightPx, split, s.gap, s.edge) {
            with(density) {
                KeyGeometry(
                    layout, widthPx, heightPx,
                    // The edge keys' touch areas still run to the edge.
                    padH = 2.dp.toPx() + if (split) widthPx * s.edge else 0f,
                    padV = 4.dp.toPx(),
                    gapH = 5.dp.toPx(),
                    gapV = 8.dp.toPx(),
                    splitGap = if (split) widthPx * s.gap else 0f,
                )
            }
        }
        LaunchedEffect(geometry) { if (layout.id == LayoutId.ALPHA) typing.proximity = KeyProximity(geometry) }

        val act: (PlacedKey, Offset) -> Unit = act@{ pk, at ->
            val upper = shift != ShiftState.OFF
            when (val code = pk.key.code) {
                is KeyCode.Text -> {
                    val t = if (upper && pk.key.isLetter) code.text.uppercase() else code.text
                    when {
                        // Ctrl+C and friends aren't words.
                        modifiers.value -> typing.raw(t)
                        pk.key.isLetter || t == "'" -> typing.letter(t, at.x, at.y)
                        else -> typing.separator(t)
                    }
                    if (shift == ShiftState.ONCE && pk.key.isLetter) shift = ShiftState.OFF
                }
                KeyCode.Space -> if (modifiers.value) typing.raw(" ") else typing.separator(" ")
                KeyCode.Delete -> typing.delete()
                KeyCode.Enter -> {
                    typing.breakWord(enter = true)
                    onKeyCode(KeyEvent.KEYCODE_ENTER, false)
                }
                KeyCode.Shift -> {
                    val now = System.currentTimeMillis()
                    shift = when {
                        shift == ShiftState.ONCE && now - lastShiftTap < CAPS_LOCK_TAP_MS -> ShiftState.LOCK
                        shift == ShiftState.OFF -> ShiftState.ONCE
                        else -> ShiftState.OFF
                    }
                    lastShiftTap = now
                }
                is KeyCode.Switch -> symbols = code.target == LayoutId.SYMBOLS
                KeyCode.Globe -> onPhone()
            }
        }
        val actNow by rememberUpdatedState(act)
        val keyWidth = remember(geometry) { KeyProximity(geometry).keyWidth }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .pointerInput(geometry) {
                    coroutineScope {
                        fun begin(change: PointerInputChange): Track {
                            val pk = geometry.keyAt(change.position.x, change.position.y)
                            val t = Track(change.id.value, pk, change.position)
                            t.xs += change.position.x
                            t.ys += change.position.y
                            pressed = pk
                            buzz()
                            if (pk?.key?.code == KeyCode.Delete) {
                                // Backspace acts on touch and repeats while held.
                                actNow(pk, change.position)
                                t.acted = true
                                t.repeat = launch {
                                    delay(REPEAT_DELAY_MS)
                                    while (isActive) {
                                        actNow(pk, change.position)
                                        delay(REPEAT_EVERY_MS)
                                    }
                                }
                            }
                            return t
                        }
                        fun end(t: Track) {
                            t.repeat?.cancel()
                            pressed = null
                            if (t.swiping) {
                                trail = emptyList()
                                val caps = when (shift) {
                                    ShiftState.LOCK -> TerminalTyping.Caps.ALL
                                    ShiftState.ONCE -> TerminalTyping.Caps.FIRST
                                    ShiftState.OFF -> TerminalTyping.Caps.NONE
                                }
                                typing.glide(t.xs.toFloatArray(), t.ys.toFloatArray(), t.xs.size, caps)
                                if (shift == ShiftState.ONCE) shift = ShiftState.OFF
                            } else if (!t.acted) {
                                t.key?.let { actNow(it, t.start) }
                            }
                        }
                        awaitEachGesture {
                            var t: Track? = begin(awaitFirstDown(requireUnconsumed = false))
                            while (t != null) {
                                val event = awaitPointerEvent()
                                val cur: Track = t
                                // A second finger: the first one's key is typed now.
                                val next = event.changes.firstOrNull { it.pressed && !it.previousPressed && it.id.value != cur.id }
                                if (next != null) {
                                    end(cur)
                                    t = begin(next)
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id.value == cur.id } ?: break
                                if (!change.pressed) {
                                    end(cur)
                                    t = null
                                    break
                                }
                                val canSwipe = !symbols && cur.key?.key?.isLetter == true && !modifiers.value
                                if (canSwipe && !cur.swiping && (change.position - cur.start).getDistance() > keyWidth * 0.5f) {
                                    cur.swiping = true
                                    pressed = null
                                }
                                if (cur.swiping) {
                                    cur.xs += change.position.x
                                    cur.ys += change.position.y
                                    trail = cur.xs.indices.map { Offset(cur.xs[it], cur.ys[it]) }
                                    change.consume()
                                }
                            }
                        }
                    }
                }
                .clearAndSetSemantics { contentDescription = if (symbols) "Symbol keys" else "Letter keys" },
        ) {
            val radius = CornerRadius(6.dp.toPx())
            val upper = shift != ShiftState.OFF
            for (pk in geometry.keys) {
                val r = pk.visual
                val fill = when {
                    pk === pressed -> colors.terminalAccent.copy(alpha = 0.35f)
                    pk.key.code == KeyCode.Shift && upper -> colors.terminalAccent.copy(alpha = 0.25f)
                    pk.key.kind == KeyKind.LETTER || pk.key.kind == KeyKind.SPACE -> colors.terminalText.copy(alpha = 0.10f)
                    else -> colors.terminalText.copy(alpha = 0.17f)
                }
                drawRoundRect(fill, Offset(r.left, r.top), Size(r.width(), r.height()), radius)
                val label = when {
                    pk.key.code == KeyCode.Shift && shift == ShiftState.LOCK -> "⇪"
                    pk.key.code == KeyCode.Space -> s.language.uppercase()
                    upper && pk.key.isLetter -> pk.key.label.uppercase()
                    else -> pk.key.label
                }
                if (label.isEmpty()) continue
                val big = label.length == 1 && pk.key.code !is KeyCode.Switch && pk.key.code != KeyCode.Globe
                val style = TextStyle(
                    color = if (pk.key.code == KeyCode.Space) colors.terminalText.copy(alpha = 0.45f) else colors.terminalText,
                    fontSize = if (big) 21.sp else 14.sp,
                    fontWeight = if (pk.key.code == KeyCode.Shift && upper) FontWeight.Bold else FontWeight.Normal,
                )
                val m = measurer.measure(label, style)
                drawText(m, topLeft = Offset(r.centerX() - m.size.width / 2f, r.centerY() - m.size.height / 2f))
            }
            if (trail.size > 1) {
                val path = Path().apply {
                    moveTo(trail[0].x, trail[0].y)
                    for (i in 1 until trail.size) lineTo(trail[i].x, trail[i].y)
                }
                drawPath(path, colors.terminalAccent.copy(alpha = 0.6f), style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Up to three suggestions; tapping one finishes the word. Kept at a fixed height so the keys don't jump. */
@Composable
private fun SuggestionStrip(suggestions: List<String>, bold: Int, colors: BoriColors, onPick: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val s = suggestions.getOrNull(i)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (s != null) Modifier.clickable { onPick(i) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (s != null) {
                    Text(
                        s,
                        color = colors.terminalText,
                        fontWeight = if (i == bold) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 16.sp,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeysSettingsSheet(
    settings: TerminalKeysSettings,
    onChange: (TerminalKeysSettings) -> Unit,
    onDone: (TerminalKeysSettings) -> Unit,
    onPreviewVibration: (Int) -> Unit,
    autoEnterTotals: () -> String,
    onDismiss: () -> Unit,
) {
    // Edits apply to the keys at once (behind the sheet) and are saved when a slider is let go.
    var s by remember { mutableStateOf(settings) }
    val update: (TerminalKeysSettings) -> Unit = { s = it; onChange(it) }
    val save: () -> Unit = { onDone(s) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Terminal keyboard", style = MaterialTheme.typography.titleMedium)

            SettingLabel("Key height", "${(s.height * 100).toInt()}%")
            Slider(s.height, { update(s.copy(height = it)) }, valueRange = 0.8f..1.3f, onValueChangeFinished = save)

            SettingLabel("Split keys", null)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(null to "Auto", true to "Split", false to "Full width").forEachIndexed { i, (v, label) ->
                    SegmentedButton(
                        selected = s.split == v,
                        onClick = { update(s.copy(split = v)); onDone(s.copy(split = v)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Auto splits on the unfolded screen and is full width on the cover screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingLabel("Space between the halves", "${(s.gap * 100).toInt()}% of the width")
            Slider(s.gap, { update(s.copy(gap = it)) }, valueRange = 0.1f..0.5f, onValueChangeFinished = save)
            SettingLabel("Space at the edges", "${(s.edge * 100).toInt()}% of the width")
            Slider(s.edge, { update(s.copy(edge = it)) }, valueRange = 0f..0.2f, onValueChangeFinished = save)

            SettingLabel("Vibration", if (s.vibration == 0) "Off" else "${s.vibration}")
            Slider(
                s.vibration.toFloat(),
                { update(s.copy(vibration = it.toInt())) },
                valueRange = 0f..100f,
                onValueChangeFinished = { onPreviewVibration(s.vibration); save() },
            )

            SettingLabel("Autocorrect at an agent's prompt", null)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("OFF" to "Off", "MILD" to "Mild", "NORMAL" to "Normal", "AGGRESSIVE" to "Strong").forEachIndexed { i, (v, label) ->
                    SegmentedButton(
                        selected = s.autocorrect == v,
                        onClick = { update(s.copy(autocorrect = v)); onDone(s.copy(autocorrect = v)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 4),
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
            Text(
                "At a shell prompt nothing is corrected: commands and file names are typed as you type them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Number row", modifier = Modifier.weight(1f))
                Switch(s.numberRow, { update(s.copy(numberRow = it)); onDone(s.copy(numberRow = it)) })
            }

            SettingLabel("Language", null)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("en" to "English", "es" to "Español").forEachIndexed { i, (v, label) ->
                    SegmentedButton(
                        selected = s.language == v,
                        onClick = { update(s.copy(language = v)); onDone(s.copy(language = v)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = { Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(label) },
                    )
                }
            }

            Text(
                "Auto-enter: ${autoEnterTotals()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun SettingLabel(title: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                .padding(vertical = 2.dp)
                .background(colors.terminalText.copy(alpha = 0.09f), RoundedCornerShape(8.dp))
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
 * A top-bar key, with a short buzz on touch. It acts when the finger lifts, so scrolling the bar
 * types nothing; [repeats] keys also repeat while held.
 */
@Composable
private fun BarButton(
    label: String?,
    icon: ImageVector?,
    description: String,
    active: Boolean,
    repeats: Boolean,
    colors: BoriColors,
    buzz: () -> Unit,
    wide: Boolean = false,
    onPress: () -> Unit,
) {
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val fill = when {
        pressed -> colors.terminalAccent.copy(alpha = 0.35f)
        active -> colors.terminalAccent.copy(alpha = 0.2f)
        else -> colors.terminalKeys
    }
    val tint = if (active) colors.terminalAccent else colors.terminalText
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = if (wide) 64.dp else 46.dp)
            .padding(2.dp)
            .background(fill, RoundedCornerShape(6.dp))
            .pointerInput(repeats) {
                coroutineScope { awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    buzz()
                    var repeated = false
                    val repeat = if (repeats) launch {
                        delay(REPEAT_DELAY_MS)
                        while (isActive) {
                            repeated = true
                            press()
                            delay(REPEAT_EVERY_MS)
                        }
                    } else null
                    var tapped = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        // The bar scrolled: not a key press.
                        if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        if (!change.pressed) { tapped = true; break }
                    }
                    repeat?.cancel()
                    pressed = false
                    if (tapped && !repeated) press()
                } }
            }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { press(); true }
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        } else if (label != null) {
            Text(
                label,
                color = tint,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
    }
}
