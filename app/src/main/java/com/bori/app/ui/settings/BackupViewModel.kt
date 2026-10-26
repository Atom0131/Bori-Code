package com.bori.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.backup.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
) : ViewModel() {

    sealed interface BackupState {
        data object Idle : BackupState
        data object InProgress : BackupState
        data class Success(val message: String) : BackupState
        data class Error(val message: String) : BackupState
    }

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    fun exportBackup(uri: Uri, password: CharArray) {
        viewModelScope.launch {
            _state.value = BackupState.InProgress
            try {
                runCatching { backupManager.exportToUri(uri, password) }
                    .onSuccess { _state.value = BackupState.Success("Backup saved successfully.") }
                    .onFailure { _state.value = BackupState.Error("Export failed: ${it.message}") }
            } finally {
                password.fill(' ')
            }
        }
    }

    fun importBackup(uri: Uri, password: CharArray) {
        viewModelScope.launch {
            _state.value = BackupState.InProgress
            try {
                runCatching { backupManager.importFromUri(uri, password) }
                    .onSuccess { _state.value = BackupState.Success("Restore complete.") }
                    .onFailure { _state.value = BackupState.Error("Import failed: ${it.message}") }
            } finally {
                password.fill(' ')
            }
        }
    }

    fun resetState() {
        _state.value = BackupState.Idle
    }
}
