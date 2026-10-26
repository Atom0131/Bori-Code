package com.bori.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.core.ai.local.DeviceSpecs
import com.bori.core.ai.local.DownloadState
import com.bori.core.ai.local.LocalGemmaClient
import com.bori.core.ai.local.LocalModel
import com.bori.core.ai.local.ModelAdvisor
import com.bori.core.ai.local.ModelFit
import com.bori.core.ai.local.ModelDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LocalModelViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager,
    private val gemmaClient: LocalGemmaClient,
) : ViewModel() {

    val downloadState: StateFlow<DownloadState> = downloadManager.downloadState
    val selectedModel: StateFlow<LocalModel> = downloadManager.selected

    fun isDownloaded(model: LocalModel): Boolean = downloadManager.isDownloaded(model)

    private val _specs = MutableStateFlow(downloadManager.manualSpecs ?: DeviceSpecs(name = "", ramGb = 0.0, freeStorageGb = 0.0))
    /** The phone's specs: typed in by the user if they set any, otherwise scanned. */
    val specs: StateFlow<DeviceSpecs> = _specs.asStateFlow()

    /** Advisor rating for every on-device model, best pick marked. */
    val fits: StateFlow<List<ModelFit>> = combine(_specs, downloadManager.downloadState) { specs, _ ->
        val downloaded = LocalModel.entries.filter { downloadManager.isDownloaded(it) }.toSet()
        if (specs.ramGb <= 0.0) emptyList() else ModelAdvisor.recommend(specs, downloaded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Re-reads the phone (free storage changes as models come and go). Ignored while manual specs are set. */
    fun rescan() {
        if (downloadManager.manualSpecs != null) return
        viewModelScope.launch {
            _specs.value = withContext(Dispatchers.IO) { downloadManager.scanSpecs() }
        }
    }

    fun setManualSpecs(name: String, ramGb: Double, freeStorageGb: Double) {
        val manual = DeviceSpecs(name = name.trim(), ramGb = ramGb, freeStorageGb = freeStorageGb, manual = true)
        downloadManager.manualSpecs = manual
        _specs.value = downloadManager.manualSpecs ?: manual
    }

    fun clearManualSpecs() {
        downloadManager.manualSpecs = null
        rescan()
    }

    /** Switches the on-device model; the engine reloads with the new file on next use. */
    fun selectModel(model: LocalModel) {
        if (model == downloadManager.selected.value) return
        gemmaClient.invalidateCache()
        downloadManager.select(model)
    }

    fun getHfToken(): String = downloadManager.hfToken
    fun setHfToken(token: String) { downloadManager.hfToken = token }

    fun startDownload() = downloadManager.startDownload()
    fun cancelDownload() = downloadManager.cancelDownload()
    fun deleteModel() {
        gemmaClient.invalidateCache()
        downloadManager.deleteModel()
    }

    fun storageBytes(): Long = downloadManager.storageBytes()

    init {
        rescan()
        // A finished download or a delete changes free storage
        viewModelScope.launch {
            downloadManager.downloadState.distinctUntilChangedBy { it::class }.collect { rescan() }
        }
        // Poll progress while a download is active
        viewModelScope.launch {
            while (true) {
                if (downloadManager.downloadState.value is DownloadState.Downloading) {
                    downloadManager.refreshProgress()
                }
                delay(1_500)
            }
        }
    }
}
