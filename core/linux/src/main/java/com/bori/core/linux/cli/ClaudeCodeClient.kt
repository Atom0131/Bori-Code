package com.bori.core.linux.cli

import android.content.Context
import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatMessage
import com.bori.core.ai.model.MessageContent
import com.bori.core.linux.CodingCli
import com.bori.core.linux.GuestPolicy
import com.bori.core.linux.ProotLauncher
import com.bori.core.security.AiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.FlowCollector
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Claude Code in Bori Code's chat: `claude -p` with stream-json in and out. Its permission
 * questions come over the same pipe (`--permission-prompt-tool stdio`, as the Agent SDK does),
 * so each one shows Bori Code's approval card and is answered once; nothing is pre-approved
 * beyond the user's own Claude Code settings.
 */
@Singleton
class ClaudeCodeClient @Inject constructor(
    proot: ProotLauncher,
    guestPolicy: GuestPolicy,
    @ApplicationContext context: Context,
) : CliAgentClient(proot, guestPolicy, context) {

    override val provider: AiProvider = AiProvider.CLAUDE_CODE
    override val cli: CodingCli = CodingCli.CLAUDE_CODE

    override fun supportsToolUse(): Boolean = true

    override suspend fun FlowCollector<ChatChunk>.runTurn(
        turn: Turn,
        message: ChatMessage,
        resumeId: String?,
        saveSession: (String) -> Unit,
    ) {
        val argv = buildList {
            addAll(listOf(cli.command, "-p", "--input-format", "stream-json", "--output-format", "stream-json"))
            addAll(listOf("--verbose", "--include-partial-messages", "--permission-prompt-tool", "stdio"))
            resumeId?.let { addAll(listOf("--resume", it)) }
            turn.model?.let { addAll(listOf("--model", it)) }
        }
        var finished = false
        var anyText = false
        val stderr = withCli(turn, argv) { stdout, stdin ->
            stdin.sendLine(userMessage(message))
            // Tool calls by id, so their results can be shown with what they were run on.
            val calls = HashMap<String, Pair<String, JSONObject>>()
            while (!finished) {
                val line = stdout.readLine() ?: break
                val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (event.optString("type")) {
                    "system" -> event.optString("session_id").takeIf { it.isNotEmpty() }?.let(saveSession)
                    "stream_event" -> {
                        val e = event.optJSONObject("event") ?: continue
                        val delta = e.optJSONObject("delta")
                        if (e.optString("type") == "content_block_delta" && delta?.optString("type") == "text_delta") {
                            anyText = true
                            emit(ChatChunk.TextDelta(delta.optString("text")))
                        } else if (e.optString("type") == "message_start" && anyText) {
                            // A new model message after tool calls: keep its text apart from the last one.
                            emit(ChatChunk.TextDelta("\n\n"))
                        }
                    }
                    "assistant" -> event.optJSONObject("message")?.optJSONArray("content")?.forEachObject { block ->
                        if (block.optString("type") == "tool_use") {
                            calls[block.optString("id")] = block.optString("name") to (block.optJSONObject("input") ?: JSONObject())
                        }
                    }
                    "user" -> event.optJSONObject("message")?.optJSONArray("content")?.forEachObject { block ->
                        if (block.optString("type") != "tool_result") return@forEachObject
                        val (name, input) = calls[block.optString("tool_use_id")] ?: ("Tool" to JSONObject())
                        emit(
                            ChatChunk.AgentActivity(
                                title = name,
                                detail = turn.describe(name, input),
                                output = clip(resultText(block.opt("content"))),
                                isError = block.optBoolean("is_error"),
                            ),
                        )
                    }
                    "control_request" -> answerControlRequest(turn, event, stdin)
                    "result" -> {
                        finished = true
                        event.optString("session_id").takeIf { it.isNotEmpty() }?.let(saveSession)
                        event.optJSONObject("usage")?.let { u ->
                            val prompt = u.optInt("input_tokens") + u.optInt("cache_read_input_tokens") + u.optInt("cache_creation_input_tokens")
                            emit(ChatChunk.Usage(provider, prompt, u.optInt("output_tokens")))
                        }
                        if (event.optBoolean("is_error")) {
                            emit(ChatChunk.Error(explain(event.optString("result").ifEmpty { event.optString("subtype") })))
                        } else if (!anyText) {
                            event.optString("result").takeIf { it.isNotBlank() }?.let { emit(ChatChunk.TextDelta(it)) }
                        }
                    }
                }
            }
            stdin.close()
        }
        if (!finished) emit(ChatChunk.Error(explain(stderr.ifBlank { "Claude Code stopped without an answer." })))
    }

    private fun userMessage(message: ChatMessage): JSONObject {
        val content = JSONArray()
        message.content.forEach { block ->
            when (block) {
                is MessageContent.Text -> content.put(JSONObject().put("type", "text").put("text", block.text))
                is MessageContent.Image -> content.put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject().put("type", "base64").put("media_type", block.mediaType).put("data", block.data),
                    ),
                )
                else -> Unit
            }
        }
        return JSONObject().put("type", "user").put("message", JSONObject().put("role", "user").put("content", content))
    }

    /** Shows a tool permission question to the user and sends back their one-time answer. */
    private suspend fun FlowCollector<ChatChunk>.answerControlRequest(turn: Turn, event: JSONObject, stdin: java.io.Writer) {
        val requestId = event.optString("request_id")
        val request = event.optJSONObject("request") ?: JSONObject()
        if (request.optString("subtype") != "can_use_tool") {
            stdin.sendLine(
                JSONObject().put("type", "control_response").put(
                    "response",
                    JSONObject().put("subtype", "error").put("request_id", requestId)
                        .put("error", "Not supported by Bori Code"),
                ),
            )
            return
        }
        val tool = request.optString("tool_name")
        val input = request.optJSONObject("input") ?: JSONObject()
        val decision = CompletableDeferred<Boolean>()
        emit(ChatChunk.PermissionRequest(requestId, tool, turn.describe(tool, input, full = true), decision))
        val answer = if (decision.await()) {
            JSONObject().put("behavior", "allow").put("updatedInput", input)
        } else {
            JSONObject().put("behavior", "deny").put("message", "The user denied this in Bori Code.")
        }
        stdin.sendLine(
            JSONObject().put("type", "control_response").put(
                "response",
                JSONObject().put("subtype", "success").put("request_id", requestId).put("response", answer),
            ),
        )
    }

    /** What a tool call works on, for the chat: the file, command or pattern. */
    private fun Turn.describe(tool: String, input: JSONObject, full: Boolean = false): String {
        val path = input.optString("file_path").ifEmpty { input.optString("notebook_path") }
        val detail = when {
            path.isNotEmpty() -> shortPath(path)
            input.has("command") -> input.optString("command")
            input.has("pattern") -> input.optString("pattern")
            input.has("url") -> input.optString("url")
            input.has("description") -> input.optString("description")
            else -> input.toString()
        }
        if (!full) return clip(detail, 300)
        // The approval card also shows what an edit changes.
        val change = when (tool) {
            "Edit" -> "\n\n− ${input.optString("old_string")}\n+ ${input.optString("new_string")}"
            "Write" -> "\n\n${input.optString("content")}"
            else -> ""
        }
        return clip(detail + change, 4_000)
    }

    private fun resultText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            content.forEachObject { if (it.optString("type") == "text") appendLine(it.optString("text")) }
        }.trim()
        else -> ""
    }

    private fun explain(error: String): String =
        if ("Not logged in" in error || "/login" in error) {
            "Claude Code isn't signed in. Start it from More → Linux environment → Claude Code → Open and sign in with /login."
        } else {
            error
        }

    private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(action)
    }
}
