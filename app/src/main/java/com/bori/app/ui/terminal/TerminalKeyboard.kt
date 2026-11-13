package com.bori.app.ui.terminal

import com.bori.theme.components.crystalSegmentedButtonColors
import com.bori.theme.util.BoriSheet
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.time.LocalDate
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bori.app.data.prefs.TerminalKeysSettings
import com.bori.app.ui.theme.BoriColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_EVERY_MS = 50L

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

/**
 * The terminal's keyboard. The top bar is always there: Esc, Tab, Ctrl, Alt, the arrows, Home/End,
 * PgUp/PgDn, the symbols shells use and settings, then (always in reach) the auto-enter bot, the
 * direct switch, Enter and the keys toggle; those keys go straight to the terminal. Under it the
 * type bar, where a command or a prompt is typed and Enter sends it. Below that, when [showKeys],
 * Bori Keyboard itself ([keys]): its suggestion strip, keys, glide typing and panels. With
 * [systemKeyboard] the phone's keyboard types into the same bar instead (voice typing works there).
 */
@Composable
fun TerminalKeyboard(
    showKeys: Boolean,
    systemKeyboard: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    direct: Boolean,
    colors: BoriColors,
    settings: TerminalKeysSettings,
    onSettings: (TerminalKeysSettings) -> Unit,
    keys: BoriKeysController,
    settingsOpen: Boolean,
    onSettingsOpen: (Boolean) -> Unit,
    /** Auto-enter for the focused tab: on/off, or null when it can't run there. */
    autoEnter: Boolean?,
    onToggleAutoEnter: () -> Unit,
    autoEnterTotals: () -> String,
    /** Saves / merges auto-enter's counts (the PC helper's EXPORT / IMPORT STATS file); returns a message. */
    onExportStats: suspend (Uri) -> String,
    onImportStats: suspend (Uri) -> String,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleDirect: () -> Unit,
    onKeyCode: (code: Int, shift: Boolean) -> Unit,
    onShowKeys: (Boolean) -> Unit,
    onSystemKeyboard: (Boolean) -> Unit,
) {
    // While a slider is being dragged, its value shows at once; it's saved when the drag ends.
    var live by remember { mutableStateOf<TerminalKeysSettings?>(null) }
    val s = live ?: settings
    val vibration = rememberUpdatedState(s.vibration)
    val buzz = { keys.previewVibration(vibration.value) }
    val bori = showKeys && !systemKeyboard

    Column(modifier = Modifier.fillMaxWidth().background(colors.terminalKeys)) {
        TopBar(
            ctrl = ctrl,
            alt = alt,
            direct = direct,
            colors = colors,
            showKeys = showKeys,
            systemKeyboard = systemKeyboard,
            autoEnter = autoEnter,
            buzz = buzz,
            onCode = { code -> if (code == KeyEvent.KEYCODE_ENTER) keys.enter() else onKeyCode(code, false) },
            onText = keys::typeSymbol,
            onToggleCtrl = onToggleCtrl,
            onToggleAlt = onToggleAlt,
            onToggleDirect = onToggleDirect,
            onToggleAutoEnter = onToggleAutoEnter,
            onSettings = { onSettingsOpen(true) },
            onShowKeys = onShowKeys,
            onSystemKeyboard = onSystemKeyboard,
        )
        TypeBar(keys, colors, systemKeyboard)
        if (bori) BoriKeysPanel(keys)
    }

    if (settingsOpen) {
        KeysSettingsSheet(
            settings = s,
            onChange = { live = it; keys.apply(it) },
            onDone = { done -> live = null; onSettings(done) },
            onPreviewVibration = keys::previewVibration,
            autoEnterTotals = autoEnterTotals,
            onExportStats = onExportStats,
            onImportStats = onImportStats,
            onDismiss = {
                live?.let(onSettings)
                live = null
                onSettingsOpen(false)
            },
        )
    }
}

