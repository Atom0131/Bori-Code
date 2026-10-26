package com.bori.app.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.project.ProjectStateHolder
import com.bori.app.diagnostics.AppLogReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val MAX_ENTRIES = 1000

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val logReader: AppLogReader,
    private val projectStateHolder: ProjectStateHolder,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<AppLogReader.LogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogReader.LogEntry>> = _entries.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _readLogsGranted = MutableStateFlow(logReader.isReadLogsGranted())
    val readLogsGranted: StateFlow<Boolean> = _readLogsGranted.asStateFlow()

    val activePackageName: StateFlow<String?> get() =
        projectStateHolder.activeProject.let {
            MutableStateFlow(it.value?.packageName)
        }

    private var streamJob: Job? = null

    init {
        startStreaming()
        // Restart stream when active project changes so "App" filter auto-targets the new project
        viewModelScope.launch {
            projectStateHolder.activeProject.collect { startStreaming() }
        }
    }

    fun startStreaming() {
        streamJob?.cancel()
        _entries.value = emptyList()
        _readLogsGranted.value = logReader.isReadLogsGranted()
        val packageName = projectStateHolder.activeProject.value?.packageName
        val source = if (packageName != null && logReader.isReadLogsGranted())
            logReader.streamForPackage(packageName)
        else
            logReader.streamAll()

        streamJob = viewModelScope.launch {
            source.collect { entry ->
                _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
            }
        }
    }

    /** Legacy one-shot load kept for the refresh action. */
    fun load() {
        startStreaming()
    }
}
