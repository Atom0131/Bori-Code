package com.bori.app.ui.terminal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bori.app.terminal.PaneLayout
import com.bori.app.terminal.ShellKind
import com.bori.app.terminal.Split
import com.bori.app.terminal.TerminalTab
import com.bori.app.ui.adaptive.LocalAppChrome
import com.bori.app.ui.workspace.LocalPaneVisible
import com.bori.app.ui.common.BoriTopBar
import com.bori.app.ui.theme.BoriColors
import com.bori.app.ui.theme.BoriTheme
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private const val MIN_FONT_SP = 8f
private const val MAX_FONT_SP = 28f
private const val DEFAULT_FONT_SP = 13f

/**
 * The terminal: one or more panes, each a live session, with the keyboard in the focused one.
 * Sessions live in TerminalSessionManager; this screen only shows them. Full screen hides the
 * app's bars and Android's, leaving the panes and the keyboard. The keyboard is Bori's own
 * [TerminalKeyboard] unless the user switched to the phone's; being part of this screen, it sits
 * under the terminal only, so unfolded the chat beside it keeps its full height.
 */
@Composable
fun TerminalScreen(
    onBack: (() -> Unit)?,
    onOpenLinuxSetup: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val ctrl by viewModel.ctrl.collectAsStateWithLifecycle()
    val alt by viewModel.alt.collectAsStateWithLifecycle()
    val systemKeyboard by viewModel.systemKeyboard.collectAsStateWithLifecycle()
    val keysShown by viewModel.keysShown.collectAsStateWithLifecycle()
    val colors = BoriTheme.colors

    var fonts by rememberSaveable { mutableStateOf(List(Split.GRID.panes) { DEFAULT_FONT_SP }) }
    var newTabMenu by remember { mutableStateOf(false) }
    var splitMenu by remember { mutableStateOf(false) }
    // The view in each pane, so the keyboard reaches the focused one.
    val views = remember { mutableStateMapOf<Int, TerminalView>() }
    val focusedSlot = rememberUpdatedState(layout.focused)
    var canColumns by remember { mutableStateOf(false) }
    var canGrid by remember { mutableStateOf(false) }

    var fullScreen by LocalAppChrome.current.fullScreen
    FullScreenEffect(fullScreen)
    BackHandler(enabled = fullScreen) { fullScreen = false }
    // Leaving the Terminal always brings the bars back.
    val visible = LocalPaneVisible.current
    LaunchedEffect(visible) { if (!visible) fullScreen = false }
    DisposableEffect(Unit) { onDispose { fullScreen = false } }

    // A physical keyboard folds Bori's keys away (the top row stays, and can open them again).
    val configuration = LocalConfiguration.current
    val hardKeyboard = configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    LaunchedEffect(hardKeyboard) { if (hardKeyboard) viewModel.showKeys(false) }
    val hasHardKeyboard = rememberUpdatedState(hardKeyboard)
    // Tapping the terminal opens Bori's keys, like it would the phone's, unless a physical keyboard is there.
    val openKeys = { if (!hasHardKeyboard.value) viewModel.showKeys(true) }
    val showingKeys = visible && keysShown && !systemKeyboard
    BackHandler(enabled = showingKeys) { viewModel.showKeys(false) }
    // On a phone the bottom bar steps aside for the keys, as it does for the phone's keyboard.
    var terminalKeys by LocalAppChrome.current.terminalKeys
    LaunchedEffect(showingKeys) { terminalKeys = showingKeys }
    DisposableEffect(Unit) { onDispose { terminalKeys = false } }
    // Switching keyboards: the phone's comes up at once, and goes away for Bori's keys. Done here, on the
    // tap, so the saved choice loading or the terminal coming into view never pops or closes a keyboard.
    val hostView = LocalView.current
    // Bumped to put the cursor in the typing box (and bring up the phone's keyboard) on a switch.
    var focusLineBox by remember { mutableStateOf(0) }
    val switchKeyboard: (Boolean) -> Unit = { system ->
        val imm = hostView.context.getSystemService(InputMethodManager::class.java)
        viewModel.setSystemKeyboard(system)
        val v = views[focusedSlot.value]
        if (v != null) {
            v.setSoftKeyboardEnabled(system)
            v.requestFocus()
            // The view's editor status changed; let the IME see it.
            imm?.restartInput(v)
        }
        if (system) {
            focusLineBox++
        } else {
            imm?.hideSoftInputFromWindow(hostView.windowToken, 0)
            openKeys()
        }
    }

    Scaffold(
        containerColor = colors.terminalBackground,
        // The shell's bottom bar (or its padding when unfolded) already clears the gesture bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (!fullScreen) {
                BoriTopBar(
                    title = "Terminal",
                    onBack = onBack,
                    containerColor = colors.terminalBar,
                    contentColor = colors.terminalText,
                    actions = {
                        Box {
                            IconButton(onClick = { splitMenu = true }) {
                                Icon(Icons.Default.SpaceDashboard, contentDescription = "Split")
                            }
                            DropdownMenu(expanded = splitMenu, onDismissRequest = { splitMenu = false }) {
                                Split.entries.forEach { split ->
                                    val allowed = when (split) {
                                        Split.SINGLE, Split.ROWS -> true
                                        Split.COLUMNS -> canColumns
                                        Split.GRID -> canGrid
                                    }
                                    if (allowed) {
                                        DropdownMenuItem(
                                            text = { Text(split.label) },
                                            trailingIcon = { if (layout.split == split) Icon(Icons.Default.Check, contentDescription = "Current") },
                                            onClick = { splitMenu = false; viewModel.setSplit(split) },
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { fullScreen = true }) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "Full screen")
                        }
                        Box {
                            IconButton(onClick = { newTabMenu = true }) {
                                Icon(Icons.Default.Add, contentDescription = "New session")
                            }
                            DropdownMenu(expanded = newTabMenu, onDismissRequest = { newTabMenu = false }) {
                                viewModel.installedDistros().forEach { distro ->
                                    DropdownMenuItem(
                                        text = { Text("Linux shell (${distro.replaceFirstChar { it.uppercase() }})") },
                                        onClick = { newTabMenu = false; viewModel.open(ShellKind.Linux(distro)) },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Android shell") },
                                    onClick = { newTabMenu = false; viewModel.open(ShellKind.Android) },
                                )
                                if (com.bori.app.flavor.HAS_LINUX) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Set up Linux…") },
                                        onClick = { newTabMenu = false; onOpenLinuxSetup() },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.terminalBackground)
                .padding(innerPadding)
                .imePadding(),
        ) {
            if (!fullScreen && (tabs.size > 1 || layout.split != Split.SINGLE)) {
                SessionChips(
                    tabs = tabs,
                    layout = layout,
                    colors = colors,
                    onSelect = viewModel::select,
                    onClose = viewModel::close,
                )
            }

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Side by side needs room for two usable widths; four panes need it both ways. The
                // height is the window's, so opening the keyboard doesn't undo a grid.
                val columnsFit = maxWidth >= 560.dp
                val gridFit = maxWidth >= 800.dp && LocalConfiguration.current.screenHeightDp >= 560
                LaunchedEffect(columnsFit, gridFit) {
                    canColumns = columnsFit
                    canGrid = gridFit
                }
                // After folding, a layout that no longer fits becomes stacked panes; unfolding
                // brings it back. A hidden terminal (zero size) leaves the layout alone.
                val measured = maxWidth > 0.dp && maxHeight > 0.dp
                LaunchedEffect(layout.split, columnsFit, gridFit, measured) {
                    if (!measured) return@LaunchedEffect
                    val fits = { split: Split ->
                        when (split) {
                            Split.COLUMNS -> columnsFit
                            Split.GRID -> gridFit
                            Split.SINGLE, Split.ROWS -> true
                        }
                    }
                    if (!fits(layout.split)) viewModel.squeeze(Split.ROWS) else viewModel.unsqueeze(fits)
                }

                val pane: @Composable (Int, Modifier) -> Unit = { slot, modifier ->
                    val tab = tabs.firstOrNull { it.id == layout.slots.getOrNull(slot) }
                    TerminalPane(
                        slot = slot,
                        tab = tab,
                        focused = slot == layout.focused,
                        visible = visible,
                        showFocus = layout.split != Split.SINGLE,
                        fontSp = fonts[slot],
                        onFontChange = { sp -> fonts = fonts.toMutableList().also { it[slot] = sp } },
                        viewModel = viewModel,
                        isFocused = { focusedSlot.value == slot },
                        systemKeyboard = systemKeyboard,
                        onShowKeys = openKeys,
                        onView = { view -> views[slot] = view },
                        // A replaced view may go after its successor registered; leave that one.
                        onViewGone = { view -> if (views[slot] === view) views.remove(slot) },
                        colors = colors,
                        modifier = modifier,
                    )
                }
                PaneArea(layout.split, pane, colors)

                if (fullScreen) {
                    Surface(
                        color = colors.terminalBar.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    ) {
                        IconButton(onClick = { fullScreen = false }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.FullscreenExit, contentDescription = "Exit full screen", tint = colors.terminalText)
                        }
                    }
                }
            }

            TerminalKeyboard(
                showKeys = keysShown,
                systemKeyboard = systemKeyboard,
                ctrl = ctrl,
                alt = alt,
                colors = colors,
                onToggleCtrl = viewModel::toggleCtrl,
                onToggleAlt = viewModel::toggleAlt,
                onKeyCode = { code, shift ->
                    val view = views[layout.focused]
                    if (view?.currentSession != null) {
                        // The one-shot Ctrl/Alt apply to keys too (Ctrl+← moves by word, Shift+Tab for TUIs).
                        var mod = if (shift) KeyHandler.KEYMOD_SHIFT else 0
                        if (viewModel.consumeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
                        if (viewModel.consumeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
                        view.handleKeyCode(code, mod)
                    }
                },
                onCodePoint = { cp -> views[layout.focused]?.inputCodePoint(cp, false, false) },
                predictor = viewModel.predictor,
                screenWords = { screenWords(views[layout.focused]?.currentSession) },
                isEchoed = { word -> onCursorLine(views[layout.focused]?.currentSession, word) },
                focusLineBox = focusLineBox,
                onSendLine = { line -> viewModel.sendLine(tabs.firstOrNull { it.id == layout.focusedTabId }?.session, line) },
                onShowKeys = viewModel::showKeys,
                onSystemKeyboard = switchKeyboard,
            )
        }
    }

    // Coming back from another app (e.g. the browser after a CLI sign-in) leaves nothing focused,
    // so hardware keys like Enter went nowhere until the terminal was tapped.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (visible) views[focusedSlot.value]?.requestFocus() }
}

/** Lays out [split]'s panes, with a draggable divider between two. */
@Composable
private fun PaneArea(split: Split, pane: @Composable (Int, Modifier) -> Unit, colors: BoriColors) {
    var fraction by rememberSaveable(split) { mutableFloatStateOf(0.5f) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        when (split) {
            Split.SINGLE -> pane(0, Modifier.fillMaxSize())
            Split.COLUMNS -> Row(Modifier.fillMaxSize()) {
                val total = widthPx
                pane(0, Modifier.weight(fraction).fillMaxHeight())
                PaneDivider(Orientation.Horizontal, colors) { fraction = (fraction + it / total).coerceIn(0.2f, 0.8f) }
                pane(1, Modifier.weight(1f - fraction).fillMaxHeight())
            }
            Split.ROWS -> Column(Modifier.fillMaxSize()) {
                val total = heightPx
                pane(0, Modifier.weight(fraction).fillMaxWidth())
                PaneDivider(Orientation.Vertical, colors) { fraction = (fraction + it / total).coerceIn(0.2f, 0.8f) }
                pane(1, Modifier.weight(1f - fraction).fillMaxWidth())
            }
            Split.GRID -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    pane(0, Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(2.dp).fillMaxHeight().background(colors.terminalBar))
                    pane(1, Modifier.weight(1f).fillMaxHeight())
                }
                Box(Modifier.height(2.dp).fillMaxWidth().background(colors.terminalBar))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    pane(2, Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(2.dp).fillMaxHeight().background(colors.terminalBar))
                    pane(3, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** A thin bar between two panes; dragging it moves the split. */
@Composable
private fun PaneDivider(orientation: Orientation, colors: BoriColors, onDrag: (Float) -> Unit) {
    val state = rememberDraggableState(onDrag)
    val size = if (orientation == Orientation.Horizontal) Modifier.width(8.dp).fillMaxHeight() else Modifier.height(8.dp).fillMaxWidth()
    Box(
        modifier = size
            .background(colors.terminalKeys)
            .draggable(state, orientation),
        contentAlignment = Alignment.Center,
    ) {
        val grip = if (orientation == Orientation.Horizontal) Modifier.width(2.dp).height(32.dp) else Modifier.height(2.dp).width(32.dp)
        Box(grip.background(colors.terminalText.copy(alpha = 0.4f), RoundedCornerShape(1.dp)))
    }
}

/** One pane: its own TerminalView showing [tab], or a hint when empty. */
@Composable
private fun TerminalPane(
    slot: Int,
    tab: TerminalTab?,
    focused: Boolean,
    visible: Boolean,
    showFocus: Boolean,
    fontSp: Float,
    onFontChange: (Float) -> Unit,
    viewModel: TerminalViewModel,
    isFocused: () -> Boolean,
    systemKeyboard: Boolean,
    onShowKeys: () -> Unit,
    onView: (TerminalView) -> Unit,
    onViewGone: (TerminalView) -> Unit,
    colors: BoriColors,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val currentFont = rememberUpdatedState(fontSp)

    Box(
        modifier = modifier
            .background(colors.terminalBackground)
            .border(2.dp, if (showFocus && focused) colors.terminalAccent else Color.Transparent),
    ) {
        if (tab != null) {
            key(slot) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            // setTextSize (in update) creates the renderer, monospace by default;
                            // setTypeface before that would hit a null renderer.
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) viewModel.focus(slot) }
                            view = this
                            onView(this)
                        }
                    },
                    // Only here: an effect keyed on the view would run its cleanup for the live one.
                    onRelease = { v -> onViewGone(v) },
                    update = { v ->
                        v.setTerminalViewClient(
                            ScreenViewClient(
                                view = v,
                                viewModel = viewModel,
                                onTap = { viewModel.focus(slot) },
                                isFocused = isFocused,
                                systemKeyboard = systemKeyboard,
                                onShowKeys = onShowKeys,
                                onScale = { scale ->
                                    val now = currentFont.value
                                    val next = (now * scale).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
                                    if (kotlin.math.abs(next - now) >= 1f) { onFontChange(next); 1f } else scale
                                },
                            ),
                        )
                        v.setSoftKeyboardEnabled(systemKeyboard)
                        v.setTextSize(with(density) { fontSp.sp.toPx() }.toInt())
                        v.keepScreenOn = focused && visible
                        if (v.currentSession !== tab.session) v.attachSession(tab.session)
                        v.onScreenUpdated()
                    },
                )
            }
        } else {
            Text(
                "Empty pane. Pick a session above, or tap + for a new one.",
                color = colors.terminalText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(16.dp).clickable { viewModel.focus(slot) },
            )
        }

        if (tab?.finished == true) {
            Surface(
                color = colors.terminalBar,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                    if (tab.killed) {
                        Text(
                            com.bori.core.project.PhantomProcesses.HELP,
                            color = colors.terminalText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Session ended", color = colors.terminalText)
                        TextButton(onClick = { viewModel.restart(tab) }) { Text("Restart") }
                        TextButton(onClick = { viewModel.close(tab.id) }) { Text("Close") }
                    }
                }
            }
        }
    }

    // Redraw when this pane's session prints.
    val manager = viewModel.sessionManager
    DisposableEffect(view) {
        val v = view
        val remove = manager.addScreenListener { session: TerminalSession ->
            if (v != null && v.currentSession === session) v.onScreenUpdated()
        }
        onDispose { remove() }
    }
    // The focused pane takes the keyboard; a hidden one lets go of it. Coming back into view
    // (a fold, a tab switch) redraws at once instead of waiting for the next output.
    LaunchedEffect(focused, view, tab?.id, visible) {
        val v = view ?: return@LaunchedEffect
        if (!visible) {
            v.clearFocus()
            return@LaunchedEffect
        }
        v.onScreenUpdated()
        if (focused) v.requestFocus()
    }
}

/** The sessions, as chips: tap to show one in the focused pane, ✕ to close it. */
@Composable
private fun SessionChips(
    tabs: List<TerminalTab>,
    layout: PaneLayout,
    colors: BoriColors,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.terminalBar)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val inFocused = tab.id == layout.focusedTabId
            val shown = tab.id in layout.slots
            Surface(
                shape = RoundedCornerShape(50),
                color = if (inFocused) colors.terminalAccent.copy(alpha = 0.25f) else colors.terminalKeys,
                modifier = Modifier
                    .border(1.dp, if (shown) colors.terminalAccent else Color.Transparent, RoundedCornerShape(50))
                    .clickable { onSelect(tab.id) },
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (tab.finished) "✕ " else "") + tab.title,
                        color = colors.terminalText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close session", tint = colors.terminalText, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/** Hides Android's status and navigation bars while [fullScreen]; a swipe shows them briefly. */
@Composable
private fun FullScreenEffect(fullScreen: Boolean) {
    val view = LocalView.current
    DisposableEffect(fullScreen) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullScreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullScreen) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Words on [session]'s screen and the last lines above it: file names, commands, errors. */
private fun screenWords(session: TerminalSession?): Set<String> {
    val emulator = session?.emulator ?: return emptySet()
    val screen = emulator.screen
    val top = -minOf(screen.activeTranscriptRows, 60)
    val text = screen.getSelectedText(0, top, emulator.mColumns, emulator.mRows - 1)
    return SCREEN_WORD.findAll(text).map { it.value.trimEnd('.', '-') }.filter { it.length >= 3 }.toSet()
}

/** Whether [word] is on the cursor's line or the two above (a wrap, or Enter since): the shell echoed it. */
private fun onCursorLine(session: TerminalSession?, word: String): Boolean {
    val emulator = session?.emulator ?: return false
    val row = emulator.cursorRow
    val top = maxOf(row - 2, -emulator.screen.activeTranscriptRows)
    return emulator.screen.getSelectedText(0, top, emulator.mColumns, row).contains(word)
}

private val SCREEN_WORD = Regex("[A-Za-z0-9_][A-Za-z0-9_.\\-]+")

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The view-side callbacks Termux's TerminalView needs; the keyboard's toggles feed Ctrl/Alt. */
private class ScreenViewClient(
    private val view: TerminalView,
    private val viewModel: TerminalViewModel,
    private val onTap: () -> Unit,
    private val isFocused: () -> Boolean,
    private val systemKeyboard: Boolean,
    private val onShowKeys: () -> Unit,
    private val onScale: (Float) -> Float,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = onScale.invoke(scale)

    override fun onSingleTapUp(e: MotionEvent?) {
        onTap()
        view.requestFocus()
        val imm = view.context.getSystemService(InputMethodManager::class.java)
        if (systemKeyboard) {
            imm?.showSoftInput(view, 0)
        } else {
            // A chat field may have left the phone's keyboard up.
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            onShowKeys()
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = isFocused()
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    // Only the focused pane takes the one-shot Ctrl/Alt from the keyboard.
    override fun readControlKey(): Boolean = isFocused() && viewModel.consumeCtrl()
    override fun readAltKey(): Boolean = isFocused() && viewModel.consumeAlt()
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() = Unit

    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
