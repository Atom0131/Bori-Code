package com.bori.core.linux.cli

import android.content.Context
import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatMessage
import com.bori.core.ai.model.ChatRequest
import com.bori.core.ai.model.CompletionChunk
import com.bori.core.ai.model.CompletionRequest
import com.bori.core.ai.model.MessageContent
import com.bori.core.ai.provider.CliProviderClient
import com.bori.core.linux.CodingCli
import com.bori.core.linux.GuestPolicy
import com.bori.core.project.PhantomProcesses
import com.bori.core.linux.ProotLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.Writer
import java.util.concurrent.TimeUnit

/**
 * Bori Code's chat driving a coding CLI (Claude Code, Antigravity) that runs inside the Linux
 * environment. The CLI is the agent: it reads, edits and runs commands itself, in the open
 * project, signed in with the user's own account. Bori Code sends the user's message, shows the
 * CLI's text and tool activity, and passes its permission questions to the user.
 *
 * Each turn starts the CLI once, headless (`-p` with stream-json), and resumes the CLI's own
 * session for that chat, so the conversation lives in the CLI; only the newest message is sent.
 * Bori Code never reads the CLIs' sign-in files; signing in happens in the CLI (Terminal tab).
 */
abstract class CliAgentClient(
    protected val proot: ProotLauncher,
    protected val guestPolicy: GuestPolicy,
    context: Context,
) : CliProviderClient {

    abstract val cli: CodingCli

    private val sessions = context.getSharedPreferences("cli_sessions", Context.MODE_PRIVATE)

    /** The distro the CLI runs in: the default one if the CLI is installed there, else any. */
    fun distro(): String? = proot.installedDistros().firstOrNull { proot.guestHasCommand(it, cli.command) }

    override fun isAvailable(): Boolean = distro() != null

    override fun complete(request: CompletionRequest): Flow<CompletionChunk> = emptyFlow()

    // The CLI manages its own context window.
    override fun maxContextTokens(): Int = 200_000

    override fun chat(request: ChatRequest): Flow<ChatChunk> = flow {
        val distroId = distro()
        if (distroId == null) {
            emit(ChatChunk.Error("${cli.title} isn't installed. Install it in More → Linux environment."))
            return@flow
        }
        val message = request.messages.lastOrNull { it.role == ChatMessage.Role.USER }
        if (message == null) {
            emit(ChatChunk.Error("Nothing to send."))
            return@flow
        }
        guestPolicy.apply(distroId)
        val cwd = proot.guestWorkspacePath(request.workingDir?.let(::File))
        val key = "${provider.name}|${request.conversationId}|$cwd"
        val turn = Turn(distroId, cwd, request.model.takeUnless { it.isBlank() || it == DEFAULT_MODEL })
        runTurn(turn, message, request.conversationId?.let { sessions.getString(key, null) }) { sessionId ->
            if (request.conversationId != null) sessions.edit().putString(key, sessionId).apply()
        }
        emit(ChatChunk.Done("end_turn"))
    }.flowOn(Dispatchers.IO)

    protected class Turn(val distroId: String, val guestCwd: String, val model: String?)

    /** Runs one turn: starts the CLI, sends [message], and emits what it does until it finishes. */
    protected abstract suspend fun FlowCollector<ChatChunk>.runTurn(
        turn: Turn,
        message: ChatMessage,
        resumeId: String?,
        saveSession: (String) -> Unit,
    )

    /**
     * Starts [guestArgv] in the turn's folder and runs [block] with its stdout and stdin. The
     * process is killed when the block ends or the chat is stopped (reads block, so cancelling
     * the coroutine alone wouldn't stop them). Returns stderr's last lines, for error messages,
     * or Android's explanation when the system killed the CLI.
     */
    protected suspend fun withCli(
        turn: Turn,
        guestArgv: List<String>,
        block: suspend (stdout: BufferedReader, stdin: Writer) -> Unit,
    ): String = coroutineScope {
        val process = proot.start(
            proot.command(turn.distroId, guestArgv, guestCwd = turn.guestCwd, binds = proot.workspaceBinds()),
            mergeStderr = false,
        )
        val stderr = ArrayDeque<String>()
        val stderrReader = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderr) {
                        stderr.addLast(line)
                        if (stderr.size > 20) stderr.removeFirst()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val killer = launch {
            try {
                awaitCancellation()
            } finally {
                process.destroy()
            }
        }
        try {
            block(process.inputStream.bufferedReader(), process.outputStream.bufferedWriter())
            // Output ended on its own: if Android killed the CLI, say so instead of a bare error.
            val exited = process.waitFor(2, TimeUnit.SECONDS)
            if (exited) stderrReader.join(500)
            val output = synchronized(stderr) { stderr.joinToString("\n") }
            if (exited && PhantomProcesses.wasKilled(process.exitValue(), output)) {
                return@coroutineScope "${cli.title} stopped. ${PhantomProcesses.HELP}"
            }
        } finally {
            killer.cancel()
            stderrReader.join(500)
        }
        synchronized(stderr) {
            stderr.filterNot { it.startsWith("proot warning") || it.startsWith("proot info") }.joinToString("\n")
        }
    }

    /** The message's text, with a note for attachments the CLI can't take. */
    protected fun textOf(message: ChatMessage): String {
        val text = message.content.filterIsInstance<MessageContent.Text>().joinToString("\n\n") { it.text }
        return if (message.content.any { it is MessageContent.Image }) "$text\n\n(An image was attached in Bori Code but not sent.)" else text
    }

    protected fun Writer.sendLine(json: JSONObject) {
        write(json.toString())
        write("\n")
        flush()
    }

    /** [path] relative to the turn's folder when it's inside it. */
    protected fun Turn.shortPath(path: String): String =
        path.removePrefix("$guestCwd/").takeIf { it != path } ?: path.removePrefix("${ProotLauncher.GUEST_WORKSPACE}/")

    protected fun clip(text: String, max: Int = 2_000): String =
        if (text.length <= max) text else text.take(max) + "\n… (${text.length - max} more characters)"

    companion object {
        /** Model entry that leaves the choice to the CLI (its own /model setting). */
        const val DEFAULT_MODEL = "default"
    }
}
