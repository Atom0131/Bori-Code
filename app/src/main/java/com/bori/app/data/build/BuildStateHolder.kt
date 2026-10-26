package com.bori.app.data.build

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton cache of the most recent build result.
 *
 * Written to by [BuildViewModel] whenever the user triggers a build via the FAB, and by
 * [ToolExecutor] whenever the AI agent triggers a build via the trigger_build tool.
 * Read by [ToolExecutor] to satisfy get_build_logs tool calls without needing a ViewModel ref.
 */
@Singleton
class BuildStateHolder @Inject constructor() {

    data class LastBuild(
        val logs: List<String> = emptyList(),
        val status: String = "none",  // none | building | success | failed
        val apkPath: String? = null,
        val error: String? = null,
        /** The built app's package name (its applicationId, which can differ from the namespace). */
        val applicationId: String? = null,
    )

    private val _lastBuild = MutableStateFlow(LastBuild())
    val lastBuild: StateFlow<LastBuild> = _lastBuild.asStateFlow()

    fun onStarted() {
        _lastBuild.value = LastBuild(status = "building")
    }

    fun onLog(msg: String) {
        _lastBuild.update { it.copy(logs = it.logs + msg) }
    }

    fun onSuccess(apkPath: String, applicationId: String? = null) {
        _lastBuild.update { it.copy(status = "success", apkPath = apkPath, applicationId = applicationId) }
    }

    fun onFailure(error: String) {
        _lastBuild.update { it.copy(status = "failed", error = error) }
    }
}
