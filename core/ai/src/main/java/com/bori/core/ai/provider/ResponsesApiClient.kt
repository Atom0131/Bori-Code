package com.bori.core.ai.provider

import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatMessage
import com.bori.core.ai.model.ChatRequest
import com.bori.core.ai.model.CompletionChunk
import com.bori.core.ai.model.CompletionRequest
import com.bori.core.ai.model.MessageContent
import com.bori.core.ai.model.ModelCatalog
import com.bori.core.security.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull
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
 * Base client for OpenAI's Responses API (`/v1/responses`).
 *
 * Current OpenAI models only support tool calling through the Responses API —
 * Chat Completions either rejects tools or requires reasoning to be disabled —
 * so the OpenAI provider uses this client instead of [OpenAiCompatClient].
 *
 * Requests are stateless (`store: false`): the full history is sent each turn,
 * with tool calls and results as `function_call` / `function_call_output` items.
 *
 * Streaming events handled:
 *  - `response.output_text.delta`             → [ChatChunk.TextDelta]
 *  - `response.output_item.added` (function)  → [ChatChunk.ToolCallStart]
 *  - `response.function_call_arguments.delta` → [ChatChunk.ToolCallDelta]
 *  - `response.output_item.done` (function)   → [ChatChunk.ToolCallEnd]
 *  - `response.completed` / `.incomplete`     → [ChatChunk.Usage] then [ChatChunk.Done]
 *  - `response.failed` / `error`              → flow closes with an [IOException]
 */
