package com.bori.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.build.KeystoreRepository
import com.bori.app.data.prefs.UserPreferences
import com.bori.app.data.prefs.UserPreferencesRepository
import com.bori.app.data.usage.TokenUsage
import com.bori.app.data.usage.UsageRepository
import com.bori.core.ai.model.ProviderBalance
import com.bori.core.ai.router.ModelRouter
import com.bori.core.project.ProjectRepository
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.core.build.model.KeystoreConfig
import com.bori.core.build.steps.ApkSigner
import com.bori.core.ai.local.DownloadState
import com.bori.core.ai.local.ModelDownloadManager
import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class KeystoreFormState(
    val enabled: Boolean = false,
    val path: String = "",
    val alias: String = "",
    val keystorePassword: String = "",
    val keyPassword: String = "",
    val verifyResult: String? = null,
)

/** One provider's usage row in Settings: app-tracked tokens + optional live balance. */
data class ProviderUsageUi(
    val provider: AiProvider,
    val tokens: TokenUsage,
    val balance: BalanceUiState,
)

/** State of the live-balance fetch for a provider. */
sealed interface BalanceUiState {
    /** Provider exposes no quota API — show app-tracked tokens only. */
    object None : BalanceUiState
    object Loading : BalanceUiState
    data class Loaded(val balance: ProviderBalance) : BalanceUiState
    data class Error(val message: String) : BalanceUiState
}

