package com.bori.core.ai.provider

import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatMessage
import com.bori.core.ai.model.ChatRequest
import com.bori.core.ai.model.CompletionChunk
import com.bori.core.ai.model.CompletionRequest
import com.bori.core.ai.model.MessageContent
import com.bori.core.ai.model.ModelCatalog
import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic (Claude) provider client.
 *
 * Anthropic uses a different API format from OpenAI, so this is a standalone
 * implementation rather than a subclass of [OpenAiCompatClient].
 *
 * API reference: https://docs.anthropic.com/en/api/messages
 *
 * Key differences from OpenAI:
 *  - Auth header: `x-api-key` instead of `Authorization: Bearer`
 *  - Requires `anthropic-version` header
 *  - System message is a top-level field, not a messages array entry
 *  - SSE event types: `content_block_delta`, `message_stop`
 *  - Tool use: `content_block_start` (type=tool_use), `content_block_delta` (input_json_delta),
 *    `content_block_stop`
 */
@Singleton
class AnthropicClient @Inject constructor(
    private val keyVault: KeyVault,
) : AiProviderClient {

    override val provider: AiProvider = AiProvider.ANTHROPIC

    override fun supportsToolUse(): Boolean = true

    override fun maxContextTokens(): Int = 200_000  // conservative floor; current Claude models take 1M

    private val okClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .addInterceptor(RedactingLoggingInterceptor())
            .build()
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)
        val ids = mutableListOf<String>()
        var afterId = ""
        do {
            val httpRequest = Request.Builder()
                .url("https://api.anthropic.com/v1/models?limit=1000" + if (afterId.isNotEmpty()) "&after_id=$afterId" else "")
                .get()
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .build()
            val json = okClient.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("Anthropic error: HTTP ${response.code} $body")
                JSONObject(body)
            }
            ids += parseModelIds(json.toString())
            afterId = if (json.optBoolean("has_more")) json.optString("last_id") else ""
        } while (afterId.isNotEmpty())
        ids.sorted()
    }

    /*
     * Thinking: current Claude models think by default (adaptive) and stream `thinking`
     * blocks, which this client skips rather than echoing back. Bori Code rebuilds the system
     * prompt every turn (open file, repo map), and a replayed thinking block is only valid
     * while everything before it is unchanged, so echoing them would fail; leaving them out
     * of history is the API's documented fallback. No `temperature` is sent either: the
     * current models reject non-default sampling parameters.
     */
    override fun chat(request: ChatRequest): Flow<ChatChunk> = callbackFlow {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)

        val body = buildMessagesBody(request).toString()
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .build()

        val call = okClient.newCall(httpRequest)
        call.enqueue(object : Callback {
            // Tracks the id of the tool_use block currently being streamed.
            private var currentToolId: String? = null
            // Token usage accrued across the stream (input from message_start, output from message_delta).
            private var inputTokens = 0
            private var outputTokens = 0

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: "HTTP ${response.code}"
                    close(IOException("Anthropic error: $error"))
                    return
                }
                response.body?.source()?.let { source ->
                    try {
                        while (!source.exhausted() && isActive) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.startsWith("data: ") -> {
                                    val data = line.removePrefix("data: ").trim()
                                    accrueUsage(data)
                                    parseAnthropicChunk(data, currentToolId)?.let { chunk ->
                                        // Keep track of which tool_use block is in flight
                                        when (chunk) {
                                            is ChatChunk.ToolCallStart -> currentToolId = chunk.id
                                            is ChatChunk.ToolCallEnd  -> currentToolId = null
                                            else -> { /* no state change */ }
                                        }
                                        trySend(chunk)
                                    }
                                }
                                line.startsWith("event: message_stop") -> {
                                    // Emit the accrued token usage once, just before Done.
                                    if (inputTokens > 0 || outputTokens > 0) {
                                        trySend(ChatChunk.Usage(provider, inputTokens, outputTokens))
                                    }
                                    trySend(ChatChunk.Done("end_turn"))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        close(e)
                        return
                    }
                }
                close()
            }

            /** Pulls token counts out of message_start / message_delta events. */
            private fun accrueUsage(data: String) {
                runCatching {
                    val o = org.json.JSONObject(data)
                    when (o.optString("type")) {
                        "message_start" -> o.optJSONObject("message")?.optJSONObject("usage")?.let {
                            inputTokens = it.optInt("input_tokens", inputTokens)
                            outputTokens = it.optInt("output_tokens", outputTokens)
                        }
                        "message_delta" -> o.optJSONObject("usage")?.let {
                            outputTokens = it.optInt("output_tokens", outputTokens)
                        }
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }
        })

        awaitClose { call.cancel() }
    }

    /** Inline completions are proxied through the chat endpoint using a FIM-style prompt. */
    override fun complete(request: CompletionRequest): Flow<CompletionChunk> = callbackFlow {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)

        val fimPrompt = if (request.suffix.isBlank()) {
            "Complete the following code. Output only the completion:\n\n${request.prefix}"
        } else {
            "Complete the code between <prefix> and <suffix>. Output only the inserted code.\n\n" +
                "<prefix>${request.prefix}</prefix><suffix>${request.suffix}</suffix>"
        }

        val body = JSONObject().apply {
            put("model", request.model)
            put("max_tokens", cappedMaxTokens(request.model, request.maxTokens))
            put("stream", true)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", fimPrompt)
                })
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .build()

        val call = okClient.newCall(httpRequest)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    close(IOException("Anthropic error: HTTP ${response.code}"))
                    return
                }
                response.body?.source()?.let { source ->
                    try {
                        while (!source.exhausted() && isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data: ")) continue
                            val data = line.removePrefix("data: ").trim()
                            val text = extractAnthropicText(data) ?: continue
                            trySend(CompletionChunk(text))
                        }
                    } catch (e: Exception) {
                        close(e)
                        return
                    }
                }
                close()
            }

            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }
        })

        awaitClose { call.cancel() }
    }

    /**
     * Builds the JSON body for /v1/messages.
     *
     * - System message is extracted as a top-level field.
     * - Each message's content list is serialized as an Anthropic content array.
     * - If [request.tools] is non-empty, a "tools" array is included.
     */
    private fun buildMessagesBody(request: ChatRequest): JSONObject {
        val systemMessage = request.messages
            .firstOrNull { it.role == ChatMessage.Role.SYSTEM }
            ?.textContent

        val userAndAssistantMessages = request.messages
            .filter { it.role != ChatMessage.Role.SYSTEM }

        val messagesArray = JSONArray().apply {
            userAndAssistantMessages.forEach { msg ->
                put(JSONObject().apply {
                    put("role", msg.role.name.lowercase())
                    put("content", serializeContent(msg.content))
                })
            }
        }

        return JSONObject().apply {
            put("model", request.model)
            put("max_tokens", cappedMaxTokens(request.model, request.maxTokens))
            put("stream", request.stream)
            put("messages", messagesArray)
            if (!systemMessage.isNullOrBlank()) put("system", systemMessage)
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    request.tools.forEach { tool ->
                        put(JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", JSONObject(tool.inputSchema))
                        })
                    }
                })
            }
        }
    }

    /**
     * Converts a list of [MessageContent] to an Anthropic content array.
     *
     * If the list contains only a single [MessageContent.Text] we still use the
     * array format so that Anthropic accepts it uniformly.
     */
    private fun serializeContent(contents: List<MessageContent>): JSONArray {
        return JSONArray().apply {
            contents.forEach { item ->
                when (item) {
                    is MessageContent.Text -> put(JSONObject().apply {
                        put("type", "text")
                        put("text", item.text)
                    })
                    is MessageContent.ToolUse -> put(JSONObject().apply {
                        put("type", "tool_use")
                        put("id", item.id)
                        put("name", item.name)
                        put("input", JSONObject(item.inputJson))
                    })
                    is MessageContent.ToolResult -> put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", item.toolUseId)
                        put("content", item.content)
                        if (item.isError) put("is_error", true)
                    })
                    is MessageContent.Image -> put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", item.mediaType)
                            put("data", item.data)
                        })
                    })
                    is MessageContent.Reasoning -> { /* DeepSeek-only — Anthropic has no reasoning_content field */ }
                }
            }
        }
    }

    /**
     * Parses a single SSE data line from the Anthropic streaming response.
     *
     * Handles:
     *  - `content_block_start`  → [ChatChunk.ToolCallStart] when it's a tool_use block
     *  - `content_block_delta`  → [ChatChunk.TextDelta] for text, [ChatChunk.ToolCallDelta] for tool input
     *  - `content_block_stop`   → [ChatChunk.ToolCallEnd] when a tool_use block was open
     *  - `message_delta`        → [ChatChunk.Done] on stop reason
     */
    private fun parseAnthropicChunk(json: String, currentToolId: String?): ChatChunk? {
        return try {
            val obj = JSONObject(json)
            when (val type = obj.optString("type")) {
                "content_block_start" -> {
                    val block = obj.optJSONObject("content_block") ?: return null
                    if (block.optString("type") == "tool_use") {
                        ChatChunk.ToolCallStart(
                            id = block.optString("id"),
                            name = block.optString("name"),
                        )
                    } else null
                }
                "content_block_delta" -> {
                    val delta = obj.optJSONObject("delta") ?: return null
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val text = delta.optString("text")
                            if (text.isNullOrEmpty()) null else ChatChunk.TextDelta(text)
                        }
                        "input_json_delta" -> {
                            val partial = delta.optString("partial_json")
                            if (partial.isNullOrEmpty()) null
                            else ChatChunk.ToolCallDelta(partial)
                        }
                        else -> null
                    }
                }
                "content_block_stop" -> {
                    // Signal end of tool call if one was open
                    if (currentToolId != null) ChatChunk.ToolCallEnd(currentToolId) else null
                }
                "message_delta" -> {
                    val delta = obj.optJSONObject("delta") ?: return null
                    val stopReason = delta.optString("stop_reason")
                    when {
                        // Safety classifiers can decline a request with HTTP 200; say so instead of going quiet
                        stopReason == "refusal" -> ChatChunk.Error(
                            "Claude declined this request" +
                                (delta.optJSONObject("stop_details")?.optString("category")
                                    ?.takeIf { it.isNotEmpty() && it != "null" }?.let { " ($it)" } ?: "") +
                                ". Try rephrasing it, or switch to another model.",
                        )
                        !stopReason.isNullOrEmpty() && stopReason != "null" -> ChatChunk.Done(stopReason)
                        else -> null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractAnthropicText(json: String): String? {
        return try {
            val obj = JSONObject(json)
            if (obj.optString("type") == "content_block_delta") {
                val delta = obj.optJSONObject("delta") ?: return null
                // Handle both old "text" field and new "text_delta" type
                when {
                    delta.optString("type") == "text_delta" ->
                        delta.optString("text").takeIf { it.isNotEmpty() }
                    delta.has("text") ->
                        delta.optString("text").takeIf { it.isNotEmpty() }
                    else -> null
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clamps requested output tokens to the model's known ceiling (from [ModelCatalog]),
     * mirroring the OpenAI/Gemini clamp. Claude 4.x caps are well above our budget, so this
     * is defensive — null cap = no clamp.
     */
    private fun cappedMaxTokens(model: String, requested: Int): Int {
        val cap = ModelCatalog.model(provider, model)?.maxOutput ?: return requested
        return minOf(requested, cap)
    }

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

