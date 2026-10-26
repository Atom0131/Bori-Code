package com.bori.app.data.usage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.bori.core.security.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Cumulative token counts the app has observed for one provider. */
data class TokenUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/**
 * Tracks cumulative AI token usage per provider, persisted in the shared Preferences DataStore.
 * This is the app's own count (providers like Anthropic/OpenAI/Gemini expose no quota API), so it
 * reflects usage since install / last reset, not the provider's billing. Backed by the existing
 * Preferences DataStore (no Room entity / migration required).
 */
@Singleton
class UsageRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private fun promptKey(p: AiProvider) = longPreferencesKey("usage_${p.name}_prompt")
    private fun completionKey(p: AiProvider) = longPreferencesKey("usage_${p.name}_completion")

    /** Per-provider cumulative token usage. */
    val usage: Flow<Map<AiProvider, TokenUsage>> = dataStore.data.map { prefs ->
        AiProvider.entries.associateWith { p ->
            TokenUsage(
                promptTokens = prefs[promptKey(p)] ?: 0L,
                completionTokens = prefs[completionKey(p)] ?: 0L,
            )
        }
    }

    /** Adds the tokens from one request to the running totals for [provider]. */
    suspend fun record(provider: AiProvider, promptTokens: Int, completionTokens: Int) {
        if (promptTokens <= 0 && completionTokens <= 0) return
        dataStore.edit { e ->
            e[promptKey(provider)] = (e[promptKey(provider)] ?: 0L) + promptTokens
            e[completionKey(provider)] = (e[completionKey(provider)] ?: 0L) + completionTokens
        }
    }

    /** Clears the tracked totals for one provider. */
    suspend fun reset(provider: AiProvider) {
        dataStore.edit { e ->
            e.remove(promptKey(provider))
            e.remove(completionKey(provider))
        }
    }
}
