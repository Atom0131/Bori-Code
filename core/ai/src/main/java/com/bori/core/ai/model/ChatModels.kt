package com.bori.core.ai.model

import com.bori.core.security.AiProvider
import kotlinx.coroutines.CompletableDeferred

/** A single piece of content within a message — text, a tool invocation, or a tool result. */
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    /**
     * @param signature Gemini 3's opaque `thoughtSignature` for this call, echoed back
     *   verbatim on later turns. Null for other providers.
     */
    data class ToolUse(
        val id: String,
        val name: String,
        val inputJson: String,
        val signature: String? = null,
    ) : MessageContent()
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
    ) : MessageContent()
    data class Image(
        val data: String,
        val mediaType: String,
    ) : MessageContent()
    /** DeepSeek thinking-mode reasoning chain — must be echoed back verbatim on subsequent turns. */
    data class Reasoning(val text: String) : MessageContent()
}

data class ChatMessage(
    val role: Role,
    val content: List<MessageContent>,
) {
    enum class Role { SYSTEM, USER, ASSISTANT }

    /** Convenience constructor for plain-text messages (the common case). */
    constructor(role: Role, text: String) : this(role, listOf(MessageContent.Text(text)))

    /** Concatenation of all Text blocks — used for display and legacy paths. */
    val textContent: String
        get() = content.filterIsInstance<MessageContent.Text>().joinToString("") { it.text }
}

/** Describes a tool the AI may call. `inputSchema` is a JSON Schema object string. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: String,
)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String = "",
    /** When non-null, ModelRouter will try this provider before falling back to its priority list. */
    val preferredProvider: AiProvider? = null,
    val maxTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val stream: Boolean = true,
    /** Tool definitions to send with the request. Empty = no tool use. */
    val tools: List<ToolDefinition> = emptyList(),
    /**
     * The chat this request belongs to. Coding-CLI providers keep their own session per chat
     * (resumed with it) and only send the newest user message; other providers ignore it.
     */
    val conversationId: String? = null,
    /** Host folder of the open project; coding-CLI providers run in it. */
    val workingDir: String? = null,
)

/** A single streamed chunk of an assistant reply. */
sealed class ChatChunk {
    data class TextDelta(val text: String) : ChatChunk()
    data class Done(val stopReason: String) : ChatChunk()
    data class Error(val message: String) : ChatChunk()

    /** The AI has started filling in a tool call. Carries the tool id and name (and Gemini's signature). */
    data class ToolCallStart(val id: String, val name: String, val signature: String? = null) : ChatChunk()

    /** Incremental JSON fragment for the current tool call's input arguments. */
    data class ToolCallDelta(val inputJsonDelta: String) : ChatChunk()

    /** The current tool call's input JSON is complete. */
    data class ToolCallEnd(val id: String) : ChatChunk()

    /** DeepSeek thinking-mode reasoning text delta — not shown to the user, stored for echo-back. */
    data class ReasoningDelta(val text: String) : ChatChunk()

    /**
     * Token usage reported by the provider for this request. Emitted once near the end of the
     * stream (before [Done]) so the app can track per-provider token totals.
     *
     * NOTE: adding any new ChatChunk subtype (like this one) breaks exhaustive `when` blocks over
     * ChatChunk — every collector (e.g. the runAgentLoop chunk handler) must add a matching branch.
     */
    data class Usage(
        val provider: AiProvider,
        val promptTokens: Int,
        val completionTokens: Int,
    ) : ChatChunk()

    /**
     * A coding CLI finished one of its own tools (it runs them itself; Bori Code only shows them).
     * [title] is short ("Read", "Bash"), [detail] the file or command, [output] its result.
     */
    data class AgentActivity(
        val title: String,
        val detail: String,
        val output: String = "",
        val isError: Boolean = false,
    ) : ChatChunk()

    /**
     * A coding CLI asks before running a tool. The stream waits until the app completes
     * [decision]: true runs the tool once, false denies it.
     */
    data class PermissionRequest(
        val id: String,
        val tool: String,
        val detail: String,
        val decision: CompletableDeferred<Boolean>,
    ) : ChatChunk()
}
