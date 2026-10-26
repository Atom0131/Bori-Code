package com.bori.app.ui.editor

import com.bori.app.ui.common.ShellContentInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.MoreVert
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.bori.app.ui.build.BuildSheet
import com.bori.app.ui.build.BuildViewModel
import com.bori.app.ui.chat.ChatViewModel
import com.bori.app.ui.theme.isAppInDarkTheme
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LocalContentColor
import com.bori.app.ui.workspace.LocalPaneVisible
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.automirrored.filled.WrapText
import com.bori.app.ui.projects.NewProjectDialog
import com.bori.app.ui.projects.ProjectsViewModel
import com.bori.core.ai.suggestion.InlineSuggestionEngine
import com.bori.core.editor.EditorController
import com.bori.core.project.model.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private enum class ContextMenuAction { NEW_FILE, NEW_FOLDER, RENAME, DELETE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    editorController: EditorController,
    suggestionEngine: InlineSuggestionEngine,
    onViewLogs: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    viewModel: EditorViewModel = hiltViewModel(),
    buildViewModel: BuildViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    findInFilesViewModel: FindInFilesViewModel = hiltViewModel(),
    projectsViewModel: ProjectsViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    // ── Layout state ──────────────────────────────────────────────────────────
    var isSidebarVisible by rememberSaveable { mutableStateOf(true) }
    var sidebarWidthDp by rememberSaveable { mutableStateOf(240) }
    var activeSidebarPanel by rememberSaveable { mutableStateOf(SidebarPanel.EXPLORER) }
    var isBottomPanelVisible by rememberSaveable { mutableStateOf(false) }
    var activeBottomTab by rememberSaveable { mutableStateOf(BottomTab.PROBLEMS) }
    var bottomPanelHeightDp by rememberSaveable { mutableStateOf(200) }
    var showQuickOpen by remember { mutableStateOf(false) }

    // ── Sheet / dialog visibility ─────────────────────────────────────────────
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showBuildSheet by remember { mutableStateOf(false) }
    var showFindInFiles by remember { mutableStateOf(false) }
    var showLayoutPreview by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }

    // ── Context menu state ────────────────────────────────────────────────────
    var contextMenuNode by remember { mutableStateOf<FileNode?>(null) }
    var contextMenuAction by remember { mutableStateOf<ContextMenuAction?>(null) }
    var inputDialogValue by remember { mutableStateOf("") }

    // ── Observed state ────────────────────────────────────────────────────────
    val fileTree by viewModel.fileTree.collectAsState()
    val expandedPaths by viewModel.expandedPaths.collectAsState()
    val tabState by editorController.tabState.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val currentFileSymbols by viewModel.currentFileSymbols.collectAsState()
    val userDependencies by viewModel.userDependencies.collectAsState()
    val editorState by editorController.currentState.collectAsState()
    val activeProject by chatViewModel.activeProject.collectAsState()
    val allProjects by viewModel.allProjects.collectAsStateWithLifecycle()

    val activeFilePath = tabState.activeTab?.path ?: ""
    val isLayoutFile = activeFilePath.contains("/layout/") && activeFilePath.endsWith(".xml")
    val activeEngine = tabState.activeTab?.engine

    // ── LaunchedEffects ───────────────────────────────────────────────────────
    LaunchedEffect(tabState.activeTab?.id) {
        val engine = tabState.activeTab?.engine ?: return@LaunchedEffect
        engine.stateFlow.collect { state ->
            editorController.update(state)
            tabState.activeTab?.let { tab -> editorController.onContentChanged(tab.id, state.content) }
        }
    }

    LaunchedEffect(tabState.activeTab?.id) {
        val engine = tabState.activeTab?.engine ?: return@LaunchedEffect
        suggestionEngine.suggestions(engine.stateFlow).collect { suggestion ->
            engine.setSuggestion(suggestion)
        }
    }

    // Auto-open bottom panel when diagnostics arrive
    LaunchedEffect(diagnostics.isNotEmpty()) {
        if (diagnostics.isNotEmpty()) {
            isBottomPanelVisible = true
            activeBottomTab = BottomTab.PROBLEMS
        }
    }

    LaunchedEffect(currentFileSymbols) {
        chatViewModel.updateSymbols(currentFileSymbols)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EditorUiEvent.RequestStoragePermission -> showPermissionDialog = true
                is EditorUiEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // Show project picker when no project is active.
    if (activeProject == null) {
        ProjectPickerContent(
            projects = allProjects,
            onSelect = viewModel::selectProject,
            projectsViewModel = projectsViewModel,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Storage Access Required") },
            text = {
                Text(
                    "This project is outside Bori Code's own folder. " +
                        "Grant \"All files access\" to browse and edit it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("Cancel") }
            },
        )
    }

    val currentAction = contextMenuAction
    val currentNode = contextMenuNode
    if (currentAction != null && currentNode != null) {
        when (currentAction) {
            ContextMenuAction.NEW_FILE, ContextMenuAction.NEW_FOLDER -> {
                val isFile = currentAction == ContextMenuAction.NEW_FILE
                AlertDialog(
                    onDismissRequest = { contextMenuAction = null },
                    title = { Text(if (isFile) "New File" else "New Folder") },
                    text = {
                        OutlinedTextField(
                            value = inputDialogValue,
                            onValueChange = { inputDialogValue = it },
                            label = { Text("Name") },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val name = inputDialogValue.trim()
                                if (name.isNotEmpty()) {
                                    if (isFile) viewModel.createFile(currentNode, name)
                                    else viewModel.createFolder(currentNode, name)
                                }
                                contextMenuAction = null
                                inputDialogValue = ""
                            },
                            enabled = inputDialogValue.isNotBlank(),
                        ) { Text("Create") }
                    },
                    dismissButton = {
                        TextButton(onClick = { contextMenuAction = null; inputDialogValue = "" }) { Text("Cancel") }
                    },
                )
            }
            ContextMenuAction.RENAME -> {
                AlertDialog(
                    onDismissRequest = { contextMenuAction = null },
                    title = { Text("Rename") },
                    text = {
                        OutlinedTextField(
                            value = inputDialogValue,
                            onValueChange = { inputDialogValue = it },
                            label = { Text("New name") },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val name = inputDialogValue.trim()
                                if (name.isNotEmpty()) viewModel.renameNode(currentNode, name)
                                contextMenuAction = null
                                inputDialogValue = ""
                            },
                            enabled = inputDialogValue.isNotBlank(),
                        ) { Text("Rename") }
                    },
                    dismissButton = {
                        TextButton(onClick = { contextMenuAction = null; inputDialogValue = "" }) { Text("Cancel") }
                    },
                )
            }
            ContextMenuAction.DELETE -> {
                AlertDialog(
                    onDismissRequest = { contextMenuAction = null },
                    title = { Text("Delete") },
                    text = { Text("Delete \"${currentNode.name}\"? This cannot be undone.") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.deleteNode(currentNode); contextMenuAction = null }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { contextMenuAction = null }) { Text("Cancel") }
                    },
                )
            }
        }
    }

    if (showLayoutPreview) {
        LayoutPreviewSheet(
            content = tabState.activeTab?.engine?.currentState?.content ?: "",
            onDismiss = { showLayoutPreview = false },
        )
    }

    if (showFindInFiles) {
        FindInFilesSheet(
            onDismiss = { showFindInFiles = false },
            onResultClick = { _, _ -> showFindInFiles = false },
        )
    }

    if (showBuildSheet) {
        BuildSheet(
            viewModel = buildViewModel,
            onDismiss = { showBuildSheet = false; buildViewModel.reset() },
            onViewLogs = { showBuildSheet = false; onViewLogs() },
        )
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, packageName, minSdk, template ->
                showNewProjectDialog = false
                projectsViewModel.createProject(name, packageName, minSdk, template) { project ->
                    viewModel.selectProject(project)
                }
            },
        )
    }

    // ── Main scaffold ─────────────────────────────────────────────────────────
    // Narrow = a phone, the cover screen or half the unfolded workspace: the sidebar becomes a
    // drawer over the code and the top bar keeps only Build and Save.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val narrow = maxWidth < 600.dp
    val drawerWidth = minOf(300.dp, maxWidth * 0.8f)
    LaunchedEffect(narrow) { if (narrow) isSidebarVisible = false }

    // The code view follows the app: dark colors in dark theme, long lines wrapped when narrow
    // (unless the user chose), and the same text size on both screens of a foldable.
    val dark = isAppInDarkTheme()
    val codeBackground = MaterialTheme.colorScheme.surface.toArgb()
    val gutterBackground = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
    val wrapChoice by viewModel.wordWrap.collectAsStateWithLifecycle()
    val wordWrap = wrapChoice ?: narrow
    LaunchedEffect(activeEngine, dark, codeBackground, gutterBackground) {
        activeEngine?.setTheme(dark, codeBackground, gutterBackground)
    }
    LaunchedEffect(activeEngine, wordWrap) { activeEngine?.setWordWrap(wordWrap) }
    LaunchedEffect(activeEngine, density.density) { activeEngine?.setDensity(density.density) }
    // Hidden behind another tab or the other side of the fold: let go of the keyboard.
    val visible = LocalPaneVisible.current
    LaunchedEffect(activeEngine, visible) { if (!visible) activeEngine?.view?.clearFocus() }
    var overflowMenu by remember { mutableStateOf(false) }
    val otherAgentLock by chatViewModel.otherAgentLock.collectAsState()
    val branch by viewModel.currentBranch.collectAsStateWithLifecycle()
    com.bori.app.ui.common.PollAgentLock(activeProject?.rootPath) {
        chatViewModel.refreshAgentLock()
        viewModel.refreshBranch()
    }
    // Saving while another agent works asks once per lock; after "Save anyway" it stays quiet.
    var lockSaveAsk by remember { mutableStateOf(false) }
    var lockSaveOkFor by remember { mutableStateOf<Long?>(null) }
    val save = {
        val lock = otherAgentLock
        if (lock != null && lockSaveOkFor != lock.startedMs) lockSaveAsk = true else viewModel.saveCurrentFile()
    }
    if (lockSaveAsk) otherAgentLock?.let { lock ->
        com.bori.app.ui.common.AgentLockDialog(
            lock = lock,
            action = "save",
            onConfirm = { lockSaveAsk = false; lockSaveOkFor = lock.startedMs; viewModel.saveCurrentFile() },
            onCancel = { lockSaveAsk = false },
        )
    } ?: run { lockSaveAsk = false }
    Scaffold(
        contentWindowInsets = ShellContentInsets,
        topBar = {
            val errorCount = diagnostics.count { it.severity == com.bori.core.build.model.Diagnostic.Severity.ERROR }
            Column {
            TopAppBar(
                title = {
                    Text(
                        text = activeProject?.name?.let { name -> branch?.let { "$name · $it" } ?: name } ?: "Editor",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                },
                actions = {
                    val findInFiles = {
                        activeSidebarPanel = SidebarPanel.SEARCH
                        isSidebarVisible = true
                        showFindInFiles = true
                    }
                    if (!narrow) {
                        IconButton(onClick = { showNewProjectDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "New project")
                        }
                        if (isLayoutFile) {
                            IconButton(onClick = { showLayoutPreview = true }) {
                                Icon(Icons.Outlined.Visibility, contentDescription = "Layout preview")
                            }
                        }
                        IconButton(onClick = findInFiles) {
                            Icon(Icons.Default.Search, contentDescription = "Find in files")
                        }
                        IconButton(
                            onClick = { viewModel.formatCurrentFile() },
                            enabled = tabState.activeTab != null,
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = "Format file")
                        }
                    }
                    BadgedBox(
                        badge = {
                            if (errorCount > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text("$errorCount", color = MaterialTheme.colorScheme.onError, fontSize = 9.sp)
                                }
                            }
                        },
                    ) {
                        IconButton(onClick = {
                            coroutineScope.launch {
                                viewModel.syncActiveProjectIfNeeded()
                                showBuildSheet = true
                                buildViewModel.startBuild()
                            }
                        }) {
                            Icon(Icons.Default.Build, contentDescription = "Build project")
                        }
                    }
                    if (!narrow) {
                        IconButton(onClick = { viewModel.setWordWrap(!wordWrap) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.WrapText,
                                contentDescription = if (wordWrap) "Turn off word wrap" else "Turn on word wrap",
                                tint = if (wordWrap) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = onOpenGit) {
                            Icon(Icons.Default.AccountTree, contentDescription = "Git")
                        }
                    }
                    IconButton(
                        onClick = { save() },
                        enabled = tabState.activeTab != null,
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save file")
                    }
                    if (narrow) {
                        Box {
                            IconButton(onClick = { overflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Find in files") },
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    onClick = { overflowMenu = false; findInFiles() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Format file") },
                                    leadingIcon = { Icon(Icons.Default.AutoFixHigh, null) },
                                    enabled = tabState.activeTab != null,
                                    onClick = { overflowMenu = false; viewModel.formatCurrentFile() },
                                )
                                if (isLayoutFile) {
                                    DropdownMenuItem(
                                        text = { Text("Layout preview") },
                                        leadingIcon = { Icon(Icons.Outlined.Visibility, null) },
                                        onClick = { overflowMenu = false; showLayoutPreview = true },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Word wrap") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.WrapText, null) },
                                    trailingIcon = { if (wordWrap) Icon(Icons.Default.Check, contentDescription = "On") },
                                    onClick = { overflowMenu = false; viewModel.setWordWrap(!wordWrap) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Git") },
                                    leadingIcon = { Icon(Icons.Default.AccountTree, null) },
                                    onClick = { overflowMenu = false; onOpenGit() },
                                )
                                DropdownMenuItem(
                                    text = { Text("New project") },
                                    leadingIcon = { Icon(Icons.Default.Add, null) },
                                    onClick = { overflowMenu = false; showNewProjectDialog = true },
                                )
                            }
                        }
                    }
                },
            )
            com.bori.app.ui.common.AgentLockBanner(otherAgentLock)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val sidebar: @Composable (Modifier) -> Unit = { sidebarModifier ->
                        Box(
                            modifier = sidebarModifier
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Panel header
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = when (activeSidebarPanel) {
                                            SidebarPanel.EXPLORER -> "EXPLORER"
                                            SidebarPanel.SEARCH   -> "SEARCH"
                                            SidebarPanel.GIT      -> "SOURCE CONTROL"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    if (activeSidebarPanel == SidebarPanel.EXPLORER) {
                                        IconButton(
                                            onClick = {
                                                val root = fileTree.firstOrNull()
                                                if (root != null) {
                                                    inputDialogValue = ""
                                                    contextMenuNode = root
                                                    contextMenuAction = ContextMenuAction.NEW_FILE
                                                }
                                            },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.NoteAdd,
                                                contentDescription = "New file",
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                val root = fileTree.firstOrNull()
                                                if (root != null) {
                                                    inputDialogValue = ""
                                                    contextMenuNode = root
                                                    contextMenuAction = ContextMenuAction.NEW_FOLDER
                                                }
                                            },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.CreateNewFolder,
                                                contentDescription = "New folder",
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { isSidebarVisible = false },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Hide sidebar",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                HorizontalDivider()

                                // Panel content
                                Box(modifier = Modifier.fillMaxSize()) {
                                    when (activeSidebarPanel) {
                                        SidebarPanel.EXPLORER -> {
                                            if (fileTree.isEmpty()) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        text = "Nothing here.\nOpen a project in the\nProjects tab to begin.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier.padding(horizontal = 16.dp),
                                                    )
                                                }
                                            } else {
                                                FileTreePanel(
                                                    nodes = fileTree,
                                                    expandedPaths = expandedPaths,
                                                    onToggleExpand = { viewModel.toggleExpand(it) },
                                                    onFileClick = {
                                                        viewModel.openFile(it)
                                                        if (narrow) isSidebarVisible = false
                                                    },
                                                    onLongPress = { node ->
                                                        contextMenuNode = node
                                                        inputDialogValue = node.name
                                                    },
                                                    activeFilePath = activeFilePath,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }

                                        SidebarPanel.SEARCH -> SearchSidebarPanel(
                                            viewModel = findInFilesViewModel,
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        SidebarPanel.GIT -> GitSidebarPanel(
                                            onOpenGit = onOpenGit,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    // Context menu for Explorer
                                    if (activeSidebarPanel == SidebarPanel.EXPLORER) {
                                        DropdownMenu(
                                            expanded = contextMenuNode != null && contextMenuAction == null,
                                            onDismissRequest = { contextMenuNode = null },
                                        ) {
                                            val node = contextMenuNode
                                            if (node != null) {
                                                if (node.isDirectory) {
                                                    DropdownMenuItem(
                                                        text = { Text("New File") },
                                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) },
                                                        onClick = { inputDialogValue = ""; contextMenuAction = ContextMenuAction.NEW_FILE },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("New Folder") },
                                                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                                        onClick = { inputDialogValue = ""; contextMenuAction = ContextMenuAction.NEW_FOLDER },
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text("Rename") },
                                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                                    onClick = { inputDialogValue = node.name; contextMenuAction = ContextMenuAction.RENAME },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                                    },
                                                    onClick = { contextMenuAction = ContextMenuAction.DELETE },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                        when (event.key) {
                            Key.S -> { save(); true }
                            Key.B -> { isSidebarVisible = !isSidebarVisible; true }
                            Key.P -> { showQuickOpen = true; true }
                            Key.Grave -> { onOpenTerminal(); true }
                            else -> false
                        }
                    } else false
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {

                    // ── Activity bar ──────────────────────────────────────────
                    ActivityBar(
                        activePanel = if (isSidebarVisible) activeSidebarPanel else null,
                        onPanelClick = { panel ->
                            if (isSidebarVisible && activeSidebarPanel == panel) {
                                isSidebarVisible = false
                            } else {
                                activeSidebarPanel = panel
                                isSidebarVisible = true
                            }
                        },
                    )

                    // ── Sidebar ───────────────────────────────────────────────
                    if (isSidebarVisible && !narrow) {
                        sidebar(Modifier.width(sidebarWidthDp.dp))
                        // Drag-to-resize handle
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant)
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { _, dragAmount ->
                                        val deltaDp = with(density) { dragAmount.toDp().value.toInt() }
                                        sidebarWidthDp = (sidebarWidthDp + deltaDp).coerceIn(140, 400)
                                    }
                                },
                        )
                    } else if (!narrow) {
                        // Collapsed indicator strip — click or tap to reveal
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant)
                                .clickable { isSidebarVisible = true },
                        )
                    }

                    // ── Editor column ─────────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        // Tab bar
                        EditorTabBar(
                            tabs = tabState.tabs,
                            activeIndex = tabState.activeIndex,
                            onSelectTab = { editorController.selectTab(it) },
                            onCloseTab = { viewModel.closeTab(it) },
                        )


                        // Editor area
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            key(tabState.activeTab?.id) {
                                if (activeEngine != null) {
                                    AndroidView(
                                        // The engine's view outlives this screen; if a previous editor
                                        // (folding, the workspace) still holds it, take it back first.
                                        factory = {
                                            activeEngine.view.also { v ->
                                                (v.parent as? android.view.ViewGroup)?.removeView(v)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                Icons.Default.ChevronRight,
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp),
                                                tint = MaterialTheme.colorScheme.outlineVariant,
                                            )
                                            Text(
                                                text = "Open a file from the Explorer",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = "or press Ctrl+P to quick-open",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            // "Ask Claude" chip when text is selected
                            val selectedText = editorState?.selectedText.orEmpty()
                            if (selectedText.isNotEmpty()) {
                                SuggestionChip(
                                    onClick = {
                                        val lang = editorState?.language ?: "kotlin"
                                        chatViewModel.setPendingInput("```$lang\n$selectedText\n```\n")
                                        onNavigateToChat()
                                    },
                                    label = { Text("Ask Claude") },
                                    icon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Chat,
                                            contentDescription = null,
                                            modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                                        )
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 8.dp, bottom = 8.dp),
                                )
                            }
                        }

                        // Bottom panel
                        if (isBottomPanelVisible) {
                            BottomPanel(
                                activeTab = activeBottomTab,
                                onTabChange = { activeBottomTab = it },
                                onClose = { isBottomPanelVisible = false },
                                panelHeight = bottomPanelHeightDp,
                                onHeightChange = { bottomPanelHeightDp = it },
                                diagnostics = diagnostics,
                                symbols = currentFileSymbols,
                                dependencies = userDependencies,
                                onDiagnosticClick = { viewModel.navigateToDiagnostic(it) },
                                onAddImport = { path, fqn -> viewModel.applyImportFix(path, fqn) },
                                onSymbolClick = { sym ->
                                    editorController.tabState.value.activeTab?.engine?.jumpToLine(sym.line)
                                },
                                onAddDependency = { viewModel.addDependency(it) },
                                onRemoveDependency = { viewModel.removeDependency(it) },
                            )
                        } else if (currentFileSymbols.isNotEmpty() || diagnostics.isNotEmpty()) {
                            // Collapsed panel hint strip
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .clickable { isBottomPanelVisible = true }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val errCount = diagnostics.count { it.severity == com.bori.core.build.model.Diagnostic.Severity.ERROR }
                                val warnCount = diagnostics.count { it.severity == com.bori.core.build.model.Diagnostic.Severity.WARNING }
                                if (errCount > 0) {
                                    Text("✕ $errCount  ", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                if (warnCount > 0) {
                                    Text("⚠ $warnCount  ", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary)
                                }
                                if (currentFileSymbols.isNotEmpty()) {
                                    Text("${currentFileSymbols.size} symbols  ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = "Expand panel",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }

                // ── Status bar ────────────────────────────────────────────────
                StatusBar(
                    diagnostics = diagnostics,
                    editorState = editorState,
                    projectName = activeProject?.name,
                )

                // Symbols and undo for touch typing; last, so it sits right above the keyboard.
                if (activeEngine != null) {
                    ShortcutBar(
                        onInsert = { text -> activeEngine.insertAtCursor(text) },
                        onUndo = { activeEngine.undo() },
                        onRedo = { activeEngine.redo() },
                        onMoveCursor = { delta -> activeEngine.moveCursor(delta) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Narrow: the sidebar opens as a drawer over the code, beside the activity bar.
            if (narrow && isSidebarVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 48.dp)
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable { isSidebarVisible = false },
                )
                sidebar(
                    Modifier
                        .padding(start = 48.dp)
                        .width(drawerWidth)
                        .shadow(8.dp),
                )
            }

            // Quick-open dialog (Ctrl+P)
            if (showQuickOpen) {
                QuickOpenDialog(
                    projectRoot = activeProject?.rootPath,
                    viewModel = viewModel,
                    onDismiss = { showQuickOpen = false },
                )
            }
        }
    }
    }
}

// ── Sidebar panels ────────────────────────────────────────────────────────────

@Composable
private fun SearchSidebarPanel(
    viewModel: FindInFilesViewModel,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                viewModel.search(it, caseSensitive = false, useRegex = false)
            },
            placeholder = { Text("Search in files…", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        HorizontalDivider()
        if (isSearching) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Searching…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                results.forEach { fileResult ->
                    item {
                        Text(
                            text = fileResult.fileName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    items(fileResult.matches) { match ->
                        Text(
                            text = "${match.line}: ${match.content}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openResult(fileResult.filePath, match.line) }
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    item { HorizontalDivider() }
                }
                if (results.isEmpty() && query.isNotBlank()) {
                    item {
                        Text(
                            "No results for \"$query\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GitSidebarPanel(onOpenGit: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Changes, commits, pull and push",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onOpenGit) { Text("Open Git") }
        }
    }
}

// ── Status bar ────────────────────────────────────────────────────────────────

@Composable
private fun StatusBar(
    diagnostics: List<com.bori.core.build.model.Diagnostic>,
    editorState: com.bori.core.editor.EditorState?,
    projectName: String?,
    modifier: Modifier = Modifier,
) {
    val errCount = diagnostics.count { it.severity == com.bori.core.build.model.Diagnostic.Severity.ERROR }
    val warnCount = diagnostics.count { it.severity == com.bori.core.build.model.Diagnostic.Severity.WARNING }

    val (lineNum, colNum) = remember(editorState?.cursorOffset, editorState?.content) {
        val offset = editorState?.cursorOffset ?: 0
        val content = editorState?.content ?: ""
        val textBefore = content.take(offset)
        val line = textBefore.count { it == '\n' } + 1
        val col = offset - textBefore.lastIndexOf('\n')
        line to col
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (errCount > 0) {
            Text(
                text = "✕ $errCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(10.dp))
        }
        if (warnCount > 0) {
            Text(
                text = "⚠ $warnCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (projectName != null) {
            Text(
                text = projectName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(12.dp))
        }
        if (!editorState?.language.isNullOrEmpty()) {
            Text(
                text = editorState!!.language.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(12.dp))
        }
        if (editorState != null) {
            Text(
                text = "Ln $lineNum, Col $colNum",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// ── Project picker (shown when no project is active) ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectPickerContent(
    projects: List<Project>,
    onSelect: (Project) -> Unit,
    projectsViewModel: ProjectsViewModel,
    modifier: Modifier = Modifier,
) {
    var showNewProjectDialog by remember { mutableStateOf(false) }

    if (showNewProjectDialog) {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, packageName, minSdk, template ->
                showNewProjectDialog = false
                projectsViewModel.createProject(name, packageName, minSdk, template) { project ->
                    projectsViewModel.openProject(project)
                    onSelect(project)
                }
            },
        )
    }

    Scaffold(

        contentWindowInsets = ShellContentInsets,
        topBar = { TopAppBar(title = { Text("Open Project") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewProjectDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New project")
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        if (projects.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "No projects yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Tap + to create your first project",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    val dateStr = remember(project.createdAt) {
                        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(project.createdAt))
                    }
                    val language = if (project.template.name.endsWith("_KOTLIN")) "Kotlin" else "Java"
                    Card(
                        onClick = { onSelect(project) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            ) {
                                Text(
                                    text = project.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${project.packageName} · API ${project.minSdk} · $language",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = dateStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
