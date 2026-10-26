package com.bori.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.bori.app.service.TerminalService
import com.bori.core.project.PhantomProcesses
import com.bori.core.project.ProjectRepository
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** What a terminal tab runs: Android's own shell, or a shell inside an installed Linux distro. */
sealed interface ShellKind {
    data object Android : ShellKind
    data class Linux(val distroId: String) : ShellKind
}

/** One terminal tab. [session] is Termux's PTY session; the rest is what the tab bar shows. */
data class TerminalTab(
    val id: Int,
    val kind: ShellKind,
    val session: TerminalSession,
    val title: String,
    val finished: Boolean = false,
    /** It ended because Android killed it (see [PhantomProcesses]), not because the shell exited. */
    val killed: Boolean = false,
)

/**
 * Owns every terminal session, so they outlive the Terminal screen: leaving the screen or
 * rotating detaches the view, never the shell. [TerminalService] keeps the process in the
 * foreground while any session exists.
 *
 * Sessions must be created on the main thread (TerminalSession posts its callbacks to the
 * Looper of the thread that created it).
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linux: LinuxShells,
) {
    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _layout = MutableStateFlow(PaneLayout())
    /** Which tab each visible pane shows, and which pane has the keyboard. */
    val layout: StateFlow<PaneLayout> = _layout.asStateFlow()

    private val nextId = AtomicInteger(1)

    /** Each visible terminal view redraws when its own session prints; see [addScreenListener]. */
    private val screenListeners = CopyOnWriteArraySet<(TerminalSession) -> Unit>()

    /** Registers a redraw callback for every session's output; returns its remover. */
    fun addScreenListener(listener: (TerminalSession) -> Unit): () -> Unit {
        screenListeners += listener
        return { screenListeners -= listener }
    }

    private val projectsRoot: File get() = ProjectRepository.projectsRoot(context)

    fun installedDistros(): List<String> = linux.installedDistros()

    /**
     * Opens a new tab running [kind] in [hostCwd]. For a Linux tab, [startCommand] runs first
     * (a coding CLI) and the tab drops to a normal shell when it exits. It's part of the shell's
     * command line: the PTY only starts once a view attaches, so typing it in would be lost.
     */
    fun open(kind: ShellKind, hostCwd: File? = null, startCommand: String? = null, title: String? = null): TerminalTab {
        val id = nextId.getAndIncrement()
        val linuxCmd = (kind as? ShellKind.Linux)?.let { linux.shellCommand(it.distroId, hostCwd, startCommand) }
        // No Linux environment in this build: an Android shell, under its own name.
        val shownKind = if (kind is ShellKind.Linux && linuxCmd == null) ShellKind.Android else kind
        val session = if (linuxCmd != null) linuxSession(linuxCmd) else androidShell(hostCwd)
        val tab = TerminalTab(id, shownKind, session, if (shownKind == kind) title ?: defaultTitle(kind) else defaultTitle(shownKind))
        _tabs.update { it + tab }
        // A new session goes into an empty pane if there is one, else the focused pane.
        _layout.update { layout ->
            val slot = layout.slots.indexOfFirst { it == null }.takeIf { it >= 0 } ?: layout.focused
            layout.copy(slots = layout.slots.toMutableList().also { it[slot] = id }, focused = slot)
        }
        TerminalService.start(context)
        return tab
    }

    /** Shows tab [id] in the focused pane, or focuses the pane already showing it. */
    fun select(id: Int) {
        if (_tabs.value.none { it.id == id }) return
        _layout.update { layout ->
            val shownAt = layout.slots.indexOf(id)
            if (shownAt >= 0) layout.copy(focused = shownAt)
            else layout.copy(slots = layout.slots.toMutableList().also { it[layout.focused] = id })
        }
    }

    fun focus(slot: Int) {
        _layout.update { if (slot in it.slots.indices) it.copy(focused = slot) else it }
    }

    /** Changes how many panes there are, keeping what's shown and filling new panes with hidden tabs. */
    fun setSplit(split: Split) {
        _layout.update { layout ->
            if (layout.split == split) return@update layout
            val kept = layout.slots.filterNotNull().take(split.panes)
            val hidden = _tabs.value.map { it.id }.filterNot { it in kept }.toMutableList()
            val slots = List(split.panes) { i -> kept.getOrNull(i) ?: hidden.removeFirstOrNull() }
            val focusedTab = layout.focusedTabId
            PaneLayout(split, slots, slots.indexOf(focusedTab).takeIf { it >= 0 } ?: 0)
        }
    }

    /** The layout from before a fold squeezed it, so unfolding puts every pane back. */
    private var roomyLayout: PaneLayout? = null

    /** The user picked [split]: it's the one to keep. */
    fun choose(split: Split) {
        roomyLayout = null
        setSplit(split)
    }

    /** The screen got too small for the current split: show [split] for now. */
    fun squeeze(split: Split) {
        if (roomyLayout == null) roomyLayout = _layout.value
        setSplit(split)
    }

    /** There's room again: bring back the layout from before [squeeze], if it [fits]. */
    fun unsqueeze(fits: (Split) -> Boolean) {
        val saved = roomyLayout ?: return
        if (!fits(saved.split)) return
        roomyLayout = null
        val ids = _tabs.value.map { it.id }.toSet()
        val slots = saved.slots.map { id -> id?.takeIf { it in ids } }
        val focusedTab = _layout.value.focusedTabId
        _layout.value = saved.copy(slots = slots, focused = slots.indexOf(focusedTab).takeIf { it >= 0 } ?: saved.focused)
    }

    fun close(id: Int) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        tab.session.finishIfRunning()
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        // Panes that showed it take a tab no other pane shows, or go empty.
        _layout.update { layout ->
            val slots = layout.slots.toMutableList()
            slots.indices.filter { slots[it] == id }.forEach { i ->
                slots[i] = remaining.map { it.id }.lastOrNull { it !in slots }
            }
            layout.copy(slots = slots)
        }
        if (remaining.isEmpty()) TerminalService.stop(context)
    }

    fun closeAll() {
        _tabs.value.forEach { it.session.finishIfRunning() }
        _tabs.value = emptyList()
        _layout.update { it.copy(slots = List(it.split.panes) { null }, focused = 0) }
        TerminalService.stop(context)
    }

    private fun androidShell(hostCwd: File?): TerminalSession {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val cwd = hostCwd?.takeIf { it.isDirectory } ?: projectsRoot.takeIf { it.isDirectory } ?: home
        val env = arrayOf(
            "HOME=${home.absolutePath}",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "PATH=/system/bin:/system/xbin",
        )
        return TerminalSession(
            "/system/bin/sh", cwd.absolutePath, arrayOf("-sh"), env, TRANSCRIPT_ROWS, client,
        )
    }

    private fun linuxSession(cmd: ShellCommand): TerminalSession {
        return TerminalSession(
            cmd.executable, cmd.cwd, cmd.argv.toTypedArray(), cmd.env.toTypedArray(), TRANSCRIPT_ROWS, client,
        )
    }

    private fun defaultTitle(kind: ShellKind): String = when (kind) {
        ShellKind.Android -> "Android"
        is ShellKind.Linux -> kind.distroId.replaceFirstChar { it.uppercase() }
    }

    private fun updateTab(session: TerminalSession, change: (TerminalTab) -> TerminalTab) {
        _tabs.update { tabs -> tabs.map { if (it.session === session) change(it) else it } }
    }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            screenListeners.forEach { it(changedSession) }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            val title = changedSession.title?.takeIf { it.isNotBlank() } ?: return
            updateTab(changedSession) { it.copy(title = title) }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            val tail = finishedSession.emulator?.screen?.transcriptText?.takeLast(2_000).orEmpty()
            val killed = PhantomProcesses.wasKilled(finishedSession.exitStatus, tail)
            updateTab(finishedSession) { it.copy(finished = true, killed = killed) }
            screenListeners.forEach { it(finishedSession) }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            session.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) { Log.e(TAG, "$tag: $message") }
        override fun logWarn(tag: String?, message: String?) { Log.w(TAG, "$tag: $message") }
        override fun logInfo(tag: String?, message: String?) { Log.i(TAG, "$tag: $message") }
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(TAG, "$tag: $message", e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, tag.orEmpty(), e) }
    }

    private companion object {
        const val TAG = "Terminal"
        const val TRANSCRIPT_ROWS = 4000
    }
}
