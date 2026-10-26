package com.bori.core.ai.model

import com.bori.core.ai.local.LocalModel
import com.bori.core.security.AiProvider

/**
 * Single source of truth for the model IDs each provider offers and which one is
 * the default for chat and inline completion.
 *
 * These lists used to be duplicated across [com.bori.core.ai.router.ModelRouter],
 * `ChatViewModel`, `SettingsScreen` and `SettingsViewModel`, and had drifted out of
 * sync. The most damaging drift was Gemini: the pickers listed zero-free-quota
 * Gemini 3.x models *first*, and the picker auto-selects the first entry when a
 * provider is chosen — so a brand-new free-tier key would send `gemini-3.5-flash`
 * and get an immediate `429 RESOURCE_EXHAUSTED`, which reads to the user as
 * "out of credits the moment I use it".
 *
 * Ordering rule: the first entry in each list is what a freshly-opened picker
 * selects, and the default equals that first entry.
 *
 * IDs and free-tier flags were last checked against each provider's official
 * docs on 2026-10-12. Gemini's default is `gemini-3.8-flash`, which has a real
 * free tier (the June 3.x zero-quota problem no longer applies to it); Pro
 * models stay flagged `freeTierQuota = false`.
 *
 * Settings → "Refresh models" adds each provider's live list on top of these;
 * this catalog is what pickers show before a refresh, and what the defaults are.
 */
object ModelCatalog {

    /**
     * One selectable model.
     *
     * @param freeTierQuota false flags a model with little or no free-tier quota
     *   (needs billing) — surfaced in the picker so users understand a 429 is a
     *   quota limit, not an app bug.
     */
    data class Model(
        val id: String,
        val freeTierQuota: Boolean = true,
        val note: String? = null,
        /**
         * Provider-enforced per-response output-token ceiling, if known. Requests are
         * clamped to this so a model with a low cap (e.g. the old gpt-4o at 16,384)
         * cannot 400 with "max_token is too large" when we ask for the full
         * [com.bori.core.ai.context.ContextManager] budget. null = no clamp.
         */
        val maxOutput: Int? = null,
    )

    private data class ProviderModels(
        val chat: List<Model>,
        val completion: List<Model>,
        val defaultChat: String,
        val defaultCompletion: String,
    )

    // Note reused for Gemini 3.x models: they have NO API free tier, so they need a
    // paid key — or an AI-Studio key generated under a Google AI Pro/Ultra plan
    // (AI Studio was integrated into those subscriptions in April 2026).
    private const val NO_FREE = "Requires a paid or Google AI Pro/Ultra key (from AI Studio)"

