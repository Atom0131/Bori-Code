package com.bori.core.ai.model

/**
 * Live account balance / credit information fetched from a provider that exposes it
 * (e.g. OpenRouter credits, DeepSeek balance). Providers without a quota API return null.
 *
 * @param label   short noun for the figure, e.g. "Credits" or "Balance".
 * @param used    amount already consumed, when the provider reports it (else null).
 * @param limit   total credit / granted amount, when the provider reports it (else null).
 * @param currency display currency symbol.
 */
data class ProviderBalance(
    val label: String,
    val used: Double? = null,
    val limit: Double? = null,
    val currency: String = "$",
)
