package com.bori.core.security

/**
 * Enum of supported AI providers.
 *
 * The [displayName] is shown in the Settings UI.
 * The [prefKey] is used as the DataStore preference key to store the API key
 * (unused for [isLocal] providers, which check model file presence instead).
 * The [isLocal] flag marks providers that run on-device and require no API key.
 * The [isCli] flag marks coding CLIs that run in Bori Code's Linux environment and sign in with
 * the user's own account inside the CLI; they have no key in [KeyVault].
 */
enum class AiProvider(
    val displayName: String,
    val prefKey: String,
    val isLocal: Boolean = false,
    val isCli: Boolean = false,
) {
    DEEPSEEK(displayName = "DeepSeek", prefKey = "apikey_deepseek"),
    OPENAI(displayName = "OpenAI", prefKey = "apikey_openai"),
    OPENROUTER(displayName = "OpenRouter", prefKey = "apikey_openrouter"),
    ANTHROPIC(displayName = "Anthropic (Claude)", prefKey = "apikey_anthropic"),
    GEMINI(displayName = "Google Gemini", prefKey = "apikey_gemini"),
    KIMI(displayName = "Kimi (Moonshot API)", prefKey = "apikey_kimi"),
    KIMI_CODE(displayName = "Kimi Code subscription", prefKey = "apikey_kimi_code"),
    /** A server the user runs (Ollama, LM Studio). Its "key" slot holds the server URL. */
    LOCAL_SERVER(displayName = "Local server (Ollama / LM Studio)", prefKey = "local_server_url"),
    LOCAL_GEMMA(displayName = "On-device Gemma", prefKey = "local_gemma_enabled", isLocal = true),
    CLAUDE_CODE(displayName = "Claude Code (Linux)", prefKey = "cli_claude_code", isCli = true),
    ANTIGRAVITY(displayName = "Antigravity CLI (Linux)", prefKey = "cli_antigravity", isCli = true),
}