    private val catalog: Map<AiProvider, ProviderModels> = mapOf(
        AiProvider.ANTHROPIC to ProviderModels(
            chat = listOf(
                Model("claude-sonnet-5-5"),
                Model("claude-opus-5-5", note = "⭐ Best for long agentic coding"),
                Model("claude-fable-5-1", note = "Hardest problems — slower, most expensive"),
                Model("claude-haiku-5-5", note = "Fastest and cheapest"),
            ),
            completion = listOf(Model("claude-haiku-5-5")),
            defaultChat = "claude-sonnet-5-5",
            defaultCompletion = "claude-haiku-5-5",
        ),
        AiProvider.DEEPSEEK to ProviderModels(
            chat = listOf(Model("deepseek-flash"), Model("deepseek-v4-pro")),
            completion = listOf(Model("deepseek-flash")),
            defaultChat = "deepseek-flash",
            defaultCompletion = "deepseek-flash",
        ),
        AiProvider.OPENAI to ProviderModels(
            // GPT-6 only does tool calling on the Responses API, which OpenAiClient uses.
            chat = listOf(
                Model("gpt-6.1-sol"),
                Model("gpt-6-astra", note = "⭐ Strongest — slower, for hard problems"),
                Model("gpt-6-luna", note = "Fast and cheap"),
            ),
            completion = listOf(Model("gpt-6-luna")),
            defaultChat = "gpt-6.1-sol",
            defaultCompletion = "gpt-6-luna",
        ),
        AiProvider.KIMI to ProviderModels(
            chat = listOf(
                Model("kimi-k3"),
                Model("kimi-k2.7-code", note = "Coding-tuned"),
                Model("kimi-k2.7-code-highspeed", note = "Coding-tuned, faster"),
                Model("kimi-k2.6"),
            ),
            completion = listOf(Model("kimi-k2.7-code-highspeed")),
            defaultChat = "kimi-k3",
            defaultCompletion = "kimi-k2.7-code-highspeed",
        ),
        AiProvider.KIMI_CODE to ProviderModels(
            chat = listOf(Model("kimi-for-coding", note = "Uses your Kimi Code membership")),
            completion = listOf(Model("kimi-for-coding")),
            defaultChat = "kimi-for-coding",
            defaultCompletion = "kimi-for-coding",
        ),
        AiProvider.OPENROUTER to ProviderModels(
            // The Settings picker shows a free-text field for OpenRouter; these are
            // just convenience suggestions for the chat picker.
            chat = listOf(
                Model("moonshotai/kimi-k3"),
                Model("anthropic/claude-sonnet-5-5"),
                Model("deepseek/deepseek-flash"),
                Model("google/gemini-3.8-flash"),
            ),
            completion = emptyList(),
            defaultChat = "moonshotai/kimi-k3",
            defaultCompletion = "",
        ),
        AiProvider.GEMINI to ProviderModels(
            chat = listOf(
                Model("gemini-3.8-flash", note = "Free tier — rate-limited; use a paid or Pro/Ultra key for sustained coding"),
                Model("gemini-3.6-flash", note = "Free tier"),
                Model("gemini-3.5-flash-lite", note = "Free tier — fastest/cheapest, light use"),
                Model("gemini-3.1-pro-preview", freeTierQuota = false, note = "Deep reasoning / hard problems — $NO_FREE"),
            ),
            completion = listOf(
                Model("gemini-3.5-flash-lite"),
                Model("gemini-3.8-flash"),
            ),
            defaultChat = "gemini-3.8-flash",
            defaultCompletion = "gemini-3.5-flash-lite",
        ),
        // Models come from the user's own server via Settings → "Refresh models"
        AiProvider.LOCAL_SERVER to ProviderModels(
            chat = emptyList(),
            completion = emptyList(),
            defaultChat = "",
            defaultCompletion = "",
        ),
        // Coding CLIs in the Linux environment. "default" passes no --model, so the CLI uses its
        // own choice (its /model setting); the rest are the CLIs' own names, not API model IDs.
        AiProvider.CLAUDE_CODE to ProviderModels(
            chat = listOf(
                Model("default", note = "The model set in Claude Code (/model)"),
                Model("opus", note = "Opus — best for long agentic coding"),
                Model("sonnet", note = "Sonnet — fast and capable"),
                Model("haiku", note = "Haiku — fastest, uses the least of your plan"),
            ),
            completion = emptyList(),
            defaultChat = "default",
            defaultCompletion = "",
        ),
        // From `antigravity models` (1.4.7, 2026-10-25).
        AiProvider.ANTIGRAVITY to ProviderModels(
            chat = listOf(
                Model("default", note = "The model set in Antigravity (/model)"),
                Model("gemini-4-argon-medium"),
                Model("gemini-4-argon-high", note = "Slower, for hard problems"),
                Model("gemini-3.8-flash-medium", note = "Fast"),
                Model("claude-opus-5-5-medium"),
                Model("claude-sonnet-5-5-medium"),
            ),
            completion = emptyList(),
            defaultChat = "default",
            defaultCompletion = "",
        ),
        // On-device models; which one runs is chosen in Settings → Local AI (ModelDownloadManager)
        AiProvider.LOCAL_GEMMA to ProviderModels(
            chat = LocalModel.entries.map { Model(it.id, note = it.description) },
            completion = LocalModel.entries.map { Model(it.id) },
            defaultChat = LocalModel.GEMMA_4_E2B.id,
            defaultCompletion = LocalModel.GEMMA_4_E2B.id,
        ),
    )

    /**
     * Model IDs that older app versions offered or defaulted to. Some are retired,
     * others are older generations still served; either way a saved one is moved up
     * to the provider's current default instead of being kept forever.
     */
    private val supersededModels: Set<String> = setOf(
        "claude-sonnet-4-6", "claude-opus-4-6", "claude-opus-4-8", "claude-haiku-4-5-20251001",
        "gpt-4o", "gpt-4o-mini", "o1-mini", "o3-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-5.5",
        "deepseek-chat", "deepseek-reasoner", "deepseek-coder", "deepseek-v4-flash",
        "gemini-3.5-flash",
        "deepseek/deepseek-chat", "deepseek/deepseek-coder", "deepseek/deepseek-v4-flash",
        "anthropic/claude-sonnet-4-6",
    )

    /** True for an ID an older app version saved that should move to the current default. */
    fun isSuperseded(id: String): Boolean = id in supersededModels

    fun chatModels(provider: AiProvider): List<Model> = catalog[provider]?.chat ?: emptyList()
    fun completionModels(provider: AiProvider): List<Model> = catalog[provider]?.completion ?: emptyList()

    fun chatModelIds(provider: AiProvider): List<String> = chatModels(provider).map { it.id }
    fun completionModelIds(provider: AiProvider): List<String> = completionModels(provider).map { it.id }

    fun defaultChatModel(provider: AiProvider): String = catalog[provider]?.defaultChat ?: ""
    fun defaultCompletionModel(provider: AiProvider): String = catalog[provider]?.defaultCompletion ?: ""

    /** Default chat model per provider — drop-in for the old duplicated maps. */
    val defaultChatModels: Map<AiProvider, String> = catalog.mapValues { it.value.defaultChat }

    /** Default completion model per provider — drop-in for the old duplicated maps. */
    val defaultCompletionModels: Map<AiProvider, String> = catalog.mapValues { it.value.defaultCompletion }

    /** Look up a known model's metadata (free-tier flag / note), or null if unknown. */
    fun model(provider: AiProvider, id: String): Model? =
        catalog[provider]?.let { pm -> (pm.chat + pm.completion).firstOrNull { it.id == id } }
}
