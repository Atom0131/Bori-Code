package com.bori.core.ai.provider

import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatRequest
import com.bori.core.ai.model.CompletionChunk
import com.bori.core.ai.model.CompletionRequest
import com.bori.core.ai.model.ProviderBalance
import com.bori.core.security.AiProvider
import kotlinx.coroutines.flow.Flow

/**
 * Common interface for all AI provider clients.
 *
 * All implementations stream responses via [Flow] so the UI can
 * display tokens incrementally as they arrive.
 */
interface AiProviderClient {
    val provider: AiProvider

    /**
     * Send a chat request and receive streaming text chunks.
     * The flow completes when the model emits [ChatChunk.Done] or on error.
     */
    fun chat(request: ChatRequest): Flow<ChatChunk>

    /**
     * Send a Fill-In-Middle completion request for inline code suggestions.
     * Not all providers support true FIM; implementations may fall back to
     * a prompt-formatted completion.
     */
    fun complete(request: CompletionRequest): Flow<CompletionChunk>

    /** Whether this provider/model supports tool-use / function calling. */
    fun supportsToolUse(): Boolean = false

    /** Maximum context tokens for the currently configured model. */
    fun maxContextTokens(): Int

    /**
     * Fetches live account balance / credit info if the provider exposes an endpoint for it.
     * Returns null when unsupported or unavailable. Implementations perform blocking IO — call
     * from a background dispatcher.
     */
    suspend fun fetchBalance(): ProviderBalance? = null

    /**
     * Fetches the model IDs this provider currently serves for the saved key.
     * Providers without a models endpoint return an empty list.
     */
    suspend fun listModels(): List<String> = emptyList()
}

/** Parses an OpenAI/Anthropic-style `{"data":[{"id":...}]}` model list. */
internal fun parseModelIds(json: String): List<String> {
    val data = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
    return (0 until data.length())
        .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotEmpty) }
        .sorted()
}

/** Thrown when no API key is configured for a given provider. */
class MissingApiKeyException(provider: AiProvider) :
    Exception("No API key configured for ${provider.displayName}. Add your key in Settings.")
