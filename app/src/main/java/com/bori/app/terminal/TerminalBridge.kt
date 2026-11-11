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
        return when (manager.agentStateMap.value[tabId]) {
            AgentState.WORKING -> "$name is still working. Wait for it to finish, or type in the terminal."
            AgentState.WAITING -> "$name is waiting for your approval in the terminal."
            else -> { manager.typeLine(tab.session, text); null }
        }
    }

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
    private class Follower {
        private var path: String? = null
        private var offset = 0L
        private var carry = ""
        private val messages = ArrayList<ChatMessageEntity>()

        /** The messages, or null when nothing changed since the last read. */
        fun read(file: File): List<ChatMessageEntity>? {
            val length = file.length()
            if (file.path != path || length < offset) {
                path = file.path
                messages.clear()
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

        private fun add(role: String, content: String) {
            val text = content.trim()
            if (text.isEmpty()) return
            messages += ChatMessageEntity(id = (messages.lastOrNull()?.id ?: 0) + 1, sessionId = -1, role = role, content = text)
        }

        /** One line of Claude Code's transcript (~/.claude/projects/<dir>/<session>.jsonl). */
        private fun claudeLine(line: String) {
            if ("\"type\":\"user\"" !in line && "\"type\":\"assistant\"" !in line) return
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return
            if (o.optBoolean("isSidechain") || o.optBoolean("isMeta")) return
            val content = o.optJSONObject("message")?.opt("content") ?: return
            when (o.optString("type")) {
                "user" -> when (content) {
                    is String -> if (!content.trimStart().startsWith("<")) add("user", content)
                    is JSONArray -> blocks(content).filter { it.optString("type") == "text" }
                        .map { it.optString("text") }
                        .filterNot { it.trimStart().startsWith("<") }
                        .joinToString("\n")
                        .let { add("user", it) }
                }
                "assistant" -> if (content is JSONArray) blocks(content).forEach { b ->
                    when (b.optString("type")) {
                        "text" -> add("assistant", b.optString("text"))
                        "tool_use" -> add("tool_result", toolLine(b.optString("name"), b.optJSONObject("input")))
                    }
                }
            }
        }

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
            return if (arg != null) "$name · $arg" else name
        }
    }

    private companion object {
        /** Commands Bori Code opens coding agents with, and their names. */
        val AGENTS = mapOf("claude" to "Claude Code", "antigravity" to "Antigravity")
        const val POLL_MS = 1_000L
        const val SCREEN_POLL_MS = 700L
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
