package com.bori.core.ai.router

import com.bori.core.ai.local.ConnectivityChecker
import com.bori.core.ai.local.LocalGemmaClient
import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatRequest
import com.bori.core.ai.model.CompletionChunk
import com.bori.core.ai.model.CompletionRequest
import com.bori.core.ai.model.ProviderBalance
import com.bori.core.ai.provider.AiProviderClient
import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the appropriate [AiProviderClient] and model for each task type.
 *
 * Resolution order:
 *  1. If device is offline and local Gemma model is downloaded → route to [LocalGemmaClient].
 *  2. If [ChatRequest.preferredProvider] is set and has a saved key, use it.
 *  3. Otherwise fall back to the hard-coded cloud priority list.
 */
@Singleton
class ModelRouter @Inject constructor(
    private val keyVault: KeyVault,
    private val deepSeekClient: com.bori.core.ai.provider.DeepSeekClient,
    private val openAiClient: com.bori.core.ai.provider.OpenAiClient,
    private val openRouterClient: com.bori.core.ai.provider.OpenRouterClient,
    private val anthropicClient: com.bori.core.ai.provider.AnthropicClient,
    private val geminiClient: com.bori.core.ai.provider.GeminiClient,
    private val kimiClient: com.bori.core.ai.provider.KimiClient,
    private val kimiCodeClient: com.bori.core.ai.provider.KimiCodeClient,
    private val localServerClient: com.bori.core.ai.provider.LocalServerClient,
    private val localGemmaClient: LocalGemmaClient,
    private val connectivityChecker: ConnectivityChecker,
    cliClients: Set<@JvmSuppressWildcards com.bori.core.ai.provider.CliProviderClient>,
) {
    // Ordered fallback for chat (quality priority)
    private val chatProviderPriority: List<AiProviderClient> = listOf(
        anthropicClient,
        geminiClient,
        deepSeekClient,
        openAiClient,
        kimiCodeClient,
        kimiClient,
        openRouterClient,
        localServerClient,
    )

    // Ordered fallback for inline completion (cost + speed priority)
    private val completionProviderPriority: List<AiProviderClient> = listOf(
        deepSeekClient,
        geminiClient,
        openAiClient,
        kimiClient,
        kimiCodeClient,
        openRouterClient,
        anthropicClient,
        localServerClient,
    )

    // Default model used when neither the request nor the user has specified one.
    // Sourced from the shared catalog so the router can never disagree with the
    // pickers about which model string is actually sent (see ModelCatalog).
    private val defaultChatModels: Map<AiProvider, String> = com.bori.core.ai.model.ModelCatalog.defaultChatModels
    private val defaultCompletionModels: Map<AiProvider, String> = com.bori.core.ai.model.ModelCatalog.defaultCompletionModels

    private val clientsByProvider: Map<AiProvider, AiProviderClient> = mapOf(
        AiProvider.DEEPSEEK to deepSeekClient,
        AiProvider.OPENAI to openAiClient,
        AiProvider.OPENROUTER to openRouterClient,
        AiProvider.ANTHROPIC to anthropicClient,
        AiProvider.GEMINI to geminiClient,
        AiProvider.KIMI to kimiClient,
        AiProvider.KIMI_CODE to kimiCodeClient,
        AiProvider.LOCAL_SERVER to localServerClient,
        AiProvider.LOCAL_GEMMA to localGemmaClient,
    ) + cliClients.associateBy { it.provider }

    /** Whether a coding-CLI provider can run: its CLI is installed in a Linux distro. */
    fun isCliAvailable(provider: AiProvider): Boolean =
        (clientsByProvider[provider] as? com.bori.core.ai.provider.CliProviderClient)?.isAvailable() == true

    suspend fun chat(request: ChatRequest): Flow<ChatChunk> {
        // A coding CLI was picked: it's the agent, signed in with the user's account; no fallback.
        if (request.preferredProvider?.isCli == true) {
            val cli = clientsByProvider[request.preferredProvider]
                ?: return flow { emit(ChatChunk.Error("${request.preferredProvider.displayName} needs the Linux environment, which this build doesn't have.")) }
            return cli.chat(request)
        }
        // User explicitly chose local Gemma — honour it regardless of connectivity
        if (request.preferredProvider?.isLocal == true && localGemmaClient.isReady()) {
            return localGemmaClient.chat(request)
        }
        // Offline fallback: transparent re-route to local model
        if (!connectivityChecker.isOnline() && localGemmaClient.isReady()) {
            return localGemmaClient.chat(request)
        }
        // Local provider selected but not ready — clear stale inference
        if (request.preferredProvider?.isLocal == true) {
            localGemmaClient.invalidateCache()
        }
        // Offline with no usable local model — fail fast instead of hitting a DNS error
        if (!connectivityChecker.isOnline()) {
            return flow { emit(ChatChunk.Error("No internet connection. Download the Gemma local model in Settings to use Bori Code offline.")) }
        }
        val cloudRequest = if (request.preferredProvider?.isLocal == true)
            request.copy(preferredProvider = null, model = "")
        else request
        val client = resolveCloudClient(chatProviderPriority, cloudRequest.preferredProvider)
        val model = modelFor(client, cloudRequest.preferredProvider, cloudRequest.model, defaultChatModels)
        return client.chat(cloudRequest.copy(model = model))
    }

    suspend fun complete(request: CompletionRequest): Flow<CompletionChunk> {
        val client = resolveCloudClient(completionProviderPriority, request.preferredProvider)
        val model = modelFor(client, request.preferredProvider, request.model, defaultCompletionModels)
        return client.complete(request.copy(model = model))
    }

    /**
     * The requested model only belongs to the provider it was picked for. When the router
     * fell back to another provider (no key saved for the chosen one), that provider's
     * default is used instead of sending it a model name it doesn't serve.
     */
    private fun modelFor(
        client: AiProviderClient,
        preferred: AiProvider?,
        requested: String,
        defaults: Map<AiProvider, String>,
    ): String {
        val usable = requested.isNotEmpty() && (preferred == null || preferred == client.provider)
        return if (usable) requested else defaults[client.provider] ?: ""
    }

    /** Fetches the live model list for [provider]. Throws if the provider has no key or the call fails. */
    suspend fun listModels(provider: AiProvider): List<String> =
        clientsByProvider[provider]?.listModels() ?: emptyList()

    /** Fetches live balance/credit for a provider, if it exposes one. Null otherwise. */
    suspend fun fetchBalance(provider: AiProvider): ProviderBalance? =
        clientsByProvider[provider]?.fetchBalance()

    /** Returns which provider would handle the next request (for status-bar display). */
    fun activeProvider(preferred: AiProvider?): AiProvider {
        if (preferred?.isCli == true) return preferred
        if (preferred?.isLocal == true && localGemmaClient.isReady()) return AiProvider.LOCAL_GEMMA
        if (!connectivityChecker.isOnline() && localGemmaClient.isReady()) return AiProvider.LOCAL_GEMMA
        if (preferred != null && !preferred.isLocal && keyVault.hasKey(preferred)) return preferred
        return chatProviderPriority.firstOrNull { keyVault.hasKey(it.provider) }?.provider
            ?: chatProviderPriority.first().provider
    }

    private fun resolveCloudClient(
        priority: List<AiProviderClient>,
        preferred: AiProvider?,
    ): AiProviderClient {
        // 1. User's explicit choice — only if a key is actually saved
        if (preferred != null && !preferred.isLocal && keyVault.hasKey(preferred)) {
            return clientsByProvider[preferred] ?: priority.first()
        }
        // 2. Priority-list fallback
        for (client in priority) {
            if (keyVault.hasKey(client.provider)) return client
        }
        // 3. No key configured at all — return first so it throws MissingApiKeyException
        return priority.first()
    }
}
