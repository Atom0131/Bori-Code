package com.bori.core.ai.provider

import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatRequest
import com.bori.core.ai.model.CompletionChunk
import com.bori.core.ai.model.CompletionRequest
import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A model server the user runs themselves — Ollama, LM Studio, llama.cpp — usually on
 * their PC, reached over Tailscale or the local network. All of them speak the
 * OpenAI-compatible `/v1/chat/completions` and `/v1/models` endpoints.
 *
 * The provider's "key" slot in [KeyVault] holds the server URL instead of an API key
 * (e.g. `http://100.64.0.1:11434`), so "configured" still means `keyVault.hasKey()`.
 * There is no fixed model list: the user picks from the server's own list via
 * Settings → "Refresh models".
 */
@Singleton
class LocalServerClient @Inject constructor(
    private val keyVault: KeyVault,
) : OpenAiCompatClient(keyVault) {

    override val provider: AiProvider = AiProvider.LOCAL_SERVER

    override val baseUrl: String
        get() = normalizeServerUrl(keyVault.retrieveKey(provider).orEmpty())

    // The stored value is a URL, not a secret; local servers ignore the header anyway
    override fun formatAuthValue(key: String): String = "Bearer local"

    override fun supportsToolUse(): Boolean = true

    // Matches the OLLAMA_CONTEXT_LENGTH the setup guide recommends; the server enforces its own limit
    override fun maxContextTokens(): Int = 32_768

    override fun chat(request: ChatRequest): Flow<ChatChunk> =
        if (request.model.isBlank()) flow { emit(ChatChunk.Error(NO_MODEL)) } else super.chat(request)

    override fun complete(request: CompletionRequest): Flow<CompletionChunk> =
        if (request.model.isBlank()) flow { } else super.complete(request)

    companion object {
        private const val NO_MODEL =
            "No local model picked. Open Settings, choose \"Local server\" and tap Refresh models."

        /** Trims a trailing slash and a pasted `/v1`, and assumes http:// when no scheme is given. */
        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/').removeSuffix("/v1")
            return if ("://" in trimmed) trimmed else "http://$trimmed"
        }
    }
}
