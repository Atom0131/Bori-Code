package com.bori.app.ui.editor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.project.ProjectStateHolder
import com.bori.core.editor.EditorController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SearchMatch(val line: Int, val content: String)

data class FileSearchResult(
    val filePath: String,
    val fileName: String,
    val matches: List<SearchMatch>,
)

private val TEXT_EXTENSIONS = setOf(
    "kt", "java", "xml", "gradle", "kts", "json", "md", "txt",
    "properties", "toml", "pro", "yaml", "yml",
)
private const val MAX_FILE_SIZE = 1_000_000L
private const val MAX_FILES = 50
private const val MAX_MATCHES_PER_FILE = 20

@HiltViewModel
class FindInFilesViewModel @Inject constructor(
    private val projectStateHolder: ProjectStateHolder,
    private val editorController: EditorController,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _results = MutableStateFlow<List<FileSearchResult>>(emptyList())
    val results: StateFlow<List<FileSearchResult>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    fun search(query: String, caseSensitive: Boolean, useRegex: Boolean) {
        if (query.isBlank()) {
            _results.value = emptyList()
            return
        }
        val root = projectStateHolder.activeProject.value?.rootPath?.let { File(it) } ?: return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            _results.value = withContext(Dispatchers.IO) {
                val flags = if (!caseSensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
                val pattern = runCatching {
                    if (useRegex) Regex(query, flags)
                    else Regex(Regex.escape(query), flags)
                }.getOrNull() ?: return@withContext emptyList()

                root.walkTopDown()
                    .filter { f ->
                        f.isFile &&
                            f.extension in TEXT_EXTENSIONS &&
                            f.length() <= MAX_FILE_SIZE &&
                            !f.path.contains("${File.separator}.git${File.separator}")
                    }
                    .take(500)
                    .mapNotNull { file ->
                        val lines = runCatching { file.readLines() }.getOrNull() ?: return@mapNotNull null
                        val matches = lines.mapIndexedNotNull { idx, line ->
                            if (pattern.containsMatchIn(line))
                                SearchMatch(idx + 1, line.trim().take(120))
                            else null
                        }.take(MAX_MATCHES_PER_FILE)
                        if (matches.isNotEmpty())
                            FileSearchResult(file.absolutePath, file.name, matches)
                        else null
                    }
                    .take(MAX_FILES)
                    .toList()
            }
            _isSearching.value = false
        }
    }

    fun clear() {
        searchJob?.cancel()
        _results.value = emptyList()
        _isSearching.value = false
    }

    fun openResult(filePath: String, line: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching { File(filePath).readText() }.getOrElse { return@launch }
            withContext(Dispatchers.Main) {
                editorController.openFile(context, filePath, content)
            }
            editorController.jumpToLine(filePath, line, 0)
        }
    }
}
