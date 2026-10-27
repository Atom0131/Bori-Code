package com.bori.app.data.build

import android.content.Context
import com.bori.app.data.prefs.UserPreferencesRepository
import com.bori.app.service.ChatForegroundService
import com.bori.core.build.BuildEngine
import com.bori.core.build.DiagnosticParser
import com.bori.core.build.model.BuildEvent
import com.bori.core.build.model.BuildVariant
import com.bori.core.project.model.Project
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the Build screen's builds in the app's scope, not a ViewModel's: closing the screen or
 * swiping Bori out of Recents no longer cancels a build ([ChatForegroundService] keeps the process
 * alive), and a reopened screen picks the running build back up from [uiState] and [logs].
 */
@Singleton
class BuildRunner @Inject constructor(
    private val buildEngine: BuildEngine,
    private val buildStateHolder: BuildStateHolder,
    private val diagnosticsStateHolder: DiagnosticsStateHolder,
    private val prefsRepository: UserPreferencesRepository,
    private val keystoreRepository: KeystoreRepository,
    @ApplicationContext private val context: Context,
) {
    sealed class UiState {
        data object Idle : UiState()
        data object Building : UiState()
        data class Done(val apkPath: String, val packageName: String) : UiState()
        data class Failed(val error: String) : UiState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _logs = MutableStateFlow<List<BuildEvent.Log>>(emptyList())
    val logs: StateFlow<List<BuildEvent.Log>> = _logs.asStateFlow()

    fun start(project: Project, variant: BuildVariant, appBundle: Boolean) {
        if (_uiState.value is UiState.Building) return
        _logs.value = emptyList()
        _uiState.value = UiState.Building
        ChatForegroundService.acquire(context, ChatForegroundService.Reason.BUILD, project.name)
        scope.launch {
            try {
                buildStateHolder.onStarted()
                diagnosticsStateHolder.clear()
                val prefs = prefsRepository.preferences.first()
                val allMessages = mutableListOf<String>()
                buildEngine.build(
                    project = project,
                    kotlinTimeoutSeconds = prefs.kotlinTimeoutMinutes * 60L,
                    compilerProfile = prefs.compilerPerformanceProfile,
                    keystoreConfig = keystoreRepository.get(),
                    variant = variant,
                    appBundle = appBundle && variant == BuildVariant.RELEASE,
                ).collect { event ->
                    when (event) {
                        is BuildEvent.Log -> {
                            _logs.update { it + event }
                            buildStateHolder.onLog(event.message)
                            allMessages += event.message
                        }
                        is BuildEvent.Success -> {
                            _uiState.value = UiState.Done(event.apkPath, event.applicationId ?: project.packageName)
                            // A bundle can't be installed; the last APK stays the one Install uses.
                            if (!event.apkPath.endsWith(".aab")) buildStateHolder.onSuccess(event.apkPath, event.applicationId)
                            diagnosticsStateHolder.update(DiagnosticParser.parse(allMessages))
                            recordBuildForAgents(project.rootPath, ok = true, "${variant.name.lowercase()} build, ${File(event.apkPath).name}")
                        }
                        is BuildEvent.Failure -> {
                            _uiState.value = UiState.Failed(event.error)
                            buildStateHolder.onFailure(event.error)
                            diagnosticsStateHolder.update(DiagnosticParser.parse(allMessages))
                            recordBuildForAgents(project.rootPath, ok = false, "${variant.name.lowercase()} build, error: ${event.error.lineSequence().firstOrNull().orEmpty().take(300)}")
                        }
                        BuildEvent.Started -> Unit
                    }
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Failed(e.message ?: e.toString())
                buildStateHolder.onFailure(e.message ?: e.toString())
            } finally {
                if (_uiState.value is UiState.Building) _uiState.value = UiState.Failed("Build stopped")
                ChatForegroundService.release(context, ChatForegroundService.Reason.BUILD)
            }
        }
    }

    /** Shows [error] in the Build screen without building (e.g. no project is open). */
    fun fail(error: String) {
        if (_uiState.value !is UiState.Building) _uiState.value = UiState.Failed(error)
    }

    fun reset() {
        if (_uiState.value !is UiState.Building) _uiState.value = UiState.Idle
    }

    /** Every agent (built-in, Claude Code, Antigravity) reads the last real build from .bori/handoff.md. */
    private fun recordBuildForAgents(rootPath: String, ok: Boolean, summary: String) {
        if (rootPath.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching { com.bori.core.project.agent.AgentFiles.appendBuildResult(File(rootPath), ok, summary) }
        }
    }
}
