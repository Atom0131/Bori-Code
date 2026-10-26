package com.bori.core.ai.local

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device models Bori Code can download and run with LiteRT-LM. [id] is also the model's
 * ID in [com.bori.core.ai.model.ModelCatalog]. All support up to 32k tokens of context.
 *
 * @param fileBytes exact download size, used by [ModelAdvisor] for the storage check.
 * @param minRamGb below this the model is unlikely to load at all.
 * @param goodRamGb at or above this it runs comfortably alongside the rest of the phone.
 */
enum class LocalModel(
    val id: String,
    val label: String,
    val fileName: String,
    val url: String,
    val sizeLabel: String,
    val description: String,
    val fileBytes: Long,
    val minRamGb: Int,
    val goodRamGb: Int,
) {
    GEMMA_4_E2B(
        id = "gemma-4-e2b-it",
        label = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeLabel = "2.6 GB",
        description = "Smaller and faster. Works on most phones.",
        fileBytes = 2_588_147_712,
        minRamGb = 4,
        goodRamGb = 6,
    ),
    GEMMA_4_E4B(
        id = "gemma-4-e4b-it",
        label = "Gemma 4 E4B",
        fileName = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sizeLabel = "3.7 GB",
        description = "Smarter but slower than E2B. Needs a phone with 8 GB+ RAM.",
        fileBytes = 3_659_530_240,
        minRamGb = 6,
        goodRamGb = 10,
    ),
    GEMMA_4_12B(
        id = "gemma-4-12b-it",
        label = "Gemma 4 12B",
        fileName = "gemma-4-12B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-12B-it-litert-lm/resolve/main/gemma-4-12B-it.litertlm",
        sizeLabel = "6.9 GB",
        description = "Best quality on a phone, but slow. Needs a flagship with 16 GB RAM.",
        fileBytes = 6_883_278_368,
        minRamGb = 12,
        goodRamGb = 16,
    );

    companion object {
        fun byId(id: String?): LocalModel? = entries.firstOrNull { it.id == id }
    }
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val bytesTotal: Long) : DownloadState()
    object Ready : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Downloads and tracks the on-device models. Everything here (state, path, download,
 * delete) applies to the [selected] model; switching models with [select] re-evaluates
 * the state for the newly selected file. Only one download runs at a time.
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val PREFS_NAME = "model_download_prefs"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_HF_TOKEN = "hf_token"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_MANUAL_NAME = "manual_specs_name"
        private const val KEY_MANUAL_RAM = "manual_specs_ram_gb"
        private const val KEY_MANUAL_STORAGE = "manual_specs_storage_gb"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    private val _selected = MutableStateFlow(
        LocalModel.byId(prefs.getString(KEY_SELECTED_MODEL, null)) ?: LocalModel.GEMMA_4_E2B
    )
    /** The model that downloads, deletes and inference apply to. */
    val selected: StateFlow<LocalModel> = _selected.asStateFlow()

    private val modelFile: File
        get() = File(modelsDir, _selected.value.fileName)

    val modelPath: String
        get() = modelFile.absolutePath

    private val _downloadState = MutableStateFlow<DownloadState>(
        if (isModelReady()) DownloadState.Ready else DownloadState.Idle
    )
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    private var activeDownloadId: Long
        get() = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        set(v) = prefs.edit().putLong(KEY_DOWNLOAD_ID, v).apply()

    /** Reads this phone's specs for [ModelAdvisor]. */
    fun scanSpecs(): DeviceSpecs = DeviceSpecs.scan(context)

    /** Specs the user typed in instead of the scan, or null to use the scan. */
    var manualSpecs: DeviceSpecs?
        get() = if (!prefs.contains(KEY_MANUAL_RAM)) null else DeviceSpecs(
            name = prefs.getString(KEY_MANUAL_NAME, null).orEmpty().ifBlank { "My phone" },
            ramGb = prefs.getFloat(KEY_MANUAL_RAM, 0f).toDouble(),
            freeStorageGb = prefs.getFloat(KEY_MANUAL_STORAGE, 0f).toDouble(),
            manual = true,
        )
        set(v) {
            prefs.edit().apply {
                if (v == null) {
                    remove(KEY_MANUAL_NAME); remove(KEY_MANUAL_RAM); remove(KEY_MANUAL_STORAGE)
                } else {
                    putString(KEY_MANUAL_NAME, v.name)
                    putFloat(KEY_MANUAL_RAM, v.ramGb.toFloat())
                    putFloat(KEY_MANUAL_STORAGE, v.freeStorageGb.toFloat())
                }
            }.apply()
        }

    var hfToken: String
        get() = prefs.getString(KEY_HF_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HF_TOKEN, v.trim()).apply()

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (id != activeDownloadId) return
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = dm.query(query)
            if (cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                when (cursor.getInt(statusIdx)) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        activeDownloadId = -1L
                        _downloadState.value = DownloadState.Ready
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val reason = cursor.getInt(reasonIdx)
                        activeDownloadId = -1L
                        _downloadState.value = if (isModelReady()) DownloadState.Ready
                            else DownloadState.Error(downloadFailedMessage(reason))
                    }
                }
            }
            cursor.close()
        }
    }

    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(downloadCompleteReceiver, filter)
        }

        // Restore in-progress state on process restart
        if (activeDownloadId != -1L && !isModelReady()) {
            _downloadState.value = DownloadState.Downloading(0, 0L, 0L)
        }
    }

    fun isModelReady(): Boolean = isDownloaded(_selected.value)

    fun isDownloaded(model: LocalModel): Boolean {
        val f = File(modelsDir, model.fileName)
        return f.exists() && f.length() > 10 * 1024 * 1024
    }

    /** Makes [model] the active one. Ignored while a download is running. */
    fun select(model: LocalModel) {
        if (model == _selected.value || activeDownloadId != -1L) return
        _selected.value = model
        prefs.edit().putString(KEY_SELECTED_MODEL, model.id).apply()
        _downloadState.value = if (isModelReady()) DownloadState.Ready else DownloadState.Idle
    }

    fun markCorruptAndDelete(reason: String) {
        modelFile.delete()
        activeDownloadId = -1L
        _downloadState.value = DownloadState.Error("Model file corrupt — please redownload. ($reason)")
    }

    fun startDownload() {
        if (isModelReady()) { _downloadState.value = DownloadState.Ready; return }
        if (activeDownloadId != -1L) return // already in progress

        val model = _selected.value
        val token = hfToken
        val url = if (token.isNotEmpty()) "${model.url}?token=$token" else model.url

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("${model.label} model")
            .setDescription("Downloading local AI model (~${model.sizeLabel})")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "models", model.fileName)
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )
        try {
            activeDownloadId = dm.enqueue(request)
            _downloadState.value = DownloadState.Downloading(0, 0L, 0L)
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Error("Could not start download: ${e.message}")
        }
    }

    fun cancelDownload() {
        val id = activeDownloadId
        if (id == -1L) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.remove(id)
        activeDownloadId = -1L
        _downloadState.value = DownloadState.Idle
    }

    fun deleteModel() {
        cancelDownload()
        modelFile.delete()
        _downloadState.value = DownloadState.Idle
    }

    fun refreshProgress() {
        val id = activeDownloadId
        if (id == -1L) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        if (cursor.moveToFirst()) {
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
            val status = cursor.getInt(statusIdx)
            val reason = cursor.getInt(reasonIdx)
            when (status) {
                DownloadManager.STATUS_FAILED -> {
                    activeDownloadId = -1L
                    _downloadState.value = if (isModelReady()) DownloadState.Ready
                        else DownloadState.Error(downloadFailedMessage(reason))
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    activeDownloadId = -1L
                    _downloadState.value = DownloadState.Ready
                }
                DownloadManager.STATUS_PAUSED -> {
                    val reasonText = when (reason) {
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi (file too large for mobile data)"
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
                        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Retrying…"
                        else -> "Paused (reason $reason)"
                    }
                    _downloadState.value = DownloadState.Error(reasonText)
                }
                else -> {
                    val downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val downloaded = cursor.getLong(downloadedIdx)
                    val total = cursor.getLong(totalIdx)
                    val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    _downloadState.value = DownloadState.Downloading(percent, downloaded, total)
                }
            }
        }
        cursor.close()
    }

    fun storageBytes(): Long = modelFile.length()

    private fun downloadFailedMessage(reason: Int): String = when (reason) {
        401 -> "Authentication failed (401) — this model is publicly available. If you have a token entered in Settings, try clearing it and retrying."
        403 -> "Access denied (403) — try clearing the HuggingFace Token field in Settings and retrying."
        404 -> "Model file not found (404) — the download URL may have changed."
        else -> "Download failed (code $reason)"
    }
}
