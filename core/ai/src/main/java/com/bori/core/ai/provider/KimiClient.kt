package com.bori.core.ai.provider

import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kimi (Moonshot AI) pay-as-you-go API client. OpenAI-compatible.
 *
 * Models: kimi-k3 (1M context), kimi-k2.7-code, kimi-k2.7-code-highspeed, kimi-k2.6 (256k).
 */
@Singleton
class KimiClient @Inject constructor(
    keyVault: KeyVault,
) : OpenAiCompatClient(keyVault) {

    override val provider: AiProvider = AiProvider.KIMI
    override val baseUrl: String = "https://api.moonshot.ai"

    override fun supportsToolUse(): Boolean = true

    override fun maxContextTokens(): Int = 256_000
}