/**
 * The line being typed, on Bori's keys or the phone's keyboard. Enter (or Send) sends it; Enter
 * or Backspace in an empty bar go to the terminal.
 */
@Composable
private fun TypeBar(keys: BoriKeysController, colors: BoriColors, systemKeyboard: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            modifier = Modifier.weight(1f).padding(vertical = 2.dp),
            factory = {
                keys.edit.apply {
                    (parent as? ViewGroup)?.removeView(this)
                    val density = resources.displayMetrics.density
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    val padH = (10 * density).toInt()
                    val padV = (9 * density).toInt()
                    setPadding(padH, padV, padH, padV)
                }
            },
            update = { e ->
                e.setTextColor(colors.terminalText.toArgb())
                e.setHintTextColor(colors.terminalText.copy(alpha = 0.45f).toArgb())
                e.hint = if (systemKeyboard) "Type or speak here; Enter sends it" else "Type here; Enter sends it to the terminal"
                e.background = GradientDrawable().apply {
                    cornerRadius = 8 * e.resources.displayMetrics.density
                    setColor(colors.terminalText.copy(alpha = 0.09f).toArgb())
                }
                e.showSoftInputOnFocus = systemKeyboard
            },
        )
        IconButton(onClick = { keys.enter() }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to terminal", tint = colors.terminalAccent)
        }
    }
}

/** Bori Keyboard's own views: suggestion strip, keys, swipe trail, emoji and clipboard panels. */
@Composable
private fun BoriKeysPanel(keys: BoriKeysController) {
    DisposableEffect(keys) {
        keys.start()
        onDispose { keys.stop() }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { keys.root.apply { (parent as? ViewGroup)?.removeView(this) } },
    )
}

@Composable
private fun TopBar(
    ctrl: Boolean,
    alt: Boolean,
    direct: Boolean,
    colors: BoriColors,
    showKeys: Boolean,
    systemKeyboard: Boolean,
    autoEnter: Boolean?,
    buzz: () -> Unit,
    onCode: (Int) -> Unit,
    onText: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleDirect: () -> Unit,
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
            // Lit: Bori's keys type straight into the terminal, a key at a time (menus that take
            // one key, like 1/2/3), instead of into the type bar.
            BarButton(
                "DIRECT", null, if (direct) "Type into the type bar" else "Type straight into the terminal",
                direct, false, colors, buzz, onPress = onToggleDirect,
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeysSettingsSheet(
    settings: TerminalKeysSettings,
    onChange: (TerminalKeysSettings) -> Unit,
    onDone: (TerminalKeysSettings) -> Unit,
    onPreviewVibration: (Int) -> Unit,
    autoEnterTotals: () -> String,
    onExportStats: suspend (Uri) -> String,
    onImportStats: suspend (Uri) -> String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Bumped after an import so the totals line reads the new counts.
    var statsVersion by remember { mutableIntStateOf(0) }
    fun runStats(action: suspend () -> String) {
        scope.launch {
            Toast.makeText(context, action(), Toast.LENGTH_LONG).show()
            statsVersion++
        }
    }
    val exportStats = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { runStats { onExportStats(it) } }
    }
    val importStats = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runStats { onImportStats(it) } }
    }
    // Edits apply to the keys at once (behind the sheet) and are saved when a slider is let go.
    var s by remember { mutableStateOf(settings) }
    val update: (TerminalKeysSettings) -> Unit = { s = it; onChange(it) }
    val save: () -> Unit = { onDone(s) }
    BoriSheet(onDismiss = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
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
                        colors = crystalSegmentedButtonColors(),
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
                        colors = crystalSegmentedButtonColors(),
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
                        colors = crystalSegmentedButtonColors(),
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = { Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(label) },
                    )
                }
            }

            val totals = remember(statsVersion) { autoEnterTotals() }
            Text(
                "Auto-enter: $totals",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { exportStats.launch("agy-stats-${LocalDate.now()}.json") }) { Text("Export stats") }
                TextButton(onClick = { importStats.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                    Text("Import stats")
                }
            }
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
