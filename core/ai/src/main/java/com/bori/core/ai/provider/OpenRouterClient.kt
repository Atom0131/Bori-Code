package com.bori.core.ai.provider

import com.bori.core.ai.model.ProviderBalance
import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenRouter provider client — gives access to many models (Claude, Gemini,
 * Llama, Mistral, etc.) through a single OpenAI-compatible endpoint.
 *
 * OpenRouter requires an extra `HTTP-Referer` header for app identification.
 */
@Singleton
class OpenRouterClient @Inject constructor(
    keyVault: KeyVault,
) : OpenAiCompatClient(keyVault) {

    override val provider: AiProvider = AiProvider.OPENROUTER
    override val baseUrl: String = "https://openrouter.ai/api"

    // OpenRouter uses this for app attribution. HTTP-Referer is left out: there is no public site.
    override fun extraHeaders(): Map<String, String> = mapOf("X-Title" to "Bori - Code")

    override fun supportsToolUse(): Boolean = true

    // Context varies by the underlying model; use a conservative default.
    override fun maxContextTokens(): Int = 128_000

    /** OpenRouter exposes total credits granted and used at /api/v1/credits. */
    override suspend fun fetchBalance(): ProviderBalance? = withContext(Dispatchers.IO) {
        val data = authedGetJson("$baseUrl/v1/credits")?.optJSONObject("data") ?: return@withContext null
        // Field mapping: total_credits -> limit (granted), total_usage -> used (consumed).
        val limit = data.optDouble("total_credits", Double.NaN)
        val used = data.optDouble("total_usage", Double.NaN)
        ProviderBalance(
            label = "Credits",
            used = used.takeUnless { it.isNaN() },
            limit = limit.takeUnless { it.isNaN() },
        )
    }
}