abstract class ResponsesApiClient(
    private val keyVault: KeyVault,
) : AiProviderClient {

    /** Base URL without trailing slash, e.g. "https://api.openai.com" */
    abstract val baseUrl: String

    private val okClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Disabled — streaming response has no fixed end
            .addInterceptor(RedactingLoggingInterceptor())
            .build()
    }

    override fun supportsToolUse(): Boolean = true

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/models")
            .get()
            .header("Authorization", "Bearer $apiKey")
            .build()
        okClient.newCall(httpRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Provider error: HTTP ${response.code} $body")
            parseModelIds(body).filter(::isChatModel)
        }
    }

    /** /v1/models also lists embedding, image, audio and moderation models; keep the chat ones. */
    private fun isChatModel(id: String): Boolean =
        (id.startsWith("gpt-") || id.startsWith("chatgpt") || Regex("^o\\d").containsMatchIn(id)) &&
            NON_CHAT_MARKERS.none { it in id }

    private companion object {
        val NON_CHAT_MARKERS = listOf("audio", "realtime", "tts", "transcribe", "image", "embedding", "moderation", "search")
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    override fun chat(request: ChatRequest): Flow<ChatChunk> = callbackFlow {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)

        val body = buildResponsesBody(request).toString()
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/responses")
            .post(body)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()

        val call = okClient.newCall(httpRequest)
        call.enqueue(object : Callback {
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
                            val event = JSONObject(line.removePrefix("data: ").trim())
                            parseEvent(event).forEach { trySend(it) }
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

    private fun buildResponsesBody(request: ChatRequest): JSONObject {
        val instructions = request.messages
            .filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n\n") { it.textContent }

        val input = JSONArray()
        request.messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .forEach { msg -> serializeMessage(msg).forEach { input.put(it) } }

        return JSONObject().apply {
            put("model", request.model)
            put("input", input)
            if (instructions.isNotBlank()) put("instructions", instructions)
            put("max_output_tokens", cappedMaxTokens(request.model, request.maxTokens))
            put("stream", request.stream)
            put("store", false)
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    request.tools.forEach { tool ->
                        put(JSONObject().apply {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", JSONObject(tool.inputSchema))
                        })
                    }
                })
            }
        }
    }

    /**
     * Clamps the requested output tokens to the model's known ceiling (from
     * [ModelCatalog]) so a low-cap model can't 400 with "max_output_tokens is too large".
     */
    private fun cappedMaxTokens(model: String, requested: Int): Int {
        val cap = ModelCatalog.model(provider, model)?.maxOutput ?: return requested
        return minOf(requested, cap)
    }

    /** One [ChatMessage] can expand into several input items (text + function calls/outputs). */
    private fun serializeMessage(msg: ChatMessage): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        val text = msg.textContent
        val images = msg.content.filterIsInstance<MessageContent.Image>()
        if (images.isNotEmpty() && msg.role == ChatMessage.Role.USER) {
            items += JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    if (text.isNotEmpty()) put(JSONObject().put("type", "input_text").put("text", text))
                    images.forEach { img ->
                        put(JSONObject()
                            .put("type", "input_image")
                            .put("image_url", "data:${img.mediaType};base64,${img.data}"))
                    }
                })
            }
        } else if (text.isNotEmpty()) {
            items += JSONObject().apply {
                put("role", msg.role.name.lowercase())
                put("content", text)
            }
        }
        msg.content.forEach { item ->
            when (item) {
                is MessageContent.ToolUse -> items += JSONObject().apply {
                    put("type", "function_call")
                    put("call_id", item.id)
                    put("name", item.name)
                    put("arguments", item.inputJson)
                }
                is MessageContent.ToolResult -> items += JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", item.toolUseId)
                    put("output", item.content)
                }
                is MessageContent.Text, is MessageContent.Image -> { /* folded into the message above */ }
                is MessageContent.Reasoning -> { /* DeepSeek-only — not sent to OpenAI */ }
            }
        }
        return items
    }

    private fun parseEvent(event: JSONObject): List<ChatChunk> = listOfNotNull(parseSingleEvent(event)).let { chunks ->
        // Final events carry the token usage for the whole response; report it before Done
        val usage = event.optJSONObject("response")?.optJSONObject("usage")
        if (usage != null && chunks.singleOrNull() is ChatChunk.Done) {
            listOf(
                ChatChunk.Usage(
                    provider = provider,
                    promptTokens = usage.optInt("input_tokens", 0),
                    completionTokens = usage.optInt("output_tokens", 0),
                ),
            ) + chunks
        } else chunks
    }

    private fun parseSingleEvent(event: JSONObject): ChatChunk? {
        return when (event.optString("type")) {
            "response.output_text.delta" ->
                event.optString("delta").takeIf { it.isNotEmpty() }?.let { ChatChunk.TextDelta(it) }
            "response.output_item.added" -> {
                val item = event.optJSONObject("item") ?: return null
                if (item.optString("type") != "function_call") return null
                ChatChunk.ToolCallStart(id = item.optString("call_id"), name = item.optString("name"))
            }
            "response.function_call_arguments.delta" ->
                event.optString("delta").takeIf { it.isNotEmpty() }?.let { ChatChunk.ToolCallDelta(it) }
            "response.output_item.done" -> {
                val item = event.optJSONObject("item") ?: return null
                if (item.optString("type") != "function_call") return null
                ChatChunk.ToolCallEnd(item.optString("call_id"))
            }
            "response.completed" -> ChatChunk.Done("stop")
            "response.incomplete" -> ChatChunk.Done("max_tokens")
            "response.failed", "error" -> {
                val message = event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                    ?: event.optString("message").ifEmpty { event.toString() }
                throw IOException("Provider error: $message")
            }
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // Completion (FIM via a plain prompt through the same endpoint)
    // -------------------------------------------------------------------------

    override fun complete(request: CompletionRequest): Flow<CompletionChunk> {
        val prompt = if (request.suffix.isBlank()) {
            "Complete the following code. Output only the completion, no explanation:\n\n${request.prefix}"
        } else {
            "Complete the code between <PREFIX> and <SUFFIX>. Output only the inserted code, no explanation.\n\n" +
                "<PREFIX>${request.prefix}<SUFFIX>${request.suffix}"
        }
        val chatRequest = ChatRequest(
            messages = listOf(ChatMessage(ChatMessage.Role.USER, prompt)),
            model = request.model,
            maxTokens = request.maxTokens,
        )
        return chat(chatRequest).mapNotNull { chunk ->
            when (chunk) {
                is ChatChunk.TextDelta -> CompletionChunk(chunk.text)
                is ChatChunk.Done -> CompletionChunk("", done = true)
                else -> null
            }
        }
    }
}