data class SettingsUiState(
    val keyInputs: Map<AiProvider, String> = emptyMap(),
    val savedProviders: Set<AiProvider> = emptySet(),
    val keystoreForm: KeystoreFormState = KeystoreFormState(),
    val selectedDisplayProvider: AiProvider = AiProvider.ANTHROPIC,
    val tavilyKeyInput: String = "",
    val tavilyKeySaved: Boolean = false,
    /** Provider whose live model list is being fetched, if any. */
    val refreshingModels: AiProvider? = null,
    val modelFetchErrors: Map<AiProvider, String> = emptyMap(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val keyVault: KeyVault,
    private val prefsRepository: UserPreferencesRepository,
    private val keystoreRepository: KeystoreRepository,
    private val apkSigner: ApkSigner,
    private val projectRepository: ProjectRepository,
    private val downloadManager: ModelDownloadManager,
    // App-tracked per-provider token totals; router supplies live balances where available.
    private val usageRepository: UsageRepository,
    private val router: ModelRouter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Per-provider usage rows shown in Settings; (re)populated by [refreshUsage]. */
    private val _usage = MutableStateFlow<List<ProviderUsageUi>>(emptyList())
    val usage: StateFlow<List<ProviderUsageUi>> = _usage.asStateFlow()

    /**
     * Rebuilds the per-provider usage list: app-tracked token totals for every configured
     * provider, plus a live balance fetch for those that expose one (OpenRouter, DeepSeek).
     */
    fun refreshUsage() {
        viewModelScope.launch {
            val tokens = usageRepository.usage.first()
            val saved = AiProvider.entries.filter { !it.isLocal && keyVault.hasKey(it) }
            _usage.value = saved.map { p ->
                ProviderUsageUi(
                    provider = p,
                    tokens = tokens[p] ?: TokenUsage(),
                    balance = if (hasBalanceApi(p)) BalanceUiState.Loading else BalanceUiState.None,
                )
            }
            saved.filter { hasBalanceApi(it) }.forEach { p ->
                val state = try {
                    router.fetchBalance(p)?.let { BalanceUiState.Loaded(it) }
                        ?: BalanceUiState.Error("Unavailable")
                } catch (e: Exception) {
                    BalanceUiState.Error(e.message ?: "Failed to load")
                }
                _usage.update { list -> list.map { if (it.provider == p) it.copy(balance = state) else it } }
            }
        }
    }

    /** Clears one provider's app-tracked token totals, then re-reads the list. */
    fun resetUsage(provider: AiProvider) {
        viewModelScope.launch {
            usageRepository.reset(provider)
            refreshUsage()
        }
    }

    // Only OpenRouter and DeepSeek expose a queryable balance/credits endpoint; the rest are
    // app-tracked tokens only.
    private fun hasBalanceApi(provider: AiProvider): Boolean =
        provider == AiProvider.OPENROUTER || provider == AiProvider.DEEPSEEK

    val isGemmaReady: StateFlow<Boolean> = downloadManager.downloadState
        .map { it is DownloadState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), downloadManager.isModelReady())

    val userPrefs: StateFlow<UserPreferences> = prefsRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    private val _deletedProjects = MutableStateFlow<List<ProjectRepository.TrashedProject>>(emptyList())
    val deletedProjects: StateFlow<List<ProjectRepository.TrashedProject>> = _deletedProjects.asStateFlow()

    fun loadDeletedProjects() {
        _deletedProjects.value = projectRepository.listTrash()
    }

    fun restoreProject(trashed: ProjectRepository.TrashedProject) {
        viewModelScope.launch(Dispatchers.IO) {
            projectRepository.restoreFromTrash(trashed)
            loadDeletedProjects()
        }
    }

    fun permanentlyDeleteFromTrash(trashed: ProjectRepository.TrashedProject) {
        viewModelScope.launch(Dispatchers.IO) {
            projectRepository.permanentlyDeleteFromTrash(trashed)
            loadDeletedProjects()
        }
    }

    init {
        viewModelScope.launch {
            val initialPrefs = prefsRepository.preferences.first()
            val saved = AiProvider.entries.filter { !it.isLocal && keyVault.hasKey(it) }.toSet()
            val savedConfig = keystoreRepository.get()
            val keystoreForm = if (savedConfig != null) {
                KeystoreFormState(
                    enabled = true,
                    path = savedConfig.keystorePath,
                    alias = savedConfig.keyAlias,
                    keystorePassword = savedConfig.keystorePassword,
                    keyPassword = savedConfig.keyPassword,
                )
            } else KeystoreFormState()
            _uiState.update { it.copy(
                savedProviders = saved,
                keystoreForm = keystoreForm,
                selectedDisplayProvider = initialPrefs.chatProvider,
                tavilyKeySaved = keyVault.hasServiceKey(KeyVault.TAVILY),
            ) }
        }
        loadDeletedProjects()
    }

    fun setDisplayProvider(provider: AiProvider) {
        _uiState.update { it.copy(selectedDisplayProvider = provider) }
    }

    fun updateKeyInput(provider: AiProvider, value: String) {
        _uiState.update { state ->
            state.copy(keyInputs = state.keyInputs + (provider to value))
        }
    }

    fun saveKey(provider: AiProvider) {
        viewModelScope.launch {
            val key = _uiState.value.keyInputs[provider] ?: return@launch
            keyVault.storeKey(provider, key.trim())
            _uiState.update { state ->
                state.copy(
                    savedProviders = state.savedProviders + provider,
                    keyInputs = state.keyInputs - provider,
                )
            }
        }
    }

    fun deleteKey(provider: AiProvider) {
        viewModelScope.launch {
            keyVault.deleteKey(provider)
            _uiState.update { state ->
                state.copy(savedProviders = state.savedProviders - provider)
            }
        }
    }

    fun updateTavilyKeyInput(value: String) {
        _uiState.update { it.copy(tavilyKeyInput = value) }
    }

    fun saveTavilyKey() {
        viewModelScope.launch {
            val key = _uiState.value.tavilyKeyInput.trim()
            if (key.isEmpty()) return@launch
            keyVault.storeServiceKey(KeyVault.TAVILY, key)
            _uiState.update { it.copy(tavilyKeySaved = true, tavilyKeyInput = "") }
        }
    }

    fun deleteTavilyKey() {
        viewModelScope.launch {
            keyVault.deleteServiceKey(KeyVault.TAVILY)
            _uiState.update { it.copy(tavilyKeySaved = false) }
        }
    }

    fun refreshApiKeys() {
        viewModelScope.launch {
            val saved = AiProvider.entries.filter { !it.isLocal && keyVault.hasKey(it) }.toSet()
            _uiState.update { it.copy(savedProviders = saved) }
        }
    }

    fun setChatProvider(provider: AiProvider) {
        viewModelScope.launch {
            prefsRepository.updateChatProvider(provider)
            prefsRepository.updateCompletionProvider(provider)
            prefsRepository.updateCompletionModel(defaultCompletionModels[provider] ?: "")
        }
    }

    /** Fetches the live model list for [provider] and caches it; failures are shown inline. */
    fun refreshModels(provider: AiProvider) {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshingModels = provider, modelFetchErrors = it.modelFetchErrors - provider) }
            try {
                val models = router.listModels(provider)
                prefsRepository.updateCachedModels(provider, models)
                // A local server has no default model, so pick its first one rather than leave chat broken
                if (provider == AiProvider.LOCAL_SERVER && models.isNotEmpty()) {
                    val prefs = prefsRepository.preferences.first()
                    if (prefs.chatProvider == provider && prefs.chatModel.isEmpty()) prefsRepository.updateChatModel(models.first())
                    if (prefs.completionProvider == provider && prefs.completionModel.isEmpty()) {
                        prefsRepository.updateCompletionModel(models.first())
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    it.copy(modelFetchErrors = it.modelFetchErrors + (provider to (e.message ?: "Couldn't fetch models")))
                }
            } finally {
                _uiState.update { it.copy(refreshingModels = null) }
            }
        }
    }

    fun setChatModel(model: String) {
        viewModelScope.launch { prefsRepository.updateChatModel(model) }
    }

    fun setCompletionProvider(provider: AiProvider) {
        viewModelScope.launch { prefsRepository.updateCompletionProvider(provider) }
    }

    fun setCompletionModel(model: String) {
        viewModelScope.launch { prefsRepository.updateCompletionModel(model) }
    }

    fun restartTutorial() {
        viewModelScope.launch { prefsRepository.resetTutorial() }
    }

    fun setExperimentalFeaturesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.setExperimentalFeaturesEnabled(enabled)
            if (!enabled) {
                val current = prefsRepository.preferences.first()
                if (current.chatProvider == AiProvider.LOCAL_GEMMA)
                    prefsRepository.updateChatProvider(AiProvider.ANTHROPIC)
                if (current.completionProvider == AiProvider.LOCAL_GEMMA)
                    prefsRepository.updateCompletionProvider(AiProvider.DEEPSEEK)
            }
        }
    }

    /** Persists the user's chosen chat-bubble/send-button color (ARGB long). */
    fun setChatBubbleColor(argb: Long) {
        viewModelScope.launch { prefsRepository.updateChatBubbleColor(argb) }
    }

    fun setThemeMode(mode: com.bori.app.data.prefs.ThemeMode) {
        viewModelScope.launch { prefsRepository.setThemeMode(mode) }
    }

    fun setKotlinTimeoutMinutes(minutes: Int) {
        viewModelScope.launch { prefsRepository.updateKotlinTimeoutMinutes(minutes) }
    }

    fun setAiWorkTurns(turns: Int) {
        viewModelScope.launch { prefsRepository.updateAiWorkTurns(turns) }
    }

    fun setCompilerPerformanceProfile(profile: CompilerPerformanceProfile) {
        viewModelScope.launch { prefsRepository.updateCompilerPerformanceProfile(profile) }
    }

    fun setKeystoreEnabled(enabled: Boolean) {
        _uiState.update { it.copy(keystoreForm = it.keystoreForm.copy(enabled = enabled)) }
        if (!enabled) {
            keystoreRepository.clear()
            _uiState.update { it.copy(keystoreForm = KeystoreFormState()) }
        }
    }

    fun updateKeystorePath(path: String) =
        _uiState.update { it.copy(keystoreForm = it.keystoreForm.copy(path = path, verifyResult = null)) }

    fun updateKeystoreAlias(alias: String) =
        _uiState.update { it.copy(keystoreForm = it.keystoreForm.copy(alias = alias, verifyResult = null)) }

    fun updateKeystorePassword(password: String) =
        _uiState.update { it.copy(keystoreForm = it.keystoreForm.copy(keystorePassword = password, verifyResult = null)) }

    fun updateKeyPassword(password: String) =
        _uiState.update { it.copy(keystoreForm = it.keystoreForm.copy(keyPassword = password, verifyResult = null)) }

    fun saveKeystoreConfig() {
        val form = _uiState.value.keystoreForm
        if (!form.enabled) { keystoreRepository.clear(); return }
        keystoreRepository.save(
            KeystoreConfig(
                keystorePath = form.path,
                keyAlias = form.alias,
                keystorePassword = form.keystorePassword,
                keyPassword = form.keyPassword,
            ),
        )
    }

    companion object {
        // Single source of truth — see ModelCatalog.
        val defaultCompletionModels: Map<AiProvider, String> =
            com.bori.core.ai.model.ModelCatalog.defaultCompletionModels
    }

    fun verifyKeystore() {
        val form = _uiState.value.keystoreForm
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val config = KeystoreConfig(form.path, form.alias, form.keystorePassword, form.keyPassword)
                    val aliases = apkSigner.listAliases(config)
                    "OK — aliases: ${aliases.joinToString()}"
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            _uiState.update { it.copy(keystoreForm = it.keystoreForm.copy(verifyResult = result)) }
        }
    }
}
