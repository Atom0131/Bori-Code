package com.bori.core.ai.provider

import com.bori.core.ai.model.ProviderBalance
import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepSeek AI provider client.
 *
 * DeepSeek uses the OpenAI-compatible API format, so this class only needs to
 * supply the correct [baseUrl]. The shared SSE streaming logic lives in
 * [OpenAiCompatClient].
 *
 * Models:
 *  - Chat:        deepseek-v4-pro     (general purpose, strong coding)
 *               deepseek-v4-flash   (fast + cheap, thinking/non-thinking modes)
 *  - Completion:  deepseek-v4-flash   (deepseek-coder alias retires 2026-07-24)
 */
@Singleton
class DeepSeekClient @Inject constructor(
    keyVault: KeyVault,
) : OpenAiCompatClient(keyVault) {

    override val provider: AiProvider = AiProvider.DEEPSEEK
    override val baseUrl: String = "https://api.deepseek.com"

    override fun supportsToolUse(): Boolean = true

    override fun maxContextTokens(): Int = 128_000

    /** DeepSeek exposes the remaining balance at /user/balance. */
    override suspend fun fetchBalance(): ProviderBalance? = withContext(Dispatchers.IO) {
        val json = authedGetJson("$baseUrl/user/balance") ?: return@withContext null
        val info = json.optJSONArray("balance_infos")?.optJSONObject(0) ?: return@withContext null
        // Field mapping: total_balance -> limit (remaining funds); DeepSeek reports no "used" figure.
        val total = info.optString("total_balance", "").toDoubleOrNull()
        ProviderBalance(
            label = "Balance",
            used = null,
            limit = total,
            currency = if (info.optString("currency") == "CNY") "¥" else "$",
        )
    }
}
