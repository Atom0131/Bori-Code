package com.bori.core.ai.provider

import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatMessage
import com.bori.core.ai.model.ChatRequest
import com.bori.core.ai.model.MessageContent
import com.bori.core.ai.model.CompletionChunk
import com.bori.core.ai.model.CompletionRequest
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

/**
 * Abstract base for OpenAI-compatible APIs.
 *
 * DeepSeek, OpenRouter and Kimi all share the same chat-completions endpoint
 * shape. Subclasses supply [baseUrl], [provider] and the auth header name, and
 * override [chatPath]/[modelsPath] when the provider uses a different prefix.
 * (OpenAI itself uses [ResponsesApiClient]; Gemini has its own native client.)
 *
 * SSE streaming is handled by reading the response body line-by-line inside
 * a [callbackFlow]. Cancelling the flow cancels the in-flight OkHttp [Call].
 */
abstract class OpenAiCompatClient(
    private val keyVault: KeyVault,
) : AiProviderClient {

    /** Base URL without trailing slash, e.g. "https://api.deepseek.com" */
    abstract val baseUrl: String

    /** Header name used to pass the API key (usually "Authorization"). */
    open val authHeaderName: String = "Authorization"

    /** Format the raw key into the header value, e.g. "Bearer sk-..." */
    open fun formatAuthValue(key: String): String = "Bearer $key"

    /** Chat endpoint path appended to [baseUrl]. Kimi Code uses a non-/v1 prefix. */
    open val chatPath: String = "/v1/chat/completions"

    /** Model-list endpoint path appended to [baseUrl]. */
    open val modelsPath: String = "/v1/models"

    /**
     * JSON field used to cap output tokens. OpenAI deprecated `max_tokens` in favour
     * of `max_completion_tokens` for current/o-series models (reasoning models reject
     * the old field), so [OpenAiClient] overrides this. DeepSeek and OpenRouter still
     * expect `max_tokens`, so they keep the default.
     */
    protected open val maxTokensParam: String = "max_tokens"

    /** Extra headers some providers require (e.g. OpenRouter's HTTP-Referer / X-Title). */
    protected open fun extraHeaders(): Map<String, String> = emptyMap()

    /**
     * True for reasoning models (OpenAI o-series) that reject `temperature` and the
     * `system` role. Overridden by [OpenAiClient]; default false for DeepSeek/OpenRouter,
     * which carry their own thinking mechanisms.
     */
    protected open fun isReasoningModel(model: String): Boolean = false

    /**
     * Clamps the requested output tokens to the model's known ceiling (from
     * [ModelCatalog]) so a low-cap model can't 400 with "max_token is too large".
     */
    private fun cappedMaxTokens(model: String, requested: Int): Int {
        val cap = ModelCatalog.model(provider, model)?.maxOutput ?: return requested
        return minOf(requested, cap)
    }

    private val okClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Disabled — streaming response has no fixed end
            .addInterceptor(RedactingLoggingInterceptor())
            .build()
    }

    // -------------------------------------------------------------------------
    // Models
    // -------------------------------------------------------------------------

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)
        val httpRequest = Request.Builder()
            .url("$baseUrl$modelsPath")
            .get()
            .header(authHeaderName, formatAuthValue(apiKey))
            .apply { extraHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        okClient.newCall(httpRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Provider error: HTTP ${response.code} $body")
            parseModelIds(body)
        }
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    override fun chat(request: ChatRequest): Flow<ChatChunk> = callbackFlow {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)

        val body = buildChatBody(request).toString()
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$baseUrl$chatPath")
            .post(body)
            .header(authHeaderName, formatAuthValue(apiKey))
            .header("Content-Type", "application/json")
            .apply { extraHeaders().forEach { (k, v) -> header(k, v) } }
            .build()

        val call = okClient.newCall(httpRequest)
        call.enqueue(object : Callback {
            private var currentToolId: String? = null

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "HTTP ${response.code}"
                    close(IOException("Provider error: $errorBody"))
                    return
                }
                response.body?.source()?.let { source ->
                    try {
                        while (!source.exhausted() && isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data: ")) continue
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") {
                                currentToolId?.let { trySend(ChatChunk.ToolCallEnd(it)) }
                                currentToolId = null
                                trySend(ChatChunk.Done("stop"))
                                break
                            }
                            parseChatChunk(data, currentToolId).forEach { chunk ->
                                when (chunk) {
                                    is ChatChunk.ToolCallStart -> currentToolId = chunk.id
                                    is ChatChunk.ToolCallEnd  -> currentToolId = null
                                    else -> {}
                                }
                                trySend(chunk)
                            }
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

    private fun buildChatBody(request: ChatRequest): JSONObject {
        val messages = JSONArray().apply {
            request.messages.forEach { msg -> serializeMessage(msg, request.model).forEach { put(it) } }
        }
        val reasoning = isReasoningModel(request.model)
        return JSONObject().apply {
            put("model", request.model)
            put("messages", messages)
            put(maxTokensParam, cappedMaxTokens(request.model, request.maxTokens))
            // o-series reasoning models reject `temperature` (only the default is allowed).
            if (!reasoning) put("temperature", request.temperature.toDouble())
            put("stream", request.stream)
            // Ask the provider to include a final token-usage chunk (OpenAI/DeepSeek/OpenRouter).
            if (request.stream) {
                put("stream_options", JSONObject().put("include_usage", true))
            }
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    request.tools.forEach { tool ->
                        put(JSONObject().apply {
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", JSONObject(tool.inputSchema))
                            })
                        })
                    }
                })
            }
        }
    }

    /** One [ChatMessage] can expand into several API messages (one "tool" message per result). */
    private fun serializeMessage(msg: ChatMessage, model: String = ""): List<JSONObject> {
        // ToolResult → one role "tool" message per result, regardless of ChatMessage.Role
        val toolResults = msg.content.filterIsInstance<MessageContent.ToolResult>()
        if (toolResults.isNotEmpty()) {
            return toolResults.map { toolResult ->
                JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", toolResult.toolUseId)
                    put("content", toolResult.content)
                }
            }
        }
        // ToolUse → assistant message with "tool_calls" array
        val toolUses = msg.content.filterIsInstance<MessageContent.ToolUse>()
        val text = msg.textContent
        val imageBlocks = msg.content.filterIsInstance<MessageContent.Image>()
        val isClaudeModel = model.startsWith("claude", ignoreCase = true)
        val isDeepSeekProvider = provider == AiProvider.DEEPSEEK
        // o-series reasoning models don't accept the `system` role — use `developer` instead.
        val role = if (msg.role == ChatMessage.Role.SYSTEM && isReasoningModel(model)) "developer"
                   else msg.role.name.lowercase()
        return listOf(JSONObject().apply {
            put("role", role)
            if (imageBlocks.isEmpty()) {
                put("content", if (text.isNotEmpty()) text else JSONObject.NULL)
            } else {
                put("content", JSONArray().apply {
                    when {
                        isClaudeModel -> {
                            // Anthropic format — Claude models via OpenRouter
                            if (text.isNotEmpty()) put(JSONObject().apply { put("type", "text"); put("text", text) })
                            imageBlocks.forEach { img ->
                                put(JSONObject().apply {
                                    put("type", "image")
                                    put("source", JSONObject().apply {
                                        put("type", "base64")
                                        put("media_type", img.mediaType)
                                        put("data", img.data)
                                    })
                                })
                            }
                        }
                        isDeepSeekProvider -> {
                            // DeepSeek has no vision — strip images, keep text
                            val note = "[Image attachment not shown — ${model.ifEmpty { "deepseek" }} does not support vision]"
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "${if (text.isNotEmpty()) text else "(image attachment)"}\n$note")
                            })
                        }
                        else -> {
                            // OpenAI, OpenRouter (non-Claude), and any future OpenAI-compat provider
                            if (text.isNotEmpty()) put(JSONObject().apply { put("type", "text"); put("text", text) })
                            imageBlocks.forEach { img ->
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:${img.mediaType};base64,${img.data}")
                                    })
                                })
                            }
                        }
                    }
                })
            }
            if (toolUses.isNotEmpty()) {
                put("tool_calls", JSONArray().apply {
                    toolUses.forEach { tu ->
                        put(JSONObject().apply {
                            put("id", tu.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tu.name)
                                put("arguments", tu.inputJson)
                            })
                        })
                    }
                })
            }
            // DeepSeek thinking mode requires reasoning_content to be echoed back verbatim
            if (isDeepSeekProvider) {
                val reasoning = msg.content.filterIsInstance<MessageContent.Reasoning>().firstOrNull()
                if (reasoning != null) put("reasoning_content", reasoning.text)
            }
        })
    }

    private fun parseChatChunk(json: String, currentToolId: String?): List<ChatChunk> {
        return try {
            val obj = JSONObject(json)
            // Final usage chunk (from stream_options.include_usage) — choices is empty here.
            val usageObj = obj.optJSONObject("usage")
            val choicesArr = obj.optJSONArray("choices")
            if (usageObj != null && usageObj.has("prompt_tokens") &&
                (choicesArr == null || choicesArr.length() == 0)) {
                return listOf(ChatChunk.Usage(
                    provider = provider,
                    promptTokens = usageObj.optInt("prompt_tokens", 0),
                    completionTokens = usageObj.optInt("completion_tokens", 0),
                ))
            }
            val choice = choicesArr?.optJSONObject(0) ?: return emptyList()
            val finishReason = if (choice.isNull("finish_reason")) "" else choice.optString("finish_reason", "")
            val out = mutableListOf<ChatChunk>()
            var openToolId = currentToolId

            // A single line can carry deltas AND finish_reason (Gemini, Kimi send the last
            // text or tool arguments together with it), so parse the delta first.
            choice.optJSONObject("delta")?.let { delta ->
                // Streaming tool_calls fragment. A single delta may carry several tool_calls,
                // and the opening fragment can bundle id+name AND the first slice of arguments —
                // emit a Start *and* a Delta in that case so no argument bytes are dropped.
                val toolCalls = delta.optJSONArray("tool_calls")
                if (toolCalls != null) {
                    for (i in 0 until toolCalls.length()) {
                        val tc = toolCalls.getJSONObject(i)
                        val id = tc.optString("id")
                        val function = tc.optJSONObject("function")
                        val name = function?.optString("name") ?: ""
                        val args = function?.optString("arguments") ?: ""
                        if (id.isNotEmpty() && name.isNotEmpty() && id != openToolId) {
                            openToolId?.let { out += ChatChunk.ToolCallEnd(it) }
                            out += ChatChunk.ToolCallStart(id, name)
                            openToolId = id
                        }
                        if (args.isNotEmpty()) out += ChatChunk.ToolCallDelta(args)
                    }
                }

                // DeepSeek thinking-mode: reasoning_content delta (must be stored and echoed back)
                val reasoning = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content", "")
                if (reasoning.isNotEmpty()) out += ChatChunk.ReasoningDelta(reasoning)

                // Regular text delta
                val content = if (delta.isNull("content")) "" else delta.optString("content", "")
                if (content.isNotEmpty()) out += ChatChunk.TextDelta(content)
            }

            // Any finish reason closes an open tool call; some providers send "stop"
            // rather than "tool_calls" after one.
            if (finishReason.isNotEmpty()) {
                out += if (openToolId != null) ChatChunk.ToolCallEnd(openToolId)
                       else ChatChunk.Done(finishReason)
            }
            out
        } catch (e: Exception) {
            android.util.Log.w(TAG, "skipping malformed SSE chunk: $json", e)
            emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // Completion (FIM via prompt injection for providers without native FIM)
    // -------------------------------------------------------------------------

    override fun complete(request: CompletionRequest): Flow<CompletionChunk> = callbackFlow {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)

        // Wrap FIM in a chat message if the provider lacks a native /completions FIM endpoint
        val fimPrompt = buildFimPrompt(request.prefix, request.suffix)
        val chatBody = JSONObject().apply {
            put("model", request.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", fimPrompt)
                })
            })
            put(maxTokensParam, cappedMaxTokens(request.model, request.maxTokens))
            put("temperature", request.temperature.toDouble())
            put("stream", request.stream)
        }.toString().toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$baseUrl$chatPath")
            .post(chatBody)
            .header(authHeaderName, formatAuthValue(apiKey))
            .header("Content-Type", "application/json")
            .apply { extraHeaders().forEach { (k, v) -> header(k, v) } }
            .build()

        val call = okClient.newCall(httpRequest)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    close(IOException("Provider error: HTTP ${response.code}"))
                    return
                }
                response.body?.source()?.let { source ->
                    try {
                        while (!source.exhausted() && isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data: ")) continue
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") {
                                trySend(CompletionChunk("", done = true))
                                break
                            }
                            parseCompletionChunk(data)?.let { trySend(it) }
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

    private fun parseCompletionChunk(json: String): CompletionChunk? {
        return try {
            val obj = JSONObject(json)
            val choice = obj.getJSONArray("choices").getJSONObject(0)
            val delta = choice.optJSONObject("delta") ?: return null
            val content = if (delta.isNull("content")) "" else delta.optString("content", "")
            if (content.isEmpty()) null else CompletionChunk(content)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "skipping malformed completion SSE chunk: $json", e)
            null
        }
    }

    /**
     * Formats a FIM request as a structured chat prompt.
     * DeepSeek-Coder supports native `<｜fim▁begin｜>` tokens, but this chat
     * fallback works for all OpenAI-compat providers.
     */
    private fun buildFimPrompt(prefix: String, suffix: String): String {
        return if (suffix.isBlank()) {
            "Complete the following code. Output only the completion, no explanation:\n\n$prefix"
        } else {
            """
            Complete the code between <PREFIX> and <SUFFIX>. Output only the inserted code, no explanation.

            <PREFIX>$prefix<SUFFIX>$suffix
            """.trimIndent()
        }
    }

    /**
     * Performs a blocking authenticated GET and parses the JSON body. Used by subclasses that
     * expose an account-balance endpoint. Returns null on missing key or non-2xx response.
     * Call from a background dispatcher.
     */
    protected fun authedGetJson(url: String): JSONObject? {
        val apiKey = keyVault.retrieveKey(provider) ?: return null
        val req = Request.Builder()
            .url(url)
            .header(authHeaderName, formatAuthValue(apiKey))
            .get()
            .build()
        return try {
            okClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "balance fetch failed for $provider", e)
            null
        }
    }

    private companion object {
        const val TAG = "OpenAiCompatClient"
    }
}
