package com.bori.app.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.prefs.SessionStateStore
import com.bori.app.data.project.ProjectStateHolder
import com.bori.app.terminal.PaneLayout
import com.bori.app.terminal.ShellKind
import com.bori.app.terminal.Split
import com.bori.app.terminal.TerminalSessionManager
import com.bori.app.terminal.TerminalTab
import com.bori.app.terminal.WordPredictor
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.delay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Screen state for the terminal. The sessions themselves live in [TerminalSessionManager] so they
 * survive this ViewModel; this tracks the keyboard and its toggles, proxies the pane layout and opens
 * the first tab.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val manager: TerminalSessionManager,
    private val projectStateHolder: ProjectStateHolder,
    private val sessionState: SessionStateStore,
    /** Suggestions and swipe decoding for the terminal keyboard. */
    val predictor: WordPredictor,
) : ViewModel() {

    val tabs: StateFlow<List<TerminalTab>> = manager.tabs
    val layout: StateFlow<PaneLayout> = manager.layout

    /** One-shot Ctrl / Alt from the extra-keys bar: they apply to the next key, then release. */
    private val _ctrl = MutableStateFlow(false)
    val ctrl: StateFlow<Boolean> = _ctrl.asStateFlow()
    private val _alt = MutableStateFlow(false)
    val alt: StateFlow<Boolean> = _alt.asStateFlow()

    /** The phone's keyboard instead of Bori's terminal keys; remembered. */
    val systemKeyboard: StateFlow<Boolean> =
        sessionState.terminalSystemKeyboard.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether Bori's keys are open (the top row always shows). Closed until the terminal is tapped, like the phone's. */
    private val _keysShown = MutableStateFlow(false)
    val keysShown: StateFlow<Boolean> = _keysShown.asStateFlow()

    val sessionManager: TerminalSessionManager get() = manager

    init {
        if (manager.tabs.value.isEmpty()) {
            val distro = manager.installedDistros().firstOrNull()
            val kind = if (distro != null) ShellKind.Linux(distro) else ShellKind.Android
            open(kind)
            // Android ended the app since last time: bring back the split, a shell in each pane.
            viewModelScope.launch {
                val split = sessionState.terminalSplit()
                    ?.let { name -> Split.entries.firstOrNull { it.name == name } }
                    ?: return@launch
                if (split == Split.SINGLE || manager.layout.value.split != Split.SINGLE) return@launch
                manager.setSplit(split)
                repeat(split.panes - 1) { open(kind) }
            }
        }
    }

    fun installedDistros(): List<String> = manager.installedDistros()

    fun open(kind: ShellKind) {
        val projectDir = projectStateHolder.activeProject.value?.rootPath?.let(::File)
        manager.open(kind, hostCwd = projectDir)
    }

    fun select(id: Int) = manager.select(id)

    fun focus(slot: Int) = manager.focus(slot)

    fun setSplit(split: Split) {
        manager.choose(split)
        viewModelScope.launch { sessionState.saveTerminalSplit(split.name) }
    }

    fun squeeze(split: Split) = manager.squeeze(split)

    fun unsqueeze(fits: (Split) -> Boolean) = manager.unsqueeze(fits)

    fun close(id: Int) = manager.close(id)

    /** Replaces a finished tab with a fresh shell of the same kind. */
    fun restart(tab: TerminalTab) {
        manager.close(tab.id)
        open(tab.kind)
    }

    fun setSystemKeyboard(system: Boolean) {
        viewModelScope.launch { sessionState.setTerminalSystemKeyboard(system) }
    }

    /**
     * Types [line] from the typing box, then Enter. Enter goes a moment later, so a TUI reading the
     * line as a paste still sees Enter as a key press (and submits).
     */
    fun sendLine(session: TerminalSession?, line: String) {
        session ?: return
        viewModelScope.launch {
            if (line.isNotEmpty()) {
                session.write(line)
                delay(40)
            }
            session.write("\r")
        }
    }

    fun showKeys(shown: Boolean) { _keysShown.value = shown }

    fun toggleCtrl() { _ctrl.value = !_ctrl.value }
    fun toggleAlt() { _alt.value = !_alt.value }

    /** Read-and-release, as TerminalView expects from TerminalViewClient.readControlKey(). */
    fun consumeCtrl(): Boolean = _ctrl.value.also { if (it) _ctrl.value = false }
    fun consumeAlt(): Boolean = _alt.value.also { if (it) _alt.value = false }
}
