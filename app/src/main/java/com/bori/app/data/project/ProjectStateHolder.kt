package com.bori.app.data.project

import com.bori.core.project.model.Project
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that holds the currently active project across the app.
 *
 * [ProjectsViewModel] sets the active project when the user opens one.
 * [EditorViewModel] reads it to root the file tree at the correct directory.
 */
@Singleton
class ProjectStateHolder @Inject constructor() {

    private val _activeProject = MutableStateFlow<Project?>(null)
    val activeProject: StateFlow<Project?> = _activeProject.asStateFlow()

    fun setActiveProject(project: Project) {
        _activeProject.value = project
    }

    fun clearActiveProject() {
        _activeProject.value = null
    }

    private val _filesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when something outside the editor (a coding CLI) may have changed project files. */
    val filesChanged: SharedFlow<Unit> = _filesChanged.asSharedFlow()

    fun notifyFilesChanged() {
        _filesChanged.tryEmit(Unit)
    }
}
