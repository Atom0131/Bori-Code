package com.bori.core.ai.provider

import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI provider client (API key billing).
 *
 * Uses the Responses API because current OpenAI models only support tool
 * calling there; see [ResponsesApiClient].
 */
@Singleton
class OpenAiClient @Inject constructor(
    keyVault: KeyVault,
) : ResponsesApiClient(keyVault) {

    override val provider: AiProvider = AiProvider.OPENAI
    override val baseUrl: String = "https://api.openai.com"

    override fun maxContextTokens(): Int = 1_000_000 // GPT-6 family: 1.05M window
}
