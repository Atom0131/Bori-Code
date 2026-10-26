package com.bori.app.ui.projects

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.project.ProjectStateHolder
import com.bori.core.project.ProjectRepository
import com.bori.core.project.git.GitRepository
import com.bori.core.project.model.Project
import com.bori.core.project.model.ProjectTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

sealed interface ImportState {
    data object Idle : ImportState
    data object Loading : ImportState
    data class Error(val message: String) : ImportState
}

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val projectStateHolder: ProjectStateHolder,
    private val gitRepository: GitRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val projects: StateFlow<List<Project>> = repository.projects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProject: StateFlow<Project?> = projectStateHolder.activeProject

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.syncFromFilesystem()
        }
    }

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    fun createProject(
        name: String,
        packageName: String,
        minSdk: Int,
        template: ProjectTemplate,
        onCreated: (Project) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val project = runCatching {
                repository.createProject(
                    Project(
                        name = name,
                        packageName = packageName,
                        minSdk = minSdk,
                        template = template,
                        rootPath = "",
                    )
                )
            }.getOrElse {
                _error.emit("Project creation failed: ${it.message}")
                return@launch
            }
            withContext(Dispatchers.Main) { onCreated(project) }
        }
    }

    fun importFromGitHub(url: String, token: String, onComplete: (Project) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _importState.value = ImportState.Loading
            try {
                val repoName = url.trimEnd('/')
                    .substringAfterLast('/')
                    .removeSuffix(".git")
                    .ifBlank { "imported" }
                val targetDir = File(ProjectRepository.PROJECTS_ROOT, repoName)
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()

                gitRepository.clone(url, targetDir, token)

                val project = repository.upsertFromPath(targetDir.absolutePath)
                _importState.value = ImportState.Idle
                withContext(Dispatchers.Main) { onComplete(project) }
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Clone failed")
            }
        }
    }

    fun importFromZip(uri: Uri, onComplete: (Project) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _importState.value = ImportState.Loading
            try {
                val displayName = resolveFileName(uri) ?: "imported"
                val folderName = displayName.removeSuffix(".zip").removeSuffix(".ZIP")
                val targetDir = File(ProjectRepository.PROJECTS_ROOT, folderName)
                targetDir.mkdirs()

                // Copy URI stream → temp file so ZipFile (random-access) can read it
                val tempFile = File(ProjectRepository.PROJECTS_ROOT, "._import_temp.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("Cannot open ZIP stream")

                // Detect common top-level prefix (GitHub ZIPs have e.g. repo-main/)
                val commonPrefix = ZipFile(tempFile).use { zip ->
                    val names = zip.entries().asSequence().map { it.name }.toList()
                    val firstComponents = names.map { it.substringBefore('/') }.toSet()
                    if (firstComponents.size == 1 && firstComponents.first().isNotEmpty())
                        firstComponents.first() + "/"
                    else ""
                }

                // Extract with prefix stripping + path-traversal guard
                ZipFile(tempFile).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        var name = entry.name
                        if (name.startsWith(commonPrefix)) name = name.removePrefix(commonPrefix)
                        if (name.isEmpty()) return@forEach
                        val target = File(targetDir, name)
                        if (!target.canonicalPath.startsWith(targetDir.canonicalPath)) return@forEach
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { inp ->
                                target.outputStream().use { out -> inp.copyTo(out) }
                            }
                        }
                    }
                }
                tempFile.delete()

                val project = repository.upsertFromPath(targetDir.absolutePath)
                _importState.value = ImportState.Idle
                withContext(Dispatchers.Main) { onComplete(project) }
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Import failed")
            }
        }
    }

    fun clearImportError() {
        _importState.value = ImportState.Idle
    }

    fun openProject(project: Project) {
        projectStateHolder.setActiveProject(project)
    }

    fun deleteProject(project: Project) {
        viewModelScope.launch(Dispatchers.IO) {
            if (projectStateHolder.activeProject.value?.id == project.id) {
                projectStateHolder.clearActiveProject()
            }
            repository.deleteProject(project.id)
        }
    }

    private fun resolveFileName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else null
        }
}
