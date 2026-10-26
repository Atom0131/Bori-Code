package com.bori.core.ai.model

import com.bori.core.security.AiProvider

/** Fill-In-Middle (FIM) completion request for inline suggestions. */
data class CompletionRequest(
    /** Text before the cursor. */
    val prefix: String,
    /** Text after the cursor. */
    val suffix: String = "",
    val model: String = "",
    /** When non-null, ModelRouter will try this provider before falling back to its priority list. */
    val preferredProvider: AiProvider? = null,
    val maxTokens: Int = 256,
    val temperature: Float = 0.2f,
    val stream: Boolean = true,
)

data class CompletionChunk(
    val text: String,
    val done: Boolean = false,
)
