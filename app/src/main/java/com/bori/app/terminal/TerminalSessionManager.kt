package com.bori.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bori.app.service.StatusNotifier
import com.bori.app.service.StatusNotifier.AgentState
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
import org.json.JSONObject
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
    /** The CLI it was opened to run ("claude", "antigravity"), or null for a plain shell. */
    val command: String? = null,
    /** The coding agent in it, by name, for the status notification; null for a plain shell. */
    val agent: String? = null,
)

/** Auto-enter on one tab: [enters] this run, and why the last one went. */
data class AutoEnterStatus(val enters: Int = 0, val lastReason: String? = null)

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

    fun installedClis(distroId: String): List<CliLauncher> = linux.installedClis(distroId)

    private val autoEnters = HashMap<Int, AutoEnter>()
    private val _autoEnter = MutableStateFlow<Map<Int, AutoEnterStatus>>(emptyMap())
    /** The tabs auto-enter is on for, by tab id. */
    val autoEnter: StateFlow<Map<Int, AutoEnterStatus>> = _autoEnter.asStateFlow()

    val autoEnterStats by lazy {
        AutoEnterStats(
            File(context.filesDir, "auto-enter"),
            mirror = context.getExternalFilesDir(null)?.let { File(it, "auto-enter/stats.json") },
        )
    }

    init {
        // Loads the counts off the main thread and refreshes the copy the PC backup reads.
        Thread({ autoEnterStats }, "auto-enter-stats").start()
    }

    private val main = Handler(Looper.getMainLooper())
    private var checkPending = false

    fun setAutoEnter(id: Int, on: Boolean) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        if (on) {
            if (id in autoEnters) return
            val distro = (tab.kind as? ShellKind.Linux)?.distroId
            val agyHome = if (tab.command == "antigravity" && distro != null) linux.guestHome(distro) else null
            autoEnters[id] = AutoEnter(tab.session, agyHome)
            _autoEnter.update { it + (id to AutoEnterStatus()) }
            ensureTicking()
        } else {
            if (autoEnters.remove(id) == null) return
            _autoEnter.update { it - id }
        }
        publish()
    }

    /** Whether the user is looking at the terminal: heads-ups are for when they aren't. */
    @Volatile var inView = false

    private val agentStates = HashMap<Int, AgentState>()
    private val agyChecked = HashMap<Int, Long>()

    init {
        linux.setAgentStateListener(::onAgentState)
    }

    /** A Claude Code hook (GuestPolicy's status script) reported [state] for tab [tabId]. */
    private fun onAgentState(tabId: Int, state: String, json: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        // Only Claude Code has the hooks, so a plain shell that reports is running it.
        if (tab.agent == null) updateTab(tab.session) { it.copy(agent = "Claude Code") }
        var detail: String? = null
        val next = when (state) {
            "working" -> AgentState.WORKING
            "done" -> AgentState.DONE
            "notify" -> {
                detail = runCatching { JSONObject(json).optString("message") }.getOrNull()?.ifBlank { null }
                // The other notification is "waiting for your input" a minute after a turn: nothing new.
                if (detail?.contains("permission", ignoreCase = true) == true) AgentState.WAITING else return
            }
            else -> return
        }
        setAgentState(tabId, next, detail)
    }

    private fun setAgentState(tabId: Int, next: AgentState, detail: String?) {
        val prev = agentStates.put(tabId, next)
        if (prev == next) return
        publish()
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        val name = tab.agent ?: tab.title
        if (inView) return
        when (next) {
            // Auto-enter answers it in a moment.
            AgentState.WAITING -> if (tabId !in autoEnters) StatusNotifier.alert(context, "$name needs you", detail ?: "It's waiting for your approval")
            AgentState.DONE -> if (prev == AgentState.WORKING) StatusNotifier.alert(context, "$name is done", tab.title)
            AgentState.WORKING -> Unit
        }
    }

    /** Antigravity has no hooks: its transcript says whether it's working, waiting or done. */
    private fun pollAgy() {
        for (tab in _tabs.value) {
            if (tab.command != "antigravity" || tab.finished) continue
            val home = (tab.kind as? ShellKind.Linux)?.distroId?.let(linux::guestHome) ?: continue
            val file = AgyTranscript.latest(home) ?: continue
            val modified = file.lastModified()
            if (agyChecked[tab.id] == modified) continue
            agyChecked[tab.id] = modified
            if (System.currentTimeMillis() - modified > AgyTranscript.MAX_AGE_MS) continue
            val turn = AgyTranscript.read(file) ?: continue
            val state = when {
                turn.pending != null || turn.asksUser -> AgentState.WAITING
                turn.complete -> AgentState.DONE
                else -> AgentState.WORKING
            }
            setAgentState(tab.id, state, turn.pending?.let { "Approve ${it.name}?" } ?: if (turn.asksUser) "It has a question for you" else null)
        }
    }

    /** Puts the agents and tabs in the status notification. */
    private fun publish() {
        val tabs = _tabs.value.filterNot { it.finished }
        val enters = _autoEnter.value
        StatusNotifier.agents = tabs.filter { it.agent != null }.map { tab ->
            StatusNotifier.Agent(tab.agent!!, agentStates[tab.id], enters[tab.id]?.enters)
        }
        StatusNotifier.terminalTabs = tabs.size
        updateWake()
        StatusNotifier.refresh(context)
    }

    /** When each session last printed; an agent that has printed nothing for a long time isn't working. */
    private val lastOutput = HashMap<TerminalSession, Long>()

    /**
     * Keeps the CPU awake while an agent works, or waits on a prompt auto-enter will answer. Not while it
     * waits for the user, and not once it has printed nothing for [WAKE_QUIET_MS] (a hook that never
     * reported "done" can't hold the phone awake). Checked again every [WAKE_CHECK_MS] while held.
     */
    private fun updateWake() {
        val now = System.currentTimeMillis()
        val needed = _tabs.value.any { tab ->
            if (tab.finished) return@any false
            val busy = when (agentStates[tab.id]) {
                AgentState.WORKING -> true
                AgentState.WAITING -> tab.id in autoEnters
                else -> false
            }
            busy && now - (lastOutput[tab.session] ?: 0L) < WAKE_QUIET_MS
        }
        if (needed == TerminalService.awake && !needed) return
        if (needed != TerminalService.awake) {
            Log.i(TAG, if (needed) "Keeping the CPU awake for a working agent" else "Agent wake lock released")
        }
        // Called again while held, so the lock's cap keeps getting pushed back.
        TerminalService.keepAwake(context, needed)
        main.removeCallbacks(wakeCheck)
        if (needed) main.postDelayed(wakeCheck, WAKE_CHECK_MS)
    }

    /** publish() runs updateWake() and refreshes the notification line. */
    private val wakeCheck = Runnable { publish() }

    private fun ensureTicking() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    /** Checks every auto-enter tab: a prompt that comes up with no output after it still gets its Enter. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            checkAutoEnter()
            pollAgy()
            if (autoEnters.isNotEmpty() || _tabs.value.any { it.command == "antigravity" && !it.finished }) {
                main.postDelayed(this, TICK_MS)
            }
        }
    }

    private fun checkAutoEnter(only: TerminalSession? = null) {
        val now = System.currentTimeMillis()
        for ((id, auto) in autoEnters.toList()) {
            val tab = _tabs.value.firstOrNull { it.id == id }
            if (tab == null || tab.finished) { setAutoEnter(id, false); continue }
            if (only != null && tab.session !== only) continue
            if (auto.check(now)) {
                autoEnterStats.add()
                _autoEnter.update { m -> m[id]?.let { m + (id to it.copy(enters = it.enters + 1, lastReason = auto.lastReason)) } ?: m }
                publish()
            } else if (auto.takeStuck()) {
                _autoEnter.update { m -> m[id]?.let { m + (id to it.copy(lastReason = auto.lastReason)) } ?: m }
                publish()
            } else if (auto.idle(now)) {
                setAutoEnter(id, false)
                StatusNotifier.alert(context, "Auto-enter is off", "Nothing to approve for 20 minutes in ${tab.agent ?: tab.title}")
            }
        }
    }

    /** New output: look again shortly, once the screen has settled. */
    private fun scheduleAutoEnterCheck() {
        if (autoEnters.isEmpty() || checkPending) return
        checkPending = true
        main.postDelayed({ checkPending = false; checkAutoEnter() }, SETTLE_MS)
    }

    /**
     * Opens a new tab running [kind] in [hostCwd]. For a Linux tab, [startCommand] runs first
     * (a coding CLI) and the tab drops to a normal shell when it exits. It's part of the shell's
     * command line: the PTY only starts once a view attaches, so typing it in would be lost.
     */
    fun open(kind: ShellKind, hostCwd: File? = null, startCommand: String? = null, title: String? = null): TerminalTab {
        val id = nextId.getAndIncrement()
        val linuxCmd = (kind as? ShellKind.Linux)?.let { linux.shellCommand(it.distroId, hostCwd, startCommand, id) }
        // No Linux environment in this build: an Android shell, under its own name.
        val shownKind = if (kind is ShellKind.Linux && linuxCmd == null) ShellKind.Android else kind
        val session = if (linuxCmd != null) linuxSession(linuxCmd) else androidShell(hostCwd)
        val tab = TerminalTab(
            id, shownKind, session,
            if (shownKind == kind) title ?: defaultTitle(kind) else defaultTitle(shownKind),
            command = startCommand.takeIf { linuxCmd != null },
            agent = title.takeIf { startCommand != null && linuxCmd != null },
        )
        _tabs.update { it + tab }
        // A new session goes into an empty pane if there is one, else the focused pane.
        _layout.update { layout ->
            val slot = layout.slots.indexOfFirst { it == null }.takeIf { it >= 0 } ?: layout.focused
            layout.copy(slots = layout.slots.toMutableList().also { it[slot] = id }, focused = slot)
        }
        TerminalService.start(context)
        publish()
        if (tab.command == "antigravity") ensureTicking()
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
        setAutoEnter(id, false)
        ProcessReaper.killTree(tab.session.pid)
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
        agentStates.remove(id)
        agyChecked.remove(id)
        lastOutput.remove(tab.session)
        publish()
        if (remaining.isEmpty()) TerminalService.stop(context)
    }

    fun closeAll() {
        _tabs.value.forEach {
            setAutoEnter(it.id, false)
            ProcessReaper.killTree(it.session.pid)
            it.session.finishIfRunning()
        }
        _tabs.value = emptyList()
        _layout.update { it.copy(slots = List(it.split.panes) { null }, focused = 0) }
        agentStates.clear()
        agyChecked.clear()
        lastOutput.clear()
        publish()
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
            lastOutput[changedSession] = System.currentTimeMillis()
            screenListeners.forEach { it(changedSession) }
            scheduleAutoEnterCheck()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            val title = changedSession.title?.takeIf { it.isNotBlank() } ?: return
            updateTab(changedSession) { it.copy(title = title) }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            val tail = finishedSession.emulator?.screen?.transcriptText?.takeLast(2_000).orEmpty()
            val killed = PhantomProcesses.wasKilled(finishedSession.exitStatus, tail)
            updateTab(finishedSession) { it.copy(finished = true, killed = killed) }
            // A proot that was killed can leave its programs running on a dead PTY.
            ProcessReaper.sweepOrphans()
            screenListeners.forEach { it(finishedSession) }
            publish()
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
        const val TICK_MS = 1_000L
        const val SETTLE_MS = 300L
        const val WAKE_QUIET_MS = 15 * 60_000L
        const val WAKE_CHECK_MS = 60_000L
    }
}
