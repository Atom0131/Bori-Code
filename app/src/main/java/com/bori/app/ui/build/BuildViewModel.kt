package com.bori.app.ui.build

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.build.BuildRunner
import com.bori.app.data.build.KeystoreRepository
import com.bori.app.data.project.ProjectStateHolder
import com.bori.core.build.model.BuildEvent
import com.bori.core.build.model.BuildVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BuildViewModel @Inject constructor(
    private val buildRunner: BuildRunner,
    private val projectStateHolder: ProjectStateHolder,
    private val keystoreRepository: KeystoreRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val uiState: StateFlow<BuildRunner.UiState> = buildRunner.uiState
    val logs: StateFlow<List<BuildEvent.Log>> = buildRunner.logs

    private val _isInstalled = MutableStateFlow(false)
    val isInstalled: StateFlow<Boolean> = _isInstalled.asStateFlow()

    private val _variant = MutableStateFlow(BuildVariant.DEBUG)
    val variant: StateFlow<BuildVariant> = _variant.asStateFlow()

    /** Release only: build an Android App Bundle (.aab) for Google Play instead of an APK. */
    private val _appBundle = MutableStateFlow(false)
    val appBundle: StateFlow<Boolean> = _appBundle.asStateFlow()

    fun setAppBundle(on: Boolean) {
        if (uiState.value !is BuildRunner.UiState.Building) _appBundle.value = on
    }

    private val _hasCustomKeystore = MutableStateFlow(false)
    val hasCustomKeystore: StateFlow<Boolean> = _hasCustomKeystore.asStateFlow()

    init {
        viewModelScope.launch {
            _hasCustomKeystore.value = keystoreRepository.get() != null
        }
        viewModelScope.launch {
            buildRunner.uiState.collect { if (it is BuildRunner.UiState.Done) refreshInstallState(it.packageName) }
        }
    }

    fun setVariant(v: BuildVariant) {
        if (uiState.value !is BuildRunner.UiState.Building) _variant.value = v
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

    fun startBuild() {
        val project = projectStateHolder.activeProject.value ?: run {
            buildRunner.fail("No active project. Open a file or select a project from the Projects tab.")
            return
        }
        buildRunner.start(project, _variant.value, _appBundle.value)
    }

    fun reset() = buildRunner.reset()
}
