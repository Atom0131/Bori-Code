package com.bori.app.ui.editor

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.build.DiagnosticsStateHolder
import com.bori.app.data.prefs.EditorSnapshot
import com.bori.app.data.prefs.SessionStateStore
import com.bori.app.data.project.ProjectStateHolder
import com.bori.core.build.VersionCatalogParser
import com.bori.core.build.model.Diagnostic
import com.bori.core.build.tools.ToolsManager
import com.bori.core.editor.ClasspathIndex
import com.bori.core.editor.EditorController
import com.bori.core.project.ProjectRepository
import com.bori.core.project.model.Project
import com.bori.core.editor.EditorDiagnostic
import com.bori.core.editor.KotlinSymbolExtractor
import com.bori.core.editor.SymbolEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Manages the file tree and coordinates file open / save / CRUD operations.
 *
 * The file tree root is determined by [ProjectStateHolder.activeProject]; if no
 * project is active it falls back to the global Bori Code projects directory.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    application: Application,
    private val editorController: EditorController,
    private val projectStateHolder: ProjectStateHolder,
    private val diagnosticsStateHolder: DiagnosticsStateHolder,
    private val projectRepository: ProjectRepository,
    private val toolsManager: ToolsManager,
    private val sessionState: SessionStateStore,
    private val gitRepository: com.bori.core.project.git.GitRepository,
) : AndroidViewModel(application) {

    /** The active project's git branch, shown next to its name; null when it isn't a repo. */
    private val _currentBranch = MutableStateFlow<String?>(null)
    val currentBranch: StateFlow<String?> = _currentBranch.asStateFlow()

    /** Re-reads the branch (an agent in the terminal may have switched it). */
    fun refreshBranch() {
        val root = projectStateHolder.activeProject.value?.rootPath?.takeIf { it.isNotBlank() }
        viewModelScope.launch(Dispatchers.IO) {
            _currentBranch.value = root?.let { gitRepository.currentBranch(File(it)) }
        }
    }

    /** App-specific storage (Android/data/…/files); readable without any permission. */
    private val appFilesDir: File? = application.getExternalFilesDir(null)

    /** Global fallback root when no project is active (the projects folder). */
    private val globalRoot: File = ProjectRepository.PROJECTS_ROOT

    private val projectRoot: File
        get() = projectStateHolder.activeProject.value
            ?.let { File(it.rootPath) }
            ?: globalRoot

    private val _fileTree = MutableStateFlow<List<FileNode>>(emptyList())
    val fileTree: StateFlow<List<FileNode>> = _fileTree.asStateFlow()

    private val _expandedPaths = MutableStateFlow<Set<String>>(emptySet())
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    /** One-shot events that [EditorScreen] should handle. */
    private val _events = MutableSharedFlow<EditorUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EditorUiEvent> = _events.asSharedFlow()

    /** Current build diagnostics — forwarded from [DiagnosticsStateHolder]. */
    val diagnostics: StateFlow<List<Diagnostic>> = diagnosticsStateHolder.diagnostics

    private val _currentFileSymbols = MutableStateFlow<List<SymbolEntry>>(emptyList())
    val currentFileSymbols: StateFlow<List<SymbolEntry>> = _currentFileSymbols.asStateFlow()

    private val _userDependencies = MutableStateFlow<List<String>>(emptyList())
    val userDependencies: StateFlow<List<String>> = _userDependencies.asStateFlow()

    val allProjects: StateFlow<List<Project>> = projectRepository.projects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The user's word-wrap choice; null means automatic (wrap on narrow screens). */
    val wordWrap: StateFlow<Boolean?> = sessionState.wordWrap
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setWordWrap(wrap: Boolean) {
        viewModelScope.launch { sessionState.setWordWrap(wrap) }
    }

    init {
        // Reopen the project and files from before Android ended the app, then keep them current.
        viewModelScope.launch {
            if (projectStateHolder.activeProject.value == null) restoreProject()
            launch {
                projectStateHolder.activeProject.collect { sessionState.saveActiveProject(it?.rootPath) }
            }
            if (editorController.tabState.value.tabs.isEmpty()) restoreTabs()
            // The cursor counts too, so the restored file opens where you were.
            editorController.tabState
                .combine(editorController.currentState) { state, _ -> state }
                .debounce(1_000L)
                .collect { state ->
                    sessionState.saveEditor(
                        EditorSnapshot(
                            paths = state.tabs.map { it.path },
                            activeIndex = state.activeIndex,
                            cursor = state.activeTab?.engine?.currentState?.cursorOffset ?: 0,
                        ),
                    )
                }
        }

        // Refresh tree and dependency list whenever the active project changes.
        viewModelScope.launch {
            projectStateHolder.activeProject.collect { project ->
                _expandedPaths.value = emptySet()
                refreshFileTree()
                _userDependencies.value = readUserDependencies()
                rebuildClasspathIndex(project)
            }
        }

        // A coding CLI finished a chat turn: show what it changed.
        viewModelScope.launch {
            projectStateHolder.filesChanged.collect {
                reloadTreeKeepingExpanded()
                editorController.reloadChangedFiles()
            }
        }

        // Push diagnostics into open editor tabs whenever they update.
        viewModelScope.launch {
            diagnosticsStateHolder.diagnostics.collect { diags ->
                val byPath: Map<String, List<EditorDiagnostic>> = diags
                    .groupBy { it.filePath }
                    .mapValues { (_, list) ->
                        list.map { d ->
                            EditorDiagnostic(
                                line = d.line,
                                column = d.column,
                                severity = if (d.severity == Diagnostic.Severity.ERROR)
                                    EditorDiagnostic.Severity.ERROR
                                else
                                    EditorDiagnostic.Severity.WARNING,
                                message = d.message,
                            )
                        }
                    }
                editorController.applyAllDiagnostics(byPath)
            }
        }

        // Extract symbols from the active Kotlin file and push to completion provider.
        viewModelScope.launch {
            editorController.currentState
                .debounce(500L)
                .distinctUntilChangedBy { it?.content }
                .collect { state ->
                    if (state != null && state.language == "kotlin") {
                        val symbols = withContext(Dispatchers.Default) {
                            KotlinSymbolExtractor.extract(state.content, state.filePath ?: "")
                        }
                        _currentFileSymbols.value = symbols
                        editorController.tabState.value.activeTab?.engine?.updateSymbols(symbols)
                    } else {
                        _currentFileSymbols.value = emptyList()
                    }
                }
        }
    }

    // ── User dependencies ─────────────────────────────────────────────────────

    fun addDependency(coord: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val gradleFile = findGradleFile() ?: return@launch
            val line = "    implementation(\"$coord\")"
            val text = gradleFile.readText()
            val insertPoint = text.indexOf("dependencies {")
            val updated = if (insertPoint >= 0) {
                val afterBrace = text.indexOf('\n', insertPoint) + 1
                text.substring(0, afterBrace) + line + "\n" + text.substring(afterBrace)
            } else {
                text + "\ndependencies {\n$line\n}\n"
            }
            gradleFile.writeText(updated)
            _userDependencies.value = readUserDependencies()
        }
    }

    fun removeDependency(coord: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val gradleFile = findGradleFile() ?: return@launch
            val text = gradleFile.readText()
            val updated = text.lines()
                .filterNot { line ->
                    line.contains("implementation") && line.contains(coord)
                }
                .joinToString("\n")
            gradleFile.writeText(updated)
            _userDependencies.value = readUserDependencies()
        }
    }

    private fun readUserDependencies(): List<String> {
        val gradleFile = findGradleFile() ?: return emptyList()
        return try {
            val text = gradleFile.readText()
            val catalog = VersionCatalogParser.parse(File(projectRoot, "gradle/libs.versions.toml"))
            val inline = Regex("""implementation\s*\(\s*["']([^"']+:[^"']+:[^"']+)["']\s*\)""")
                .findAll(text)
                .map { it.groupValues[1].trim() }
                .filter { it.count { c -> c == ':' } == 2 }
            val catalogRefs = Regex("""implementation\s*\(\s*libs\.([a-zA-Z0-9._-]+)\s*\)""")
                .findAll(text)
                .mapNotNull { VersionCatalogParser.resolve(it.groupValues[1], catalog) }
            (inline + catalogRefs).distinct().toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findGradleFile(): File? =
        listOf(
            File(projectRoot, "app/build.gradle.kts"),
            File(projectRoot, "app/build.gradle"),
        ).firstOrNull { it.exists() }

    // ── File tree ────────────────────────────────────────────────────────────

    fun refreshFileTree() {
        viewModelScope.launch(Dispatchers.IO) {
            // Only projects outside app-specific storage need "All files access".
            val inAppStorage = appFilesDir != null &&
                projectRoot.canonicalPath.startsWith(appFilesDir.canonicalPath)
            if (!inAppStorage && !Environment.isExternalStorageManager()) {
                _events.emit(EditorUiEvent.RequestStoragePermission)
                return@launch
            }
            projectRoot.mkdirs()
            _fileTree.value = buildTree(projectRoot)
        }
    }

    /** Rebuilds the tree from disk, reloading the folders the user has open (parents first). */
    private fun reloadTreeKeepingExpanded() {
        viewModelScope.launch(Dispatchers.IO) {
            var tree = buildTree(projectRoot)
            for (path in _expandedPaths.value.sortedBy { it.length }) {
                if (File(path).isDirectory) tree = updateChildren(tree, path, buildTree(File(path)))
            }
            _fileTree.value = tree
        }
    }

    fun toggleExpand(node: FileNode) {
        if (!node.isDirectory) return
        viewModelScope.launch(Dispatchers.IO) { resolveActiveProjectFromDir(File(node.path)) }
        val current = _expandedPaths.value.toMutableSet()
        if (node.path in current) {
            current.remove(node.path)
        } else {
            current.add(node.path)
            if (node.children == null) {
                viewModelScope.launch(Dispatchers.IO) {
                    val children = buildTree(File(node.path))
                    _fileTree.value = updateChildren(_fileTree.value, node.path, children)
                }
            }
        }
        _expandedPaths.value = current
    }

    private suspend fun restoreProject() {
        val root = sessionState.activeProject() ?: return
        val project = projectRepository.projects.first().firstOrNull { it.rootPath == root } ?: return
        if (projectStateHolder.activeProject.value == null) projectStateHolder.setActiveProject(project)
    }

    private suspend fun restoreTabs() {
        val snapshot = sessionState.editor() ?: return
        val files = withContext(Dispatchers.IO) {
            snapshot.paths.mapNotNull { path ->
                runCatching { path to File(path).readText() }.getOrNull()
            }
        }
        if (files.isEmpty() || editorController.tabState.value.tabs.isNotEmpty()) return
        files.forEach { (path, content) -> editorController.openFile(getApplication(), path, content) }
        val active = snapshot.paths.getOrNull(snapshot.activeIndex)
            ?.let { path -> editorController.tabState.value.tabs.indexOfFirst { it.path == path } }
            ?.takeIf { it >= 0 }
            ?: editorController.tabState.value.tabs.lastIndex
        editorController.selectTab(active)
        editorController.tabState.value.activeTab?.engine?.setCursor(snapshot.cursor)
    }

    // ── File open / save ─────────────────────────────────────────────────────

    fun openFile(node: FileNode) {
        if (node.isDirectory) return
        viewModelScope.launch(Dispatchers.IO) {
            resolveActiveProject(node.path)
            val content = runCatching { File(node.path).readText() }.getOrElse {
                _events.emit(EditorUiEvent.ShowError("Cannot read ${node.name}: ${it.message}"))
                return@launch
            }
            withContext(Dispatchers.Main) {
                editorController.openFile(getApplication(), node.path, content)
            }
        }
    }

    fun formatCurrentFile() {
        val tab = editorController.tabState.value.activeTab ?: return
        val state = tab.engine.currentState
        val formatted = com.bori.core.editor.CodeFormatter.format(state.content, state.language)
        if (formatted != state.content) {
            tab.engine.setContent(formatted)
        }
    }

    fun saveCurrentFile() {
        val tab = editorController.tabState.value.activeTab ?: return
        val state = tab.engine.currentState
        val path = state.filePath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { File(path).writeText(state.content) }
                .onSuccess { editorController.markDirty(tab.id, false) }
                .onFailure { _events.emit(EditorUiEvent.ShowError("Save failed: ${it.message}")) }
        }
    }

    fun closeTab(index: Int) {
        editorController.closeTab(index)
    }

    // ── File CRUD ────────────────────────────────────────────────────────────

    fun createFile(parent: FileNode, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val file = File(parent.path, name)
                file.parentFile?.mkdirs()
                file.createNewFile()
            }.onFailure {
                _events.emit(EditorUiEvent.ShowError("Cannot create file: ${it.message}"))
            }
            refreshFileTree()
        }
    }

    fun createFolder(parent: FileNode, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                File(parent.path, name).mkdirs()
            }.onFailure {
                _events.emit(EditorUiEvent.ShowError("Cannot create folder: ${it.message}"))
            }
            refreshFileTree()
        }
    }

    fun renameNode(node: FileNode, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldFile = File(node.path)
            val newFile = File(oldFile.parent ?: return@launch, newName)
            val renamed = oldFile.renameTo(newFile)
            if (!renamed) {
                _events.emit(EditorUiEvent.ShowError("Rename failed"))
            } else {
                // Close tab if it was open, then reopen with new path.
                val openTab = editorController.tabState.value.tabs
                    .firstOrNull { it.path == node.path }
                if (openTab != null) {
                    val index = editorController.tabState.value.tabs.indexOf(openTab)
                    editorController.closeTab(index)
                    if (!node.isDirectory) {
                        val content = runCatching { newFile.readText() }.getOrDefault("")
                        editorController.openFile(getApplication(), newFile.absolutePath, content)
                    }
                }
            }
            refreshFileTree()
        }
    }

    fun deleteNode(node: FileNode) {
        viewModelScope.launch(Dispatchers.IO) {
            // Close any open tabs under this path first.
            editorController.tabState.value.tabs
                .filter { it.path == node.path || it.path.startsWith(node.path + "/") }
                .forEach { tab ->
                    val index = editorController.tabState.value.tabs.indexOf(tab)
                    if (index >= 0) editorController.closeTab(index)
                }
            runCatching { File(node.path).deleteRecursively() }
                .onFailure { _events.emit(EditorUiEvent.ShowError("Delete failed: ${it.message}")) }
            refreshFileTree()
        }
    }

    /**
     * Opens the file referenced by [d] (if not already open) and jumps the cursor to the
     * error location. Called when the user taps a row in the Problems panel.
     */
    fun navigateToDiagnostic(d: Diagnostic) {
        viewModelScope.launch(Dispatchers.IO) {
            val alreadyOpen = editorController.tabState.value.tabs.any { it.path == d.filePath }
            if (!alreadyOpen) {
                val content = runCatching { File(d.filePath).readText() }.getOrElse { return@launch }
                editorController.openFile(getApplication(), d.filePath, content)
            }
            editorController.jumpToLine(d.filePath, d.line, d.column)
        }
    }

    // ── Classpath index + import quick-fix ───────────────────────────────────

    private fun rebuildClasspathIndex(project: Project?) {
        viewModelScope.launch(Dispatchers.IO) {
            val jars = buildList {
                if (toolsManager.androidxClassesJar.exists()) add(toolsManager.androidxClassesJar)
                if (project != null) {
                    val userLibsJar = File(
                        getApplication<Application>().filesDir,
                        "builds/${project.name}/user-libs/user-libs.jar",
                    )
                    if (userLibsJar.exists()) add(userLibsJar)
                }
            }
            ClasspathIndex.rebuild(jars)
        }
    }

    fun applyImportFix(filePath: String, fqn: String) {
        viewModelScope.launch { editorController.insertImport(filePath, fqn) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Checks expanded paths and globalRoot subdirs for a project root, then sets it active.
     * Called by the build FAB to ensure a project is active before starting a build.
     */
    suspend fun syncActiveProjectIfNeeded() = withContext(Dispatchers.IO) {
        if (projectStateHolder.activeProject.value != null) return@withContext
        // First try: find a project root among currently expanded directories.
        _expandedPaths.value
            .map { File(it) }
            .firstOrNull { dir ->
                File(dir, "settings.gradle").exists() || File(dir, "settings.gradle.kts").exists()
            }
            ?.let { dir ->
                val project = projectRepository.upsertFromPath(dir.absolutePath)
                projectStateHolder.setActiveProject(project)
                return@withContext
            }
        // Second try: scan globalRoot for any valid project.
        globalRoot.listFiles()
            ?.firstOrNull { dir ->
                dir.isDirectory &&
                    (File(dir, "settings.gradle").exists() || File(dir, "settings.gradle.kts").exists())
            }
            ?.let { dir ->
                val project = projectRepository.upsertFromPath(dir.absolutePath)
                projectStateHolder.setActiveProject(project)
            }
    }

    /** Walks up from the parent of [filePath] to find a project root (has settings.gradle). */
    private suspend fun resolveActiveProject(filePath: String) {
        resolveActiveProjectFromDir(File(filePath).parentFile ?: return)
    }

    fun selectProject(project: Project) {
        projectStateHolder.setActiveProject(project)
    }

    /** Walks up from [startDir] to find a project root (has settings.gradle) and sets it active. */
    private suspend fun resolveActiveProjectFromDir(startDir: File) {
        if (projectStateHolder.activeProject.value != null) return
        var dir: File? = startDir
        while (dir != null && dir.canonicalPath != globalRoot.canonicalPath) {
            if (File(dir, "settings.gradle").exists() || File(dir, "settings.gradle.kts").exists()) {
                val project = projectRepository.upsertFromPath(dir.absolutePath)
                projectStateHolder.setActiveProject(project)
                return
            }
            dir = dir.parentFile
        }
    }

    private suspend fun buildTree(dir: File): List<FileNode> = withContext(Dispatchers.IO) {
        dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map { file ->
                FileNode(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = file.isDirectory,
                    children = null,
                )
            }
            ?: emptyList()
    }

    private fun updateChildren(
        nodes: List<FileNode>,
        targetPath: String,
        newChildren: List<FileNode>,
    ): List<FileNode> = nodes.map { node ->
        when {
            node.path == targetPath -> node.copy(children = newChildren)
            node.isDirectory && node.children != null ->
                node.copy(children = updateChildren(node.children, targetPath, newChildren))
            else -> node
        }
    }
}

sealed interface EditorUiEvent {
    data object RequestStoragePermission : EditorUiEvent
    data class ShowError(val message: String) : EditorUiEvent
}

enum class SidebarPanel { EXPLORER, SEARCH, GIT }
enum class BottomTab { PROBLEMS, OUTLINE, DEPENDENCIES }
