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
 * Google Gemini provider client.
 *
 * Gemini uses a completely different API format from OpenAI/Anthropic:
 *  - Endpoint:  generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent
 *  - Auth:      x-goog-api-key header
 *  - Messages:  "contents" array with "parts" (roles: "user" / "model")
 *  - System:    top-level "systemInstruction" field
 *  - Images:    "inline_data" with pure base64 (no "data:..." prefix)
 *  - Tools:     "functionDeclarations" in a "tools" array
 *  - Streaming: each "data:" SSE line is a complete JSON object
 */
@Singleton
class GeminiClient @Inject constructor(
    private val keyVault: KeyVault,
) : AiProviderClient {

    override val provider: AiProvider = AiProvider.GEMINI
    override fun supportsToolUse(): Boolean = true
    override fun maxContextTokens(): Int = 1_000_000

    private val BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    // Gemini's hard per-response output ceiling (2.5 and 3.x). Requests are clamped
    // to this so a larger app-side cap can never trigger an INVALID_ARGUMENT 400.
    private val MAX_OUTPUT_TOKENS = 65_536

    private val okClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .addInterceptor(RedactingLoggingInterceptor())
            .build()
    }

    // ── Models ───────────────────────────────────────────────────────────────

    /** Lists the models this key can chat with (`generateContent`), without the "models/" prefix. */
    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)
        val ids = mutableListOf<String>()
        var pageToken = ""
        do {
            val httpRequest = Request.Builder()
                .url("$BASE?pageSize=1000" + if (pageToken.isNotEmpty()) "&pageToken=$pageToken" else "")
                .get()
                .header("x-goog-api-key", apiKey)
                .build()
            val json = okClient.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(friendlyError(response.code, body, ""))
                JSONObject(body)
            }
            val models = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods")
                val canChat = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (canChat) ids += m.optString("name").removePrefix("models/")
            }
            pageToken = json.optString("nextPageToken")
        } while (pageToken.isNotEmpty())
        ids.filter(String::isNotEmpty).sorted()
    }

    // ── Chat ─────────────────────────────────────────────────────────────────

    override fun chat(request: ChatRequest): Flow<ChatChunk> = callbackFlow {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)

        val body = buildChatBody(request).toString()
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$BASE/${request.model}:streamGenerateContent?alt=sse")
            .post(body)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .build()

        val call = okClient.newCall(httpRequest)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    close(IOException(friendlyError(response.code, response.body?.string(), request.model)))
                    return
                }
                response.body?.source()?.let { source ->
                    try {
                        // Track whether the turn produced any visible output, so an empty
                        // MAX_TOKENS finish (budget consumed before any text/tool call) is
                        // surfaced instead of being a silent no-op for the agent loop.
                        var producedOutput = false
                        while (!source.exhausted() && isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data: ")) continue
                            val data = line.removePrefix("data: ").trim()
                            if (data.isEmpty()) continue
                            for (chunk in parseChunk(data)) {
                                if (chunk is ChatChunk.Done && chunk.stopReason == "MAX_TOKENS" && !producedOutput) {
                                    trySend(ChatChunk.TextDelta(
                                        "[The response hit the token limit before producing any output — " +
                                            "try a shorter request, or raise the response token limit.]",
                                    ))
                                }
                                if (chunk is ChatChunk.TextDelta || chunk is ChatChunk.ToolCallStart) {
                                    producedOutput = true
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

            override fun onFailure(call: Call, e: IOException) { close(e) }
        })

        awaitClose { call.cancel() }
    }

    // ── Completion (proxied through chat — Gemini has no native FIM endpoint) ─

    override fun complete(request: CompletionRequest): Flow<CompletionChunk> = callbackFlow {
        val apiKey = keyVault.retrieveKey(provider)
            ?: throw MissingApiKeyException(provider)

        val prompt = if (request.suffix.isBlank()) {
            "Complete the following code. Output only the completion:\n\n${request.prefix}"
        } else {
            "Complete the code between <prefix> and <suffix>. Output only the inserted code.\n\n" +
                "<prefix>${request.prefix}</prefix><suffix>${request.suffix}</suffix>"
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", minOf(request.maxTokens, MAX_OUTPUT_TOKENS))
                put("temperature", request.temperature.toDouble())
                thinkingConfig(request.model, isCompletion = true)?.let { put("thinkingConfig", it) }
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$BASE/${request.model}:streamGenerateContent?alt=sse")
            .post(body)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .build()

        val call = okClient.newCall(httpRequest)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    close(IOException(friendlyError(response.code, response.body?.string(), request.model)))
                    return
                }
                response.body?.source()?.let { source ->
                    try {
                        while (!source.exhausted() && isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data: ")) continue
                            val data = line.removePrefix("data: ").trim()
                            if (data.isEmpty()) continue
                            val text = runCatching {
                                JSONObject(data)
                                    .getJSONArray("candidates").getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts").getJSONObject(0)
                                    .optString("text")
                            }.getOrNull()
                            if (!text.isNullOrEmpty()) trySend(CompletionChunk(text))
                        }
                    } catch (e: Exception) {
                        close(e)
                        return
                    }
                }
                close()
            }

            override fun onFailure(call: Call, e: IOException) { close(e) }
        })

        awaitClose { call.cancel() }
    }

    // ── Thinking config ───────────────────────────────────────────────────────

    /**
     * Builds the `generationConfig.thinkingConfig` object for a request, or null to
     * omit it entirely.
     *
     * Why this exists: Gemini models do "thinking" that, left at the default
     * (dynamic), can consume the whole `maxOutputTokens` and return EMPTY text with
     * `finishReason: MAX_TOKENS` — which made inline completion blank on every
     * keystroke and made agent turns silently produce nothing.
     *
     * The two families use DIFFERENT, mutually-exclusive fields, and sending both
     * in one request is a hard 400 — so this returns at most one:
     *  - **2.5 family → `thinkingBudget`** (an int token budget):
     *      • 2.5 flash → 0 (thinking off; the coding agent wants the tokens for
     *        code + tool calls).
     *      • 2.5 flash-lite → omit: thinking is OFF by default and budget 0 is
     *        REJECTED (its minimum is 512), so sending nothing is the safe "off".
     *      • 2.5 pro → 128: pro cannot disable thinking; 128 is the documented min.
     *  - **3.x family → `thinkingLevel`** (low/medium/high):
     *      • chat → omit: use the model's sensible default (pro = high), which is
     *        the whole point of selecting a Pro/thinking model.
     *      • completion → "low": minimise thinking so the small budget goes to the
     *        actual completion.
     *  - anything else → omit.
     */
    private fun thinkingConfig(model: String, isCompletion: Boolean): JSONObject? = when {
        model.contains("2.5") -> when {
            model.contains("flash-lite") -> null
            model.contains("pro")        -> JSONObject().put("thinkingBudget", 128)
            else                         -> JSONObject().put("thinkingBudget", 0)
        }
        model.startsWith("gemini-3") ->
            // For inline completion, minimise thinking so the tiny (~256 token) budget goes to
            // the actual completion — "minimal" is the docs' true no-thinking level (mirrors the
            // 2.5 thinkingBudget=0 path). Pro only supports low/medium/high (400s on "minimal"),
            // so fall back to "low" there. Chat omits the field → model default (e.g. pro = high).
            if (isCompletion)
                JSONObject().put("thinkingLevel", if (model.contains("pro")) "low" else "minimal")
            else null
        else -> null
    }

    // ── Request serialization ─────────────────────────────────────────────────

    private fun buildChatBody(request: ChatRequest): JSONObject {
        val systemText = request.messages
            .firstOrNull { it.role == ChatMessage.Role.SYSTEM }
            ?.textContent

        val conversationMessages = request.messages
            .filter { it.role != ChatMessage.Role.SYSTEM }

        return JSONObject().apply {
            if (!systemText.isNullOrBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemText) })
                    })
                })
            }
            put("contents", JSONArray().apply {
                val toolUses = conversationMessages
                    .flatMap { it.content.filterIsInstance<MessageContent.ToolUse>() }
                    .associateBy { it.id }
                conversationMessages.forEach { msg -> put(serializeMessage(msg, toolUses)) }
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", minOf(request.maxTokens, MAX_OUTPUT_TOKENS))
                put("temperature", request.temperature.toDouble())
                thinkingConfig(request.model, isCompletion = false)?.let { put("thinkingConfig", it) }
            })
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("functionDeclarations", JSONArray().apply {
                            request.tools.forEach { tool ->
                                put(JSONObject().apply {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", JSONObject(tool.inputSchema))
                                })
                            }
                        })
                    })
                })
            }
        }
    }

    /**
     * @param toolUses every ToolUse in the conversation by id, so a ToolResult can recover
     *   its function name (functionResponse.name) and Gemini's own call id.
     */
    private fun serializeMessage(msg: ChatMessage, toolUses: Map<String, MessageContent.ToolUse>): JSONObject {
        // ToolResults → one user turn with a functionResponse part per result.
        val toolResults = msg.content.filterIsInstance<MessageContent.ToolResult>()
        if (toolResults.isNotEmpty()) {
            return JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    toolResults.forEach { toolResult ->
                        put(JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", toolUses[toolResult.toolUseId]?.name ?: toolResult.toolUseId)
                                geminiCallId(toolResult.toolUseId)?.let { put("id", it) }
                                put("response", JSONObject().apply {
                                    put("output", toolResult.content)
                                    if (toolResult.isError) put("error", true)
                                })
                            })
                        })
                    }
                })
            }
        }

        // Gemini roles: "user" or "model"
        val geminiRole = if (msg.role == ChatMessage.Role.ASSISTANT) "model" else "user"

        return JSONObject().apply {
            put("role", geminiRole)
            put("parts", JSONArray().apply {
                msg.content.forEach { item ->
                    when (item) {
                        is MessageContent.Text -> put(JSONObject().apply {
                            put("text", item.text)
                        })
                        is MessageContent.Image -> put(JSONObject().apply {
                            // Gemini requires pure base64 — no "data:mime;base64," prefix
                            put("inline_data", JSONObject().apply {
                                put("mime_type", item.mediaType)
                                put("data", item.data)
                            })
                        })
                        is MessageContent.ToolUse -> put(JSONObject().apply {
                            put("functionCall", JSONObject().apply {
                                put("name", item.name)
                                geminiCallId(item.id)?.let { put("id", it) }
                                put("args", JSONObject(item.inputJson))
                            })
                            // Gemini 3 validates that its signatures come back on function calls
                            item.signature?.let { put("thoughtSignature", it) }
                        })
                        is MessageContent.ToolResult -> { /* handled above as a separate message */ }
                        is MessageContent.Reasoning -> { /* DeepSeek-only — not sent to Gemini */ }
                    }
                }
            })
        }
    }

    // ── Error mapping ─────────────────────────────────────────────────────────

    /**
     * Turns Google's error response into a clear, actionable message.
     *
     * Gemini errors look like `{"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
     * "message":"..."}}`. The most common surprise is a 429 on a 3.x/Pro model: these
     * have NO API free tier, so a request fails immediately even on a brand-new key —
     * which reads like "out of credits". The 429 branch explains the paid /
     * Pro-Ultra-key path; the 2.5 branch covers a genuinely exhausted free quota.
     */
    private fun friendlyError(httpCode: Int, body: String?, model: String): String {
        val (status, message) = runCatching {
            val err = JSONObject(body ?: "").optJSONObject("error")
            (err?.optString("status") ?: "") to (err?.optString("message") ?: "")
        }.getOrDefault("" to "")

        return when {
            httpCode == 429 || status == "RESOURCE_EXHAUSTED" -> {
                // Plain-language lead so the user understands the *kind* of error before the fix.
                val plain = "Gemini is temporarily refusing requests because a usage limit was hit " +
                    "(error 429 = \"too many requests\"). "
                val onFreeTierFriendly = ModelCatalog.model(provider, model)?.freeTierQuota ?: true
                val advice = if (onFreeTierFriendly) {
                    // Already on a free-tier-friendly model, yet still 429 — the project's free
                    // quota is exhausted/zeroed for now (or this project never had any).
                    "Your Google AI Studio project's quota for \"$model\" is used up for now " +
                        "(free-tier limits reset daily, ~midnight Pacific). Quota is tied to the PROJECT, " +
                        "not the key — generating a new API key won't reset it. Note this is also a separate " +
                        "pool from Gemini CLI / Antigravity (those sign in with OAuth), so the same key being " +
                        "limited here while the CLI still works is expected. To raise it, link a billing " +
                        "account to the project at aistudio.google.com."
                } else {
                    // A Pro model — these have NO API free tier; they need a paid key
                    // or an AI-Studio key issued under a Google AI Pro/Ultra plan.
                    "\"$model\" has no free API tier, so it needs a paid key. The easiest path: open " +
                        "aistudio.google.com signed in with your Google AI Pro or Ultra subscription " +
                        "(AI Studio is included in those plans) and generate an API key there, then paste " +
                        "it in Settings — or link a billing account to your project. Quota is per-PROJECT, " +
                        "so just regenerating the current key won't help. To keep coding for free meanwhile, " +
                        "switch to ${ModelCatalog.defaultChatModel(provider)} in the model picker."
                }
                plain + advice + (if (message.isNotBlank()) " ($message)" else "")
            }
            httpCode == 404 || status == "NOT_FOUND" ->
                "Gemini couldn't find that model (error 404 = \"not found\"). \"$model\" isn't available " +
                    "to your API key — pick a different model in the picker." +
                    (if (message.isNotBlank()) " ($message)" else "")
            httpCode == 403 || status == "PERMISSION_DENIED" ->
                "Gemini refused access to your key (error 403 = \"forbidden\"). Check your API key and that " +
                    "the Generative Language API is enabled for it." +
                    (if (message.isNotBlank()) " ($message)" else "")
            httpCode == 400 ->
                "Gemini rejected the request as invalid (error 400). ${message.ifBlank { "INVALID_ARGUMENT" }}"
            else ->
                "Gemini error (HTTP $httpCode). ${message.ifBlank { (body ?: "").take(300).ifBlank { "no details" } }}"
        }
    }

    // ── Streaming parser ──────────────────────────────────────────────────────

    /**
     * Parses a single Gemini SSE data line into zero or more [ChatChunk]s.
     *
     * Each data line is a complete JSON object. For tool calls, Gemini delivers
     * the entire function call in one event (not incrementally), so we emit
     * ToolCallStart + ToolCallDelta + ToolCallEnd in a single batch.
     *
     * Every functionCall is emitted; the agent loop answers all of a turn's calls
     * and sends the results back together. The tool call ID is Gemini's own `id`
     * when it sends one, otherwise a generated [LOCAL_ID_PREFIX] id, so two calls
     * to the same function stay distinct. Each call's `thoughtSignature` is kept
     * so it can be echoed back.
     */
    private fun parseChunk(json: String): List<ChatChunk> {
        return try {
            val obj = JSONObject(json)
            val candidate = obj.optJSONArray("candidates")?.optJSONObject(0)
            val finishReason = candidate?.optString("finishReason").orEmpty()
            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
            val chunks = mutableListOf<ChatChunk>()

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val fc = part.optJSONObject("functionCall")
                    if (fc != null) {
                        // Function call — Gemini delivers complete args in one shot.
                        val name = fc.optString("name")
                        val id = fc.optString("id").ifEmpty { LOCAL_ID_PREFIX + java.util.UUID.randomUUID() }
                        val argsJson = fc.optJSONObject("args")?.toString() ?: "{}"
                        val signature = part.optString("thoughtSignature").ifEmpty { null }
                        chunks += ChatChunk.ToolCallStart(id = id, name = name, signature = signature)
                        chunks += ChatChunk.ToolCallDelta(argsJson)
                        chunks += ChatChunk.ToolCallEnd(id = id)
                    } else if (!part.optBoolean("thought")) {
                        val text = part.optString("text")
                        if (text.isNotEmpty()) chunks += ChatChunk.TextDelta(text)
                    }
                }
            }

            // Usage + terminal arrive on the final chunk (finishReason present) — emit once.
            // Gemini's usageMetadata is cumulative and repeats on every streamed chunk, so we
            // gate the single Usage emission on finishReason to avoid double-counting tokens.
            if (finishReason.isNotEmpty()) {
                obj.optJSONObject("usageMetadata")?.let { u ->
                    chunks += ChatChunk.Usage(
                        provider = provider,
                        promptTokens = u.optInt("promptTokenCount", 0),
                        completionTokens = u.optInt("candidatesTokenCount", 0),
                    )
                }
                if (finishReason == "STOP" || finishReason == "MAX_TOKENS") {
                    chunks += ChatChunk.Done(finishReason)
                }
            }

            chunks
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Gemini's own call id, or null for an id Bori Code generated. */
    private fun geminiCallId(toolUseId: String): String? =
        toolUseId.takeUnless { it.startsWith(LOCAL_ID_PREFIX) }

    private companion object {
        /** Prefix for call ids Bori Code makes up when Gemini doesn't send one. */
        const val LOCAL_ID_PREFIX = "bori-call-"
    }
}
