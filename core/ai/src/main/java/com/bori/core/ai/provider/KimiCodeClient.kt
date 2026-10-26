package com.bori.core.ai.provider

import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kimi Code subscription client. Uses the key from the user's Kimi Code
 * membership page, billed against their subscription rather than per token.
 *
 * Endpoint is the OpenAI-compatible `https://api.kimi.com/coding/v1`; third-party
 * tools must always request the model `kimi-for-coding`.
 */
@Singleton
class KimiCodeClient @Inject constructor(
    keyVault: KeyVault,
) : OpenAiCompatClient(keyVault) {

    override val provider: AiProvider = AiProvider.KIMI_CODE
    override val baseUrl: String = "https://api.kimi.com"
    override val chatPath: String = "/coding/v1/chat/completions"
    override val modelsPath: String = "/coding/v1/models"

    override fun supportsToolUse(): Boolean = true

    override fun maxContextTokens(): Int = 256_000
}
