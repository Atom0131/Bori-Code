package com.bori.core.linux.cli

import android.content.Context
import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatMessage
import com.bori.core.linux.CodingCli
import com.bori.core.linux.GuestPolicy
import com.bori.core.linux.ProotLauncher
import com.bori.core.security.AiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.FlowCollector
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Antigravity CLI in Bori Code's chat: `antigravity -p=` with stream-json in and out.
 *
 * Headless Antigravity can't ask permission: it reads the project, but denies shell commands
 * and edits to the user's files, and reports them at the end of the turn. Bori Code then asks the
 * user about exactly those calls, and on "Allow" runs one more turn in which only they are
 * allowed (see [GuestPolicy.withAntigravityRules]).
 */
@Singleton
class AntigravityClient @Inject constructor(
    proot: ProotLauncher,
    guestPolicy: GuestPolicy,
    @ApplicationContext context: Context,
) : CliAgentClient(proot, guestPolicy, context) {

    override val provider: AiProvider = AiProvider.ANTIGRAVITY
    override val cli: CodingCli = CodingCli.ANTIGRAVITY

    override fun supportsToolUse(): Boolean = true

    override suspend fun FlowCollector<ChatChunk>.runTurn(
        turn: Turn,
        message: ChatMessage,
        resumeId: String?,
        saveSession: (String) -> Unit,
    ) {
        var conversation = resumeId
        var outcome = sendOnce(turn, textOf(message), conversation)
        var rounds = 0
        while (true) {
            outcome.conversationId?.let { conversation = it; saveSession(it) }
            if (outcome.denied.isEmpty() || rounds == MAX_APPROVAL_ROUNDS) break
            rounds++
            val denied = outcome.denied
            val list = denied.joinToString("\n") { "${it.label}: ${it.shown}" }
            val decision = CompletableDeferred<Boolean>()
            val what = if (denied.size == 1) denied.single().label else "${denied.size} actions"
            emit(ChatChunk.PermissionRequest("antigravity-$rounds", what, list, decision))
            if (!decision.await()) {
                emit(ChatChunk.AgentActivity("Not run", list, "Denied in Bori Code."))
                return
            }
            emit(ChatChunk.TextDelta("\n\n"))
            val approval = "The user approved these in Bori Code. Do them now exactly as before, then continue:\n$list"
            outcome = guestPolicy.withAntigravityRules(turn.distroId, denied.map { it.rule }) {
                sendOnce(turn, approval, conversation)
            }
        }
        if (outcome.otherDenials.isNotEmpty()) {
            emit(ChatChunk.TextDelta("\n\n_Antigravity was denied ${outcome.otherDenials.joinToString()}: headless mode can't ask about it, so it didn't run._"))
        }
    }

    /**
     * A tool call that may have been denied: denied calls finish with no output, and the turn's
     * result names the permission kinds ([kind]) that were denied.
     */
    private class Pending(val kind: String, val target: String, val label: String, val shown: String) {
        /** The allow rule for exactly this call (rules match by prefix). */
        val rule: String get() = "$kind($target)"
    }

    private class Outcome(
        val conversationId: String?,
        val denied: List<Pending>,
        val otherDenials: List<String>,
    )

    private suspend fun FlowCollector<ChatChunk>.sendOnce(turn: Turn, text: String, conversation: String?): Outcome {
        val argv = buildList {
            add(cli.command)
            conversation?.let { addAll(listOf("--conversation", it)) }
            turn.model?.let { addAll(listOf("--model", it)) }
            // -p must be last and take its value inline: the prompt comes from stdin.
            addAll(listOf("--input-format", "stream-json", "--output-format", "stream-json", "-p="))
        }
        var conversationId: String? = conversation
        var finished = false
        var anyText = false
        val pending = mutableListOf<Pending>()
        val deniedKinds = mutableSetOf<String>()
        val otherDenials = mutableListOf<String>()
        val stderr = withCli(turn, argv) { stdout, stdin ->
            stdin.sendLine(JSONObject().put("event", "user").put("message", JSONObject().put("content", text)))
            stdin.close()
            while (!finished) {
                val line = stdout.readLine() ?: break
                val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (event.optString("event")) {
                    "init" -> event.optString("conversation_id").takeIf { it.isNotEmpty() }?.let { conversationId = it }
                    "step_update" -> {
                        val step = event.optJSONObject("step_update") ?: continue
                        when (step.optString("step_type")) {
                            "agent_response" -> step.optString("text_delta").takeIf { it.isNotEmpty() }?.let {
                                anyText = true
                                emit(ChatChunk.TextDelta(it))
                            }
                            "tool" -> if (step.optString("state") == "DONE") {
                                val info = step.optJSONObject("tool_info") ?: JSONObject()
                                val name = info.optString("name").ifEmpty { step.optString("tool_name") }
                                val params = info.optJSONObject("parameters") ?: JSONObject()
                                val path = params.optString("Path").ifEmpty { params.optString("TargetFile") }
                                when {
                                    info.has("output") ->
                                        emit(ChatChunk.AgentActivity(title(name), turn.describe(params), clip(info.optString("output"))))
                                    name == "run_command" -> params.optString("CommandLine").let {
                                        pending += Pending("command", it, "Run", it)
                                    }
                                    name in WRITE_TOOLS && path.isNotEmpty() ->
                                        pending += Pending("write_file", path, title(name), turn.shortPath(path))
                                    else -> emit(ChatChunk.AgentActivity(title(name), turn.describe(params)))
                                }
                            }
                        }
                    }
                    "result" -> {
                        finished = true
                        val result = event.optJSONObject("result") ?: JSONObject()
                        result.optString("conversation_id").takeIf { it.isNotEmpty() }?.let { conversationId = it }
                        result.optJSONObject("usage")?.let { u ->
                            emit(ChatChunk.Usage(provider, u.optInt("input_tokens") + u.optInt("cache_read_tokens"), u.optInt("output_tokens")))
                        }
                        val denied = result.optJSONArray("denied_actions")
                        for (i in 0 until (denied?.length() ?: 0)) {
                            val action = denied!!.optJSONObject(i) ?: continue
                            val kind = action.optString("action")
                            if (pending.any { it.kind == kind }) deniedKinds += kind
                            else otherDenials += action.optString("display_name").ifEmpty { kind }
                        }
                        if (result.optString("status") == "ERROR") {
                            emit(ChatChunk.Error(explain(result.optString("error"))))
                        } else if (!anyText && deniedKinds.isEmpty()) {
                            result.optString("response").takeIf { it.isNotBlank() }?.let { emit(ChatChunk.TextDelta(it)) }
                        }
                    }
                }
            }
        }
        if (!finished) emit(ChatChunk.Error(explain(stderr.ifBlank { "Antigravity stopped without an answer." })))
        // Calls of a kind that wasn't denied did run; show them like the other tools.
        val (denied, ran) = pending.partition { it.kind in deniedKinds }
        ran.forEach { emit(ChatChunk.AgentActivity(it.label, it.shown)) }
        return Outcome(conversationId, denied.distinctBy { it.rule }, otherDenials)
    }

    private fun title(tool: String): String = when (tool) {
        "read_file", "view_file" -> "Read"
        "write_file" -> "Write"
        "edit_file", "replace_file_content", "multi_replace_file_content" -> "Edit"
        "run_command" -> "Run"
        "list_dir", "find_by_name" -> "List"
        "grep_search" -> "Search"
        else -> tool
    }

    private fun Turn.describe(params: JSONObject): String {
        val path = listOf("Path", "AbsolutePath", "TargetFile", "DirectoryPath", "SearchPath")
            .firstNotNullOfOrNull { k -> params.optString(k).takeIf { it.isNotEmpty() } }
        return when {
            path != null -> shortPath(path)
            params.has("CommandLine") -> params.optString("CommandLine")
            params.has("Query") -> params.optString("Query")
            params.has("Pattern") -> params.optString("Pattern")
            else -> clip(params.toString(), 300)
        }
    }

    private fun explain(error: String): String =
        if ("sign in" in error.lowercase() || "login" in error.lowercase() || "authenticat" in error.lowercase()) {
            "Antigravity isn't signed in. Open More → Linux environment → Antigravity CLI → Open and sign in.\n\n($error)"
        } else {
            error
        }

    private companion object {
        /** Tools that need Antigravity's write_file permission. */
        val WRITE_TOOLS = setOf("write_file", "edit_file", "replace_file_content", "multi_replace_file_content")

        /** Approval rounds per message, so a model that keeps asking can't loop forever. */
        const val MAX_APPROVAL_ROUNDS = 3
    }
}
