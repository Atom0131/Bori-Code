package com.bori.app.data.build

import com.bori.core.project.agent.AgentFiles
import android.content.Context
import com.bori.app.data.prefs.UserPreferencesRepository
import com.bori.app.service.ChatForegroundService
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
    private val buildEngine: BuildProcessClient,
    private val buildStateHolder: BuildStateHolder,
    private val diagnosticsStateHolder: DiagnosticsStateHolder,
    private val prefsRepository: UserPreferencesRepository,
    private val keystoreRepository: KeystoreRepository,
    private val buildLock: BuildLock,
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

    private val times by lazy { context.getSharedPreferences("build_times", Context.MODE_PRIVATE) }

    /**
     * How long [variant] last took to build for the project at [rootPath] (waiting for another build
     * not counted), or null if it hasn't been built on this phone. The Build sheet shows it before
     * you tap Build; the agents read it from .bori/handoff.md.
     */
    fun lastDurationMs(rootPath: String, variant: BuildVariant): Long? =
        times.getLong("$rootPath|${variant.name}", 0L).takeIf { it > 0 }

    private fun recordDuration(rootPath: String, variant: BuildVariant, ms: Long) {
        if (rootPath.isNotBlank()) times.edit().putLong("$rootPath|${variant.name}", ms).apply()
    }

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
                buildLock.withLock(onWait = { _logs.update { it + BuildEvent.Log(BuildLock.WAITING) } }) {
                    val startedAt = System.currentTimeMillis()
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
                                val took = System.currentTimeMillis() - startedAt
                                recordDuration(project.rootPath, variant, took)
                                val copy = copyToProject(project.rootPath, File(event.apkPath), variant)
                                copy?.let { _logs.update { l -> l + BuildEvent.Log("Copied to $it in the project folder") } }
                                _logs.update { l -> l + BuildEvent.Log("Took ${AgentFiles.formatDuration(took)}") }
                                recordBuildForAgents(
                                    project.rootPath, ok = true,
                                    "${variant.name.lowercase()} build, ${File(event.apkPath).name}" +
                                        (copy?.let { "\nOutput: $it (in the project folder; upload it from there, e.g. `gh release create`)" } ?: ""),
                                    took,
                                )
                            }
                            is BuildEvent.Failure -> {
                                _uiState.value = UiState.Failed(event.error)
                                buildStateHolder.onFailure(event.error)
                                diagnosticsStateHolder.update(DiagnosticParser.parse(allMessages))
                                recordBuildForAgents(
                                    project.rootPath, ok = false,
                                    "${variant.name.lowercase()} build, error: ${event.error.lineSequence().firstOrNull().orEmpty().take(300)}",
                                    System.currentTimeMillis() - startedAt,
                                )
                            }
                            BuildEvent.Started -> Unit
                        }
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

    /** Back to Idle (the sheet's choice of variant, then Build); the old build's log goes too. */
    fun reset() {
        if (_uiState.value is UiState.Building) return
        _uiState.value = UiState.Idle
        _logs.value = emptyList()
    }

    /**
     * Copies a built APK or app bundle into the project, where Gradle would put it
     * (`app/build/outputs/apk/debug/app-debug.apk`, `…/bundle/release/app-release.aab`), so the coding
     * CLIs in Linux, git and gh can see it: the build itself stays in the app's private storage.
     * Returns the path relative to the project, or null when it couldn't be copied.
     */
    private fun copyToProject(rootPath: String, output: File, variant: BuildVariant): String? {
        if (rootPath.isBlank() || !output.isFile) return null
        return runCatching {
            val root = File(rootPath)
            val module = if (File(root, "app").isDirectory) "app/" else ""
            val kind = if (output.extension == "aab") "bundle" else "apk"
            val rel = "${module}build/outputs/$kind/${variant.name.lowercase()}/${output.name}"
            val dest = File(root, rel)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            output.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
            rel
        }.onFailure { android.util.Log.w("BuildRunner", "Copying the build output into the project", it) }.getOrNull()
    }

    /** Every agent (built-in, Claude Code, Antigravity) reads the last real build from .bori/handoff.md. */
    private fun recordBuildForAgents(rootPath: String, ok: Boolean, summary: String, tookMs: Long) {
        if (rootPath.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching { AgentFiles.appendBuildResult(File(rootPath), ok, summary, tookMs) }
        }
    }
}
