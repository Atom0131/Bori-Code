package com.bori.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bori.app.flavor.HAS_LINUX
import com.bori.core.ai.model.ModelCatalog
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.core.security.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Coding CLIs need the Linux environment: one saved by the full build (or a restored backup) isn't usable in Play. */
private val AiProvider.selectable: Boolean get() = HAS_LINUX || !isCli

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val CHAT_PROVIDER = stringPreferencesKey("chat_provider")
        val CHAT_MODEL = stringPreferencesKey("chat_model")
        val COMPLETION_PROVIDER = stringPreferencesKey("completion_provider")
        val COMPLETION_MODEL = stringPreferencesKey("completion_model")
        val KOTLIN_TIMEOUT_MINUTES = intPreferencesKey("kotlin_timeout_minutes")
        val AI_WORK_TURNS = intPreferencesKey("ai_work_turns")
        val COMPILER_PERFORMANCE_PROFILE = stringPreferencesKey("compiler_performance_profile")
        val HAS_SEEN_ONBOARDING = booleanPreferencesKey("has_seen_onboarding")
        val HAS_DISMISSED_TUTORIAL = booleanPreferencesKey("has_dismissed_tutorial")
        val EXPERIMENTAL_FEATURES_ENABLED = booleanPreferencesKey("experimental_features_enabled")
        // User-selectable chat bubble / send-button color, stored as an ARGB long.
        val CHAT_BUBBLE_COLOR = longPreferencesKey("chat_bubble_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        fun cachedModels(provider: AiProvider) = stringPreferencesKey("models_${provider.name}")
    }

    val preferences: Flow<UserPreferences> = dataStore.data.map { prefs ->
        val chatProvider = prefs[Keys.CHAT_PROVIDER]
            ?.let { name -> AiProvider.entries.firstOrNull { it.name == name && it.selectable } }
            ?: AiProvider.ANTHROPIC
        val completionProvider = prefs[Keys.COMPLETION_PROVIDER]
            ?.let { name -> AiProvider.entries.firstOrNull { it.name == name && it.selectable } }
            ?: AiProvider.DEEPSEEK
        val cachedModels = AiProvider.entries.mapNotNull { provider ->
            prefs[Keys.cachedModels(provider)]?.let { provider to it.split('\n').filter(String::isNotEmpty) }
        }.toMap()
        UserPreferences(
            chatProvider = chatProvider,
            chatModel = sanitizeModel(chatProvider, prefs[Keys.CHAT_MODEL] ?: "", isChat = true, cachedModels[chatProvider]),
            completionProvider = completionProvider,
            completionModel = sanitizeModel(completionProvider, prefs[Keys.COMPLETION_MODEL] ?: "", isChat = false, cachedModels[completionProvider]),
            kotlinTimeoutMinutes = prefs[Keys.KOTLIN_TIMEOUT_MINUTES] ?: 2,
            aiWorkTurns = prefs[Keys.AI_WORK_TURNS] ?: 50,
            compilerPerformanceProfile = prefs[Keys.COMPILER_PERFORMANCE_PROFILE]
                ?.let { name -> CompilerPerformanceProfile.entries.firstOrNull { it.name == name } }
                ?: CompilerPerformanceProfile.LOW,
            hasSeenOnboarding = prefs[Keys.HAS_SEEN_ONBOARDING] ?: false,
            hasDismissedTutorial = prefs[Keys.HAS_DISMISSED_TUTORIAL] ?: false,
            experimentalFeaturesEnabled = prefs[Keys.EXPERIMENTAL_FEATURES_ENABLED] ?: false,
            // Defaults to the original blue (0xFF1A56DB) when unset.
            chatBubbleColor = prefs[Keys.CHAT_BUBBLE_COLOR] ?: 0xFF1A56DB,
            themeMode = prefs[Keys.THEME_MODE]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.SYSTEM,
            cachedModels = cachedModels,
        )
    }

    /**
     * Drops a stored model ID that is no longer offered for its provider so a
     * stale selection can't keep failing. This matters for Gemini: IDs like
     * `gemini-3.5-pro` (removed — never existed) or the old `gemini-3.1-pro`
     * (renamed to `gemini-3.1-pro-preview`) would otherwise persist in DataStore
     * and 404 forever. Returning "" makes the router fall back to the catalog
     * default.
     *
     * An ID counts as offered if it's in [ModelCatalog] or in the provider's live
     * list from Settings → "Refresh models" ([cached]). IDs older app versions
     * defaulted to ([ModelCatalog.isSuperseded]) are dropped even if still listed,
     * so upgrading moves users onto the current default.
     *
     * Providers with a free-text picker (OpenRouter has an empty catalog list)
     * keep user-typed model IDs, apart from superseded ones.
     */
    private fun sanitizeModel(provider: AiProvider, stored: String, isChat: Boolean, cached: List<String>?): String {
        if (stored.isEmpty() || ModelCatalog.isSuperseded(stored)) return ""
        val known = if (isChat) ModelCatalog.chatModelIds(provider) else ModelCatalog.completionModelIds(provider)
        if (known.isEmpty()) return stored // free-text provider (e.g. OpenRouter) — keep as-is
        return if (stored in known || stored in cached.orEmpty()) stored else ""
    }

    suspend fun updateChatProvider(provider: AiProvider) {
        dataStore.edit { it[Keys.CHAT_PROVIDER] = provider.name }
    }

    suspend fun updateCachedModels(provider: AiProvider, models: List<String>) {
        dataStore.edit { it[Keys.cachedModels(provider)] = models.joinToString("\n") }
    }

    suspend fun updateChatModel(model: String) {
        dataStore.edit { it[Keys.CHAT_MODEL] = model }
    }

    suspend fun updateCompletionProvider(provider: AiProvider) {
        dataStore.edit { it[Keys.COMPLETION_PROVIDER] = provider.name }
    }

    suspend fun updateCompletionModel(model: String) {
        dataStore.edit { it[Keys.COMPLETION_MODEL] = model }
    }

    suspend fun updateKotlinTimeoutMinutes(minutes: Int) {
        dataStore.edit { it[Keys.KOTLIN_TIMEOUT_MINUTES] = minutes }
    }

    suspend fun updateAiWorkTurns(turns: Int) {
        dataStore.edit { it[Keys.AI_WORK_TURNS] = turns }
    }

    suspend fun updateCompilerPerformanceProfile(profile: CompilerPerformanceProfile) {
        dataStore.edit { it[Keys.COMPILER_PERFORMANCE_PROFILE] = profile.name }
    }

    suspend fun markOnboardingComplete() {
        dataStore.edit { it[Keys.HAS_SEEN_ONBOARDING] = true }
    }

    suspend fun dismissTutorial() {
        dataStore.edit { it[Keys.HAS_DISMISSED_TUTORIAL] = true }
    }

    /** Brings the "Build your first app" card back to Chat. */
    suspend fun resetTutorial() {
        dataStore.edit { it[Keys.HAS_DISMISSED_TUTORIAL] = false }
    }

    suspend fun setExperimentalFeaturesEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.EXPERIMENTAL_FEATURES_ENABLED] = enabled }
    }

    /** Persists the user's chosen chat-bubble color as an ARGB long. */
    suspend fun updateChatBubbleColor(argb: Long) {
        dataStore.edit { it[Keys.CHAT_BUBBLE_COLOR] = argb }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }
}
