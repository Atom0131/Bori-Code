package com.bori.app.ui.terminal

import com.bori.theme.util.BoriAlertDialog
import com.bori.theme.util.BoriDropdownMenu
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bori.app.terminal.AutoEnterStats
import com.bori.app.terminal.AutoEnterStatus
import com.bori.app.terminal.CliLauncher
import com.bori.app.terminal.PaneLayout
import com.bori.app.terminal.ShellKind
import com.bori.app.terminal.Split
import com.bori.app.terminal.TerminalTab
import com.bori.app.terminal.ShellPrompt
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
    val autoEnter by viewModel.autoEnter.collectAsStateWithLifecycle()
    val autoEnterExplained by viewModel.autoEnterExplained.collectAsStateWithLifecycle()
    var explainAutoEnter by remember { mutableStateOf<Int?>(null) }
    val focusedTab = tabs.firstOrNull { it.id == layout.focusedTabId }
    // Turning it on the first time explains what it does; after that it's one tap.
    val toggleAutoEnter: (Int) -> Unit = { id ->
        when {
            id in autoEnter -> viewModel.setAutoEnter(id, false)
            autoEnterExplained -> viewModel.setAutoEnter(id, true)
            else -> explainAutoEnter = id
        }
    }
    val colors = BoriTheme.colors

    var fonts by rememberSaveable { mutableStateOf(List(Split.GRID.panes) { DEFAULT_FONT_SP }) }
    var newTabMenu by remember { mutableStateOf(false) }
    var agentMenu by remember { mutableStateOf(false) }
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
    // Keys go to the focused pane; one-shot Ctrl/Alt apply to them too (Ctrl+← moves by word, Shift+Tab for TUIs).
    val sendKeyCode: (Int, Boolean) -> Unit = { code, shift ->
        val view = views[focusedSlot.value]
        if (view?.currentSession != null) {
            var mod = if (shift) KeyHandler.KEYMOD_SHIFT else 0
            if (viewModel.consumeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
            if (viewModel.consumeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
            view.handleKeyCode(code, mod)
        }
    }
    val keysSettings by viewModel.keysSettings.collectAsStateWithLifecycle()
    var keysSettingsOpen by remember { mutableStateOf(false) }
    var direct by remember { mutableStateOf(false) }
    // Set below, once the keys exist; the globe key on the keys asks for it.
    var usePhoneKeyboard by remember { mutableStateOf({}) }
    // Bori Keyboard, typing into the type bar (or, for Ctrl/Alt keys and in direct mode, the terminal).
    val hostContext = LocalContext.current
    val keys = remember {
        BoriKeysController(hostContext, object : BoriKeysController.Terminal {
            override fun sendLine(line: String) =
                viewModel.sendLine(viewModel.tabs.value.firstOrNull { it.id == viewModel.layout.value.focusedTabId }?.session, line)
            override fun key(code: Int) = sendKeyCode(code, false)
            override fun text(s: String) {
                val view = views[focusedSlot.value] ?: return
                s.codePoints().forEach { view.inputCodePoint(it, false, false) }
            }
            override fun modifierPending(): Boolean = viewModel.ctrl.value || viewModel.alt.value
            override fun atShellPrompt(): Boolean =
                ShellPrompt.REGEX.containsMatchIn(lineBeforeCursor(views[focusedSlot.value]?.currentSession))
            override fun openSettings() { keysSettingsOpen = true }
            override fun usePhoneKeyboard() = usePhoneKeyboard.invoke()
        })
    }
    DisposableEffect(keys) { onDispose { keys.dispose() } }
    LaunchedEffect(keysSettings) { keys.apply(keysSettings) }
    LaunchedEffect(direct) { keys.direct = direct }

    // Switching keyboards: the phone's comes up at once (into the type bar), and goes away for
    // Bori's keys. Done here, on the tap, so the saved choice loading or the terminal coming into
    // view never pops or closes a keyboard.
    val switchKeyboard: (Boolean) -> Unit = { system ->
        viewModel.setSystemKeyboard(system)
        if (system) {
            keys.focusBar(phoneKeyboard = true)
        } else {
            keys.focusBar(phoneKeyboard = false)
            openKeys()
        }
    }
    usePhoneKeyboard = { switchKeyboard(true) }
    // A tap on the type bar brings up the keyboard in use, even with a physical keyboard attached.
    val usingSystemKeyboard = rememberUpdatedState(systemKeyboard)
    keys.onBarTapped = {
        if (usingSystemKeyboard.value) keys.focusBar(phoneKeyboard = true) else viewModel.showKeys(true)
    }
    // A tap on the terminal: the cursor goes to the type bar, with the keyboard in use. A physical
    // keyboard keeps typing into the terminal itself.
    val tapTerminal: () -> Unit = {
        when {
            systemKeyboard -> keys.focusBar(phoneKeyboard = true)
            hasHardKeyboard.value -> Unit
            else -> {
                openKeys()
                keys.focusBar(phoneKeyboard = false)
            }
        }
    }

    // Keyboard up with little room left (landscape on the unfolded Fold, a tall keyboard): the title
    // bar and session chips step aside, as in full screen, so the terminal itself stays visible.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val roomDp = LocalConfiguration.current.screenHeightDp.dp - imeDp
    val keyboardCrowded = imeDp > 0.dp && roomDp < KEYBOARD_ROOM_MIN

    Scaffold(
        containerColor = colors.terminalBackground,
        // The shell's bottom bar (or its padding when unfolded) already clears the gesture bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (!fullScreen && !keyboardCrowded) {
                BoriTopBar(
                    title = "Terminal",
                    onBack = onBack,
                    // The theme's bar, like every other screen; only the terminal canvas stays black.
                    actions = {
                        Box {
                            IconButton(onClick = { splitMenu = true }) {
                                Icon(Icons.Default.SpaceDashboard, contentDescription = "Split")
                            }
                            BoriDropdownMenu(expanded = splitMenu, onDismissRequest = { splitMenu = false }) {
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
                        if (focusedTab != null && focusedTab.kind is ShellKind.Linux && !focusedTab.finished) {
                            val on = focusedTab.id in autoEnter
                            // The bot: it presses Enter for the agent's approval prompts.
                            IconButton(onClick = { toggleAutoEnter(focusedTab.id) }) {
                                Icon(
                                    Icons.Default.SmartToy,
                                    contentDescription = if (on) "Turn auto-enter off" else "Turn auto-enter on",
                                    tint = if (on) colors.terminalAccent else colors.terminalText,
                                )
                            }
                        }
                        if (com.bori.app.flavor.HAS_LINUX) {
                            Box {
                                IconButton(
                                    onClick = { agentMenu = true },
                                    modifier = Modifier.semantics { contentDescription = "Open a coding CLI" },
                                ) {
                                    CodeRainIcon(tint = colors.terminalText)
                                }
                                BoriDropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                                    val clis = remember(agentMenu) { if (agentMenu) viewModel.clis() else emptyList() }
                                    val distros = clis.map { it.first }.distinct().size
                                    clis.forEach { (distro, cli) ->
                                        DropdownMenuItem(
                                            text = { Text(cliLabel(cli, distro, distros > 1)) },
                                            onClick = { agentMenu = false; viewModel.openCli(distro, cli) },
                                        )
                                    }
                                    if (clis.isNotEmpty()) HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(if (clis.isEmpty()) "Install Claude Code or Antigravity…" else "Manage coding CLIs…") },
                                        onClick = { agentMenu = false; onOpenLinuxSetup() },
                                    )
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
                            BoriDropdownMenu(expanded = newTabMenu, onDismissRequest = { newTabMenu = false }) {
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
            if (!fullScreen && !keyboardCrowded && (tabs.size > 1 || layout.split != Split.SINGLE)) {
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
                        onTap = tapTerminal,
                        onView = { view -> views[slot] = view },
                        // A replaced view may go after its successor registered; leave that one.
                        onViewGone = { view -> if (views[slot] === view) views.remove(slot) },
                        launcher = { Launcher(viewModel, slot, onOpenLinuxSetup, colors) },
                        autoEnter = tab?.let { autoEnter[it.id] },
                        onAutoEnterOff = { tab?.let { viewModel.setAutoEnter(it.id, false) } },
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
                direct = direct,
                colors = colors,
                settings = keysSettings,
                onSettings = viewModel::setKeysSettings,
                keys = keys,
                settingsOpen = keysSettingsOpen,
                onSettingsOpen = { keysSettingsOpen = it },
                autoEnter = focusedTab?.takeIf { it.kind is ShellKind.Linux && !it.finished }?.let { it.id in autoEnter },
                onToggleAutoEnter = { focusedTab?.let { toggleAutoEnter(it.id) } },
                autoEnterTotals = {
                    val t = viewModel.autoEnterTotals()
                    "${t.today} Enters today, ${t.week} this week, ${t.month} this month, ${t.all} in all.\n" +
                        "Time saved: ${AutoEnterStats.timeSaved(t.all)} in all, ${AutoEnterStats.timeSaved(t.week)} this week " +
                        "(about ${AutoEnterStats.SECONDS_SAVED_PER_ENTER} s per Enter)"
                },
                onExportStats = viewModel::exportAutoEnterStats,
                onImportStats = viewModel::importAutoEnterStats,
                onToggleCtrl = viewModel::toggleCtrl,
                onToggleAlt = viewModel::toggleAlt,
                onToggleDirect = { direct = !direct },
                onKeyCode = sendKeyCode,
                onShowKeys = viewModel::showKeys,
                onSystemKeyboard = switchKeyboard,
            )
        }
    }

    explainAutoEnter?.let { id ->
        val totals = remember { viewModel.autoEnterTotals() }
        BoriAlertDialog(
            onDismissRequest = { explainAutoEnter = null },
            title = { Text("Auto-enter") },
            text = {
                Text(
                    "Bori presses Enter for you when the CLI in this tab asks to approve a step " +
                        "(\"Do you want to proceed?\", [y/n], ❯ Yes). That approves every command and edit " +
                        "it asks about, so only use it on work you trust. It never answers a question or a plan, " +
                        "Linux's blocked commands stay blocked, and it turns itself off after 20 minutes " +
                        "with nothing to approve.\n\nEnters so far: ${totals.today} today, ${totals.week} this week, ${totals.all} in all " +
                        "(${AutoEnterStats.timeSaved(totals.all)} saved).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.markAutoEnterExplained()
                    viewModel.setAutoEnter(id, true)
                    explainAutoEnter = null
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { explainAutoEnter = null }) { Text("Cancel") } },
        )
    }

    // Agent heads-ups are for when the terminal isn't in front of the user.
    LifecycleResumeEffect(visible) {
        viewModel.sessionManager.inView = visible
        onPauseOrDispose { viewModel.sessionManager.inView = false }
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
    onTap: () -> Unit,
    onView: (TerminalView) -> Unit,
    onViewGone: (TerminalView) -> Unit,
    launcher: @Composable () -> Unit,
    autoEnter: AutoEnterStatus?,
    onAutoEnterOff: () -> Unit,
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
                                onTap = { viewModel.focus(slot); onTap() },
                                isFocused = isFocused,
                                onScale = { scale ->
                                    val now = currentFont.value
                                    val next = (now * scale).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
                                    if (kotlin.math.abs(next - now) >= 1f) { onFontChange(next); 1f } else scale
                                },
                            ),
                        )
                        // Text goes through the type bar; the terminal never takes the phone's keyboard.
                        v.setSoftKeyboardEnabled(false)
                        v.setTextSize(with(density) { fontSp.sp.toPx() }.toInt())
                        v.keepScreenOn = focused && visible
                        if (v.currentSession !== tab.session) v.attachSession(tab.session)
                        v.onScreenUpdated()
                    },
                )
            }
        } else {
            Box(Modifier.align(Alignment.Center)) { launcher() }
        }

        if (autoEnter != null) {
            // Always in sight, full screen too; a tap turns it off.
            Surface(
                color = colors.terminalAccent.copy(alpha = 0.85f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.TopCenter).padding(6.dp).clickable(onClick = onAutoEnterOff),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Icon(Icons.Default.SmartToy, contentDescription = null, tint = colors.terminalBackground, modifier = Modifier.size(14.dp))
                    Text(" auto-enter · ${autoEnter.enters}", color = colors.terminalBackground, fontSize = 12.sp)
                }
            }
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

    // Redraw when this pane's session prints: at most once a frame, and not while the pane is hidden or the
    // app is in the background (coming back into view redraws, below). Agents print all the time.
    val manager = viewModel.sessionManager
    val shown = rememberUpdatedState(visible)
    DisposableEffect(view) {
        val v = view
        var queued = false
        val redraw = Runnable {
            queued = false
            if (shown.value && manager.inView) v?.onScreenUpdated()
        }
        val remove = manager.addScreenListener { session: TerminalSession ->
            if (v != null && v.currentSession === session && !queued && shown.value && manager.inView) {
                queued = true
                v.postOnAnimation(redraw)
            }
        }
        onDispose {
            remove()
            v?.removeCallbacks(redraw)
        }
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
    // Output that came while the app was in the background wasn't drawn.
    LifecycleResumeEffect(view) {
        view?.onScreenUpdated()
        onPauseOrDispose { }
    }
}

private fun cliLabel(cli: CliLauncher, distro: String, showDistro: Boolean) =
    (if (showDistro) "${cli.title} (${distro.replaceFirstChar { it.uppercase() }})" else cli.title) +
        if (cli.custom) " · your own" else ""

/** An empty pane: one tap opens a shell or a coding CLI in it. */
@Composable
private fun Launcher(viewModel: TerminalViewModel, slot: Int, onOpenLinuxSetup: () -> Unit, colors: BoriColors) {
    val distros = remember { viewModel.installedDistros() }
    val clis = remember { viewModel.clis() }
    val open: (() -> Unit) -> Unit = { action -> viewModel.focus(slot); action() }
    Column(
        modifier = Modifier.padding(16.dp).widthIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Open in this pane", color = colors.terminalText, style = MaterialTheme.typography.titleSmall)
        clis.forEach { (distro, cli) ->
            OutlinedButton(onClick = { open { viewModel.openCli(distro, cli) } }, modifier = Modifier.fillMaxWidth()) {
                Text(cliLabel(cli, distro, distros.size > 1), color = colors.terminalText)
            }
        }
        distros.forEach { distro ->
            OutlinedButton(onClick = { open { viewModel.open(ShellKind.Linux(distro)) } }, modifier = Modifier.fillMaxWidth()) {
                Text("Linux shell (${distro.replaceFirstChar { it.uppercase() }})", color = colors.terminalText)
            }
        }
        OutlinedButton(onClick = { open { viewModel.open(ShellKind.Android) } }, modifier = Modifier.fillMaxWidth()) {
            Text("Android shell", color = colors.terminalText)
        }
        if (com.bori.app.flavor.HAS_LINUX && distros.isEmpty()) {
            TextButton(onClick = onOpenLinuxSetup) { Text("Set up Linux…") }
        }
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
internal fun FullScreenEffect(fullScreen: Boolean) {
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
/** The cursor's line up to the cursor: a shell prompt, or an agent's input box. */
private fun lineBeforeCursor(session: TerminalSession?): String {
    val emulator = session?.emulator ?: return ""
    val screen = emulator.screen
    val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(emulator.cursorRow))
    val end = row.findStartOfColumn(emulator.cursorCol.coerceIn(0, emulator.mColumns))
    return String(row.mText, 0, end)
}

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
    private val onScale: (Float) -> Float,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = onScale.invoke(scale)

    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        onTap()
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

    // Into logcat, so a keyboard or rendering problem leaves a trace to read later.
    override fun logError(tag: String?, message: String?) { Log.e(LOG_TAG, "$tag: $message") }
    override fun logWarn(tag: String?, message: String?) { Log.w(LOG_TAG, "$tag: $message") }
    override fun logInfo(tag: String?, message: String?) { Log.i(LOG_TAG, "$tag: $message") }
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(LOG_TAG, "$tag: $message", e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(LOG_TAG, tag.orEmpty(), e) }
}

private const val LOG_TAG = "BoriTerm"

/** Below this much height above the keyboard, the Terminal's title bar and session chips hide. */
private val KEYBOARD_ROOM_MIN = 520.dp
