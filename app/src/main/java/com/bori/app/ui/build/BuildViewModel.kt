package com.bori.app.ui.build

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.build.BuildStateHolder
import com.bori.app.data.build.DiagnosticsStateHolder
import com.bori.app.data.build.KeystoreRepository
import com.bori.app.data.prefs.UserPreferencesRepository
import com.bori.app.data.project.ProjectStateHolder
import com.bori.app.service.ChatForegroundService
import com.bori.core.build.DiagnosticParser
import com.bori.core.build.BuildEngine
import com.bori.core.build.model.BuildEvent
import com.bori.core.build.model.BuildVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BuildViewModel @Inject constructor(
    private val buildEngine: BuildEngine,
    private val projectStateHolder: ProjectStateHolder,
    private val buildStateHolder: BuildStateHolder,
    private val diagnosticsStateHolder: DiagnosticsStateHolder,
    private val prefsRepository: UserPreferencesRepository,
    private val keystoreRepository: KeystoreRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    sealed class UiState {
        data object Idle : UiState()
        data object Building : UiState()
        data class Done(val apkPath: String, val packageName: String) : UiState()
        data class Failed(val error: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _logs = MutableStateFlow<List<BuildEvent.Log>>(emptyList())
    val logs: StateFlow<List<BuildEvent.Log>> = _logs.asStateFlow()

    private val _isInstalled = MutableStateFlow(false)
    val isInstalled: StateFlow<Boolean> = _isInstalled.asStateFlow()

    private val _variant = MutableStateFlow(BuildVariant.DEBUG)
    val variant: StateFlow<BuildVariant> = _variant.asStateFlow()

    private val _hasCustomKeystore = MutableStateFlow(false)
    val hasCustomKeystore: StateFlow<Boolean> = _hasCustomKeystore.asStateFlow()

    init {
        viewModelScope.launch {
            _hasCustomKeystore.value = keystoreRepository.get() != null
        }
    }

    fun setVariant(v: BuildVariant) {
        if (_uiState.value !is UiState.Building) _variant.value = v
    }

    fun refreshInstallState(packageName: String) {
        _isInstalled.value = try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launchApp(packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { context.startActivity(it) }
    }

    private fun setLoading(loading: Boolean) {
        if (loading) {
            val intent = Intent(context, ChatForegroundService::class.java)
            context.startForegroundService(intent)
        } else {
            val intent = Intent(context, ChatForegroundService::class.java).apply {
                action = ChatForegroundService.ACTION_STOP
            }
            context.startService(intent)
        }
    }

    fun startBuild() {
        val project = projectStateHolder.activeProject.value ?: run {
            _uiState.value = UiState.Failed("No active project. Open a file or select a project from the Projects tab.")
            return
        }
        viewModelScope.launch {
            setLoading(true)
            try {
                _logs.value = emptyList()
                _uiState.value = UiState.Building
                buildStateHolder.onStarted()
                diagnosticsStateHolder.clear()
                val prefs = prefsRepository.preferences.first()
                val timeoutMinutes = prefs.kotlinTimeoutMinutes
                val kotlinTimeoutSeconds = timeoutMinutes * 60L
                val compilerProfile = prefs.compilerPerformanceProfile
                val allMessages = mutableListOf<String>()
                val keystoreConfig = keystoreRepository.get()
                buildEngine.build(
                    project = project,
                    kotlinTimeoutSeconds = kotlinTimeoutSeconds,
                    compilerProfile = compilerProfile,
                    keystoreConfig = keystoreConfig,
                    variant = _variant.value,
                ).collect { event ->
                    when (event) {
                        is BuildEvent.Log -> {
                            _logs.update { it + event }
                            buildStateHolder.onLog(event.message)
                            allMessages += event.message
                        }
                        is BuildEvent.Success -> {
                            val packageName = event.applicationId ?: project.packageName
                            _uiState.value = UiState.Done(event.apkPath, packageName)
                            refreshInstallState(packageName)
                            buildStateHolder.onSuccess(event.apkPath, event.applicationId)
                            diagnosticsStateHolder.update(DiagnosticParser.parse(allMessages))
                        }
                        is BuildEvent.Failure -> {
                            _uiState.value = UiState.Failed(event.error)
                            buildStateHolder.onFailure(event.error)
                            diagnosticsStateHolder.update(DiagnosticParser.parse(allMessages))
                        }
                        BuildEvent.Started -> Unit
                    }
                }
            } finally {
                setLoading(false)
            }
        }
    }

    fun reset() {
        _uiState.value = UiState.Idle
        _logs.value = emptyList()
    }
}
