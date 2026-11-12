package com.bori.app.terminal

import com.bori.app.data.db.ChatMessageEntity
import com.bori.app.service.StatusNotifier.AgentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat's line to a coding CLI running in a terminal tab (Claude Code or Antigravity): what the user
 * sends is typed into that tab, as if typed in the terminal, and the conversation is read back from
 * the CLI's own transcript. Same session either way, so the user can switch between Chat and the
 * terminal at any point. Transcripts are conversation logs; sign-in files are never read.
 */
@Singleton
class TerminalBridge @Inject constructor(
    private val manager: TerminalSessionManager,
) {
    /**
     * A terminal tab Chat can send to. [readable]: Chat can read the agent's own transcript (Claude Code,
     * Antigravity) and shows replies as messages; for any other CLI it shows the terminal's screen.
     */
    data class Target(
        val tabId: Int,
        val title: String,
        val agent: String,
        val state: AgentState?,
        val command: String? = null,
    ) {
        val readable: Boolean get() = command in AGENTS
    }

    val targets: Flow<List<Target>> = combine(manager.tabs, manager.agentStateMap) { tabs, states ->
        tabs.mapNotNull { tab ->
            val agent = tab.agent ?: tab.command?.let { AGENTS[it] }
            if (tab.finished || agent == null) null else Target(tab.id, tab.title, agent, states[tab.id], tab.command)
        }
    }.distinctUntilChanged()

    /**
     * Sends [text] to tab [tabId]. Returns why it couldn't, or null when it went. It doesn't type while
     * the agent is mid-turn or showing an approval: the text would land in the wrong place.
     */
    fun send(tabId: Int, text: String): String? {
        val tab = manager.tabs.value.firstOrNull { it.id == tabId && !it.finished } ?: return "That terminal tab is closed."
        val name = tab.agent ?: tab.command?.let { AGENTS[it] } ?: tab.title
        val lines = manager.bottomLines(tabId, SCAN_LINES).orEmpty()
        // Enter on a menu would pick whatever is highlighted: answer it with its buttons instead.
        if (CliStatus.menu(lines) != null) return "$name is asking something: pick an answer above it first."
        return when (manager.agentStateMap.value[tabId]) {
            // Asking, but its menu couldn't be read: Enter there would pick the highlighted answer.
            // At its input line (a "tell Claude what to do instead" box, or an approval already
            // answered) the text is what it waits for.
            AgentState.WAITING -> if (!CliStatus.atPrompt(lines) && CliStatus.parse(lines) == null) {
                "$name is asking something in the terminal: answer it with the buttons above first."
            } else { manager.typeLine(tab.session, text); null }
            AgentState.WORKING -> if (CliStatus.parse(lines) != null) {
                "$name is still working. Wait for it to finish, or tap Stop."
            } else { manager.typeLine(tab.session, text); null }
            else -> { manager.typeLine(tab.session, text); null }
        }
    }

    /**
     * What the CLI in a tab shows live: its status line while it works, or a menu waiting for an
     * answer. [waiting] is the approval hook's word that it's asking even when no menu was read off
     * the screen; [screenTail] then shows what it asks, so Chat can still offer Yes / No.
     */
    data class Live(
        val status: CliStatus.Status? = null,
        val menu: CliStatus.Menu? = null,
        val waiting: Boolean = false,
        val screenTail: List<String> = emptyList(),
        /** The CLI's mode when it isn't the default ("Plan mode", "Accept edits"). */
        val mode: String? = null,
    )

    /** Tab [tabId]'s live status and menu, re-read from its screen a few times a second while collected. */
    fun live(tabId: Int): Flow<Live> = flow {
        while (true) {
            val lines = manager.bottomLines(tabId, SCAN_LINES)
            if (lines == null) emit(Live()) else {
                val menu = CliStatus.menu(lines)
                val status = if (menu == null) CliStatus.parse(lines) else null
                // WAITING outlives its question (it clears on the next hook): not while the spinner runs
                // again, and not at the input line after an approval was answered or turned down.
                val waiting = manager.agentStateMap.value[tabId] == AgentState.WAITING &&
                    status == null && menu == null && !CliStatus.atPrompt(lines)
                emit(Live(
                    status = status,
                    menu = menu,
                    waiting = waiting,
                    screenTail = if (waiting) {
                        lines.map(CliStatus::clean).filter { it.isNotEmpty() }.takeLast(TAIL_LINES)
                    } else emptyList(),
                    mode = CliStatus.mode(lines),
                ))
            }
            delay(LIVE_POLL_MS)
        }
    }.flowOn(Dispatchers.Main).distinctUntilChanged()

    /**
     * Picks option [number] of the menu on tab [tabId]'s screen by pressing its number. If the screen
     * hasn't changed at all a moment later, the list didn't take the number: the cursor is moved to it
     * with the arrow keys and Enter pressed. A changed screen means the number did something, and
     * nothing more is sent: Claude Code asks the same question again for the next command, and a
     * second key there would answer a prompt the user hasn't seen.
     */
    suspend fun answer(tabId: Int, number: Int) {
        val before = withContext(Dispatchers.Main) { manager.bottomLines(tabId, SCAN_LINES) } ?: return
        val menu = CliStatus.menu(before) ?: return
        manager.writeKeys(tabId, number.toString())
        delay(ANSWER_CHECK_MS)
        val after = withContext(Dispatchers.Main) { manager.bottomLines(tabId, SCAN_LINES) } ?: return
        if (after != before) return
        val from = menu.options.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: return
        val to = menu.options.indexOfFirst { it.number == number }.takeIf { it >= 0 } ?: return
        val arrow = if (to > from) "\u001b[B" else "\u001b[A"
        manager.writeKeys(tabId, arrow.repeat(Math.abs(to - from)) + "\r")
    }

    /**
     * The plain Yes when the CLI asks but its menu couldn't be read: "1" (Claude Code's Yes), and if
     * the screen didn't move, Enter, which picks the highlighted answer (Yes is first). Only while it's
     * still asking.
     */
    suspend fun approve(tabId: Int) {
        val before = withContext(Dispatchers.Main) { manager.bottomLines(tabId, SCAN_LINES) } ?: return
        if (CliStatus.atPrompt(before)) return
        manager.writeKeys(tabId, "1")
        delay(ANSWER_CHECK_MS)
        val after = withContext(Dispatchers.Main) { manager.bottomLines(tabId, SCAN_LINES) } ?: return
        if (after == before && manager.agentStateMap.value[tabId] == AgentState.WAITING) manager.writeKeys(tabId, "\r")
    }

    /**
     * Answers the menu on tab [tabId] with typed [text], through its option [number] that asks for
     * text. Returns why it couldn't, or null when it went.
     * - "Type something." (Claude Code's AskUserQuestion) takes the text on its own row: the cursor is
     *   moved there with the arrow keys (a digit could be read as a pick), the text typed, and Enter
     *   pressed only once the text shows on screen.
     * - "No, and tell Claude what to do differently" is picked first; the text then goes to the input
     *   line it returns to.
     */
    suspend fun answerText(tabId: Int, number: Int, text: String): String? {
        val line = text.replace(Regex("\\s*\\n\\s*"), " ").trim()
        if (line.isEmpty()) return null
        val before = withContext(Dispatchers.Main) { manager.bottomLines(tabId, SCAN_LINES) } ?: return "That terminal tab is closed."
        val menu = CliStatus.menu(before) ?: return "The question is gone from the terminal."
        val option = menu.options.firstOrNull { it.number == number } ?: return "The question changed in the terminal."
        if (option.inline) {
            val from = menu.options.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: return "Couldn't find the cursor in the terminal."
            val to = menu.options.indexOf(option)
            if (to != from) {
                manager.writeKeys(tabId, (if (to > from) "\u001b[B" else "\u001b[A").repeat(Math.abs(to - from)))
                delay(KEY_SETTLE_MS)
            }
            manager.writeKeys(tabId, line)
            delay(ANSWER_CHECK_MS)
            val after = withContext(Dispatchers.Main) { manager.bottomLines(tabId, SCAN_LINES) }.orEmpty()
            val probe = line.take(TEXT_PROBE).replace(" ", "")
            val shown = after.joinToString("") { CliStatus.clean(it) }.replace(" ", "")
            if (probe !in shown) return "The text didn't show in the terminal's box. Finish the answer there."
            manager.writeKeys(tabId, "\r")
            return null
        }
        answer(tabId, number)
        // Wait for the input line the "tell it what to do instead" answer leads to.
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < PROMPT_WAIT_MS) {
            delay(READY_POLL_MS)
            val lines = withContext(Dispatchers.Main) { manager.bottomLines(tabId, SCAN_LINES) } ?: return "That terminal tab is closed."
            if (CliStatus.menu(lines) == null && CliStatus.atPrompt(lines)) {
                val tab = manager.tabs.value.firstOrNull { it.id == tabId && !it.finished } ?: return "That terminal tab is closed."
                manager.typeLine(tab.session, line)
                return null
            }
        }
        return "The terminal didn't show its input line. Send your text again in a moment."
    }

    /** ←: back to the previous question of a form that asks several at once. */
    fun back(tabId: Int) = manager.writeKeys(tabId, "\u001b[D")

    /** Esc: interrupts the agent mid-turn, or cancels the menu on screen. */
    fun interrupt(tabId: Int) = manager.writeKeys(tabId, "\u001b")

    fun showInTerminal(tabId: Int) = manager.select(tabId)

    /** An agent Chat can start: [cli] in [distroId]. */
    data class Startable(val distroId: String, val cli: CliLauncher)

    /** Every coding CLI installed, built in or the user's own, as the terminal's launcher lists them. Reads files. */
    fun startable(): List<Startable> = manager.installedDistros().flatMap { distro ->
        manager.installedClis(distro).map { Startable(distro, it) }
    }

    /** The "/" commands of the agent in [target]: its own and the user's (guest home, [projectDir]). Reads files. */
    suspend fun commands(target: Target, projectDir: File?): List<SlashCommand> = withContext(Dispatchers.IO) {
        val program = target.command?.trim()?.substringBefore(' ')?.substringAfterLast('/')
        runCatching { AgentCommands.list(program, manager.guestHome(target.tabId), projectDir) }.getOrDefault(emptyList())
    }

    /** What tab [tabId]'s terminal shows, re-read each second while collected; for CLIs without a readable transcript. */
    fun screen(tabId: Int): Flow<String> = flow {
        while (true) {
            manager.screenText(tabId)?.let { emit(it) }
            delay(SCREEN_POLL_MS)
        }
    }.flowOn(Dispatchers.Main).distinctUntilChanged()

    /**
     * Opens [s] in a new terminal tab in [hostCwd] (the open project), as the terminal's launcher does,
     * and returns the tab id. It runs whether or not the terminal is on screen. Main thread.
     */
    fun start(s: Startable, hostCwd: File?): Int =
        manager.open(ShellKind.Linux(s.distroId), hostCwd = hostCwd, startCommand = s.cli.command, title = s.cli.title).id

    /**
     * Waits until the agent just started in [tabId] has drawn its screen and gone quiet (ready for a
     * message), up to [timeoutMs]. False if it didn't.
     */
    suspend fun awaitReady(tabId: Int, timeoutMs: Long = READY_TIMEOUT_MS): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (manager.tabs.value.none { it.id == tabId && !it.finished }) return false
            val quiet = manager.quietMs(tabId)
            if (quiet != null && quiet >= READY_QUIET_MS && System.currentTimeMillis() - start >= READY_MIN_MS) return true
            delay(READY_POLL_MS)
        }
        return false
    }

    /** The conversation in tab [tabId], as chat messages; re-read each second while collected. */
    fun messages(tabId: Int): Flow<List<ChatMessageEntity>> = flow {
        emit(emptyList())
        val follower = Follower()
        while (true) {
            manager.transcriptFile(tabId)?.let { file ->
                runCatching { follower.read(file) }.getOrNull()?.let { emit(it) }
            }
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    /** Reads a transcript as it grows: the last part once, then only what's appended. */
    internal class Follower {
        private var path: String? = null
        private var offset = 0L
        private var carry = ""
        private val messages = ArrayList<ChatMessageEntity>()
        /** Tool call id → its message id, so the output can be added under it when it arrives. */
        private val toolMessages = HashMap<String, Long>()
        /** The last slash command's message id, for its output (a separate transcript line). */
        private var lastCommand: Long? = null

        /** The messages, or null when nothing changed since the last read. */
        fun read(file: File): List<ChatMessageEntity>? {
            val length = file.length()
            if (file.path != path || length < offset) {
                path = file.path
                messages.clear()
                toolMessages.clear()
                lastCommand = null
                carry = ""
                offset = (length - FIRST_READ_BYTES).coerceAtLeast(0)
            }
            if (length == offset) return null
            val chunk = RandomAccessFile(file, "r").use { f ->
                f.seek(offset)
                ByteArray((length - offset).toInt()).also { f.readFully(it) }
            }
            val firstRead = carry.isEmpty() && messages.isEmpty() && offset > 0
            offset = length
            val text = carry + String(chunk, Charsets.UTF_8)
            val lines = text.split('\n')
            carry = lines.last()
            // Started mid-file: the first line is a fragment.
            val whole = lines.dropLast(1).let { if (firstRead) it.drop(1) else it }
            val agy = file.name == "transcript.jsonl"
            whole.forEach { line -> if (agy) agyLine(line) else claudeLine(line) }
            if (messages.size > MAX_MESSAGES) messages.subList(0, messages.size - MAX_MESSAGES).clear()
            return messages.toList()
        }

        private var nextId = 1L

        private fun add(role: String, content: String): Long? {
            val text = content.trim()
            if (text.isEmpty()) return null
            val id = nextId++
            messages += ChatMessageEntity(id = id, sessionId = -1, role = role, content = text)
            if (role != "tool_result") lastCommand = null
            return id
        }

        /** Adds [output] under message [id] (a tool call's line), as the "[tool] arg" bubble shows it expanded. */
        private fun appendTo(id: Long, output: String, error: Boolean) {
            val i = messages.indexOfLast { it.id == id }
            if (i < 0) return
            val m = messages[i]
            val head = if (error && !m.content.startsWith("⚠")) "⚠ " + m.content else m.content
            messages[i] = m.copy(content = head + "\n" + clip(output))
        }

        /** One line of Claude Code's transcript (~/.claude/projects/<dir>/<session>.jsonl). */
        private fun claudeLine(line: String) {
            if ("\"type\":\"user\"" !in line && "\"type\":\"assistant\"" !in line) return
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return
            if (o.optBoolean("isSidechain") || o.optBoolean("isMeta")) return
            val content = o.optJSONObject("message")?.opt("content") ?: return
            when (o.optString("type")) {
                "user" -> when (content) {
                    is String -> userText(content)
                    is JSONArray -> {
                        blocks(content).filter { it.optString("type") == "tool_result" }.forEach { r ->
                            val id = toolMessages.remove(r.optString("tool_use_id")) ?: return@forEach
                            appendTo(id, resultText(r.opt("content")), r.optBoolean("is_error"))
                        }
                        blocks(content).filter { it.optString("type") == "text" }
                            .forEach { userText(it.optString("text")) }
                    }
                }
                "assistant" -> if (content is JSONArray) blocks(content).forEach { b ->
                    when (b.optString("type")) {
                        "text" -> add("assistant", b.optString("text"))
                        "thinking" -> add("thinking", b.optString("thinking"))
                        "tool_use" -> add("tool_result", toolLine(b.optString("name"), b.optJSONObject("input")))
                            ?.let { id -> b.optString("id").takeIf { it.isNotEmpty() }?.let { toolMessages[it] = id } }
                    }
                }
            }
        }

        /**
         * A user line: what they typed, or a slash command and its output (`<command-name>`,
         * `<local-command-stdout>`). Other `<…>` lines are the CLI's own notes and stay hidden.
         */
        private fun userText(text: String) {
            val t = text.trimStart()
            if (!t.startsWith("<")) { add("user", text); return }
            COMMAND_NAME.find(t)?.let { m ->
                val args = COMMAND_ARGS.find(t)?.groupValues?.get(1)?.trim().orEmpty()
                lastCommand = add("tool_result", "[${m.groupValues[1].trim()}] $args")
                return
            }
            val out = (COMMAND_STDOUT.find(t) ?: COMMAND_STDERR.find(t))?.groupValues?.get(1)?.let(::stripAnsi)?.trim()
            if (out.isNullOrEmpty()) return
            val cmd = lastCommand
            if (cmd != null && messages.lastOrNull()?.id == cmd) appendTo(cmd, out, error = COMMAND_STDERR.containsMatchIn(t))
            else add("tool_result", "[Command output]\n" + clip(out))
            lastCommand = null
        }

        /** A tool_result's content: a string, or text blocks (images are left out). */
        private fun resultText(c: Any?): String = when (c) {
            is String -> c
            is JSONArray -> blocks(c).filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
            else -> ""
        }.let(::stripAnsi)

        /** The first [OUTPUT_LINES] lines / [OUTPUT_CHARS] characters, with a note when cut. */
        private fun clip(text: String): String {
            val lines = text.trimEnd().lines()
            var out = lines.take(OUTPUT_LINES).joinToString("\n")
            if (out.length > OUTPUT_CHARS) out = out.take(OUTPUT_CHARS)
            return if (out.length < text.trimEnd().length) "$out\n… (more in the terminal)" else out
        }

        private fun stripAnsi(s: String) = ANSI.replace(s, "")

        /** One step of Antigravity's transcript (brain/<id>/.system_generated/logs/transcript.jsonl). */
        private fun agyLine(line: String) {
            if ("USER_INPUT" !in line && "PLANNER_RESPONSE" !in line) return
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return
            when (o.optString("type")) {
                "USER_INPUT" -> {
                    val c = o.optString("content")
                    add("user", USER_REQUEST.find(c)?.groupValues?.get(1) ?: c)
                }
                "PLANNER_RESPONSE" -> {
                    add("thinking", o.optString("thinking"))
                    add("assistant", o.optString("content"))
                    val calls = o.optJSONArray("tool_calls") ?: return
                    for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        val args = call.opt("args")?.let { a -> a as? JSONObject ?: runCatching { JSONObject(a.toString()) }.getOrNull() }
                        add("tool_result", toolLine(call.optString("name"), args))
                    }
                }
            }
        }

        private fun blocks(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

        /** "Edit · src/Main.kt", "Bash · ./gradlew build": the tool and its main argument. */
        private fun toolLine(name: String, input: JSONObject?): String {
            val arg = TOOL_ARGS.firstNotNullOfOrNull { k -> input?.optString(k)?.takeIf { it.isNotBlank() } }
                ?.lineSequence()?.first()?.take(120)
            // "[Bash] ls -la": the form Chat's tool bubble labels ("Bash · ls -la") and opens to show output under.
            return if (arg != null) "[$name] $arg" else "[$name]"
        }
    }

    private companion object {
        /** Commands Bori Code opens coding agents with, and their names. */
        val AGENTS = mapOf("claude" to "Claude Code", "antigravity" to "Antigravity")
        const val POLL_MS = 1_000L
        const val SCREEN_POLL_MS = 700L
        const val LIVE_POLL_MS = 400L

        /** How long a pressed number gets to close its menu before the arrow keys are tried. */
        const val ANSWER_CHECK_MS = 700L
        const val KEY_SETTLE_MS = 250L
        const val PROMPT_WAIT_MS = 3_000L
        const val TEXT_PROBE = 16

        /** Screen lines shown with the plain Yes / No card when no menu could be read. */
        const val TAIL_LINES = 8
        /** Screen lines read for the status line and menus: a tall menu (a plan, a question) fits. */
        const val SCAN_LINES = 40
        const val OUTPUT_LINES = 12
        const val OUTPUT_CHARS = 1_500
        val COMMAND_NAME = Regex("<command-name>([\\s\\S]*?)</command-name>")
        val COMMAND_ARGS = Regex("<command-args>([\\s\\S]*?)</command-args>")
        val COMMAND_STDOUT = Regex("<local-command-stdout>([\\s\\S]*?)</local-command-stdout>")
        val COMMAND_STDERR = Regex("<local-command-stderr>([\\s\\S]*?)</local-command-stderr>")
        val ANSI = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]")
        const val READY_TIMEOUT_MS = 25_000L
        const val READY_QUIET_MS = 1_500L
        const val READY_MIN_MS = 2_000L
        const val READY_POLL_MS = 250L
        const val FIRST_READ_BYTES = 512L * 1024
        const val MAX_MESSAGES = 300
        val USER_REQUEST = Regex("<USER_REQUEST>\\s*([\\s\\S]*?)\\s*</USER_REQUEST>")
        val TOOL_ARGS = listOf(
            "file_path", "path", "AbsolutePath", "TargetFile", "command", "CommandLine",
            "pattern", "Query", "query", "url", "description",
        )
    }
}
