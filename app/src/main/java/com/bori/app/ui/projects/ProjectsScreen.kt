package com.bori.app.ui.projects

import com.bori.app.ui.common.ShellContentInsets
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.Build
import com.bori.app.ui.common.BoriTopBar
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bori.app.ui.build.BuildSheet
import com.bori.app.ui.build.BuildViewModel
import com.bori.app.ui.build.apkFileForProject
import com.bori.app.ui.build.canInstallPackages
import com.bori.app.ui.build.formatApkSize
import com.bori.app.ui.build.launchApkInstaller
import com.bori.app.ui.build.openInstallPermissionSettings
import com.bori.core.project.model.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onOpenProject: (Project) -> Unit,
    onViewLogs: () -> Unit = {},
    viewModel: ProjectsViewModel = hiltViewModel(),
    buildViewModel: BuildViewModel = hiltViewModel(),
) {
    val projects by viewModel.projects.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    var showImportSheet by remember { mutableStateOf(false) }
    var pickedZipUri by remember { mutableStateOf<Uri?>(null) }
    var pickedZipName by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var projectToDelete by remember { mutableStateOf<Project?>(null) }
    var projectToBuild by remember { mutableStateOf<Project?>(null) }
    var showBuildSheet by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedZipUri = uri
            pickedZipName = context.contentResolver
                .query(uri, null, null, null, null)?.use { cursor ->
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else null
                }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.error.collect { message ->
            snackbarHostState.showSnackbar(message)
            if (message.contains("permission", ignoreCase = true) ||
                message.contains("denied", ignoreCase = true) ||
                message.contains("EPERM", ignoreCase = true) ||
                message.contains("EACCES", ignoreCase = true)
            ) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
            }
        }
    }

    if (showImportSheet) {
        ImportProjectSheet(
            importState = importState,
            pickedZipUri = pickedZipUri,
            pickedZipName = pickedZipName,
            onPickZip = { zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
            onImportGitHub = { url, token ->
                viewModel.importFromGitHub(url, token) { project ->
                    viewModel.openProject(project)
                    showImportSheet = false
                    onOpenProject(project)
                }
            },
            onImportZip = { uri ->
                viewModel.importFromZip(uri) { project ->
                    viewModel.openProject(project)
                    showImportSheet = false
                    pickedZipUri = null
                    pickedZipName = null
                    onOpenProject(project)
                }
            },
            onClearError = { viewModel.clearImportError() },
            onDismiss = {
                showImportSheet = false
                viewModel.clearImportError()
            },
        )
    }

    projectToDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("Delete project?") },
            text = { Text("\"${pending.name}\" will be moved to the deleted projects folder. You can restore it from Settings.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProject(pending)
                    projectToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) { Text("Cancel") }
            },
        )
    }

    projectToBuild?.let { pending ->
        AlertDialog(
            onDismissRequest = { projectToBuild = null },
            title = { Text("Build project?") },
            text = { Text("Build \"${pending.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.openProject(pending)
                    buildViewModel.startBuild()
                    showBuildSheet = true
                    projectToBuild = null
                }) { Text("Build") }
            },
            dismissButton = {
                TextButton(onClick = { projectToBuild = null }) { Text("Cancel") }
            },
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
                viewModel.createProject(name, packageName, minSdk, template) { project ->
                    viewModel.openProject(project)
                    onOpenProject(project)
                }
            },
        )
    }

    Scaffold(

        contentWindowInsets = ShellContentInsets,
        topBar = {
            BoriTopBar(
                title = "Projects",
                actions = {
                    IconButton(onClick = { showNewProjectDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "New project")
                    }
                    IconButton(onClick = { showImportSheet = true }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Import project")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (projects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
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
            // One column on a phone, a grid when unfolded.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 80.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        isActive = project.id == activeProject?.id,
                        onClick = {
                            viewModel.openProject(project)
                            onOpenProject(project)
                        },
                        onBuild = { projectToBuild = project },
                        onDelete = { projectToDelete = project },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    isActive: Boolean,
    onClick: () -> Unit,
    onBuild: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateStr = remember(project.createdAt) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(project.createdAt))
    }
    val language = if (project.template.name.endsWith("_KOTLIN")) "Kotlin" else "Java"
    val context = LocalContext.current
    val apkFile = remember(project.name) { apkFileForProject(context, project.name) }
    var apkExists by remember { mutableStateOf(apkFile.exists()) }
    var showApkSizeWarning by remember { mutableStateOf(false) }

    if (showApkSizeWarning) {
        AlertDialog(
            onDismissRequest = { showApkSizeWarning = false },
            title = { Text("Large APK") },
            text = { Text("Are you sure you want to do this? This APK is ${formatApkSize(apkFile.length())} and will take up that much space on your device.") },
            confirmButton = {
                TextButton(onClick = {
                    showApkSizeWarning = false
                    if (!canInstallPackages(context)) openInstallPermissionSettings(context)
                    else launchApkInstaller(context, apkFile)
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { showApkSizeWarning = false }) { Text("Cancel") }
            },
        )
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
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
            IconButton(onClick = onBuild) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = "Build",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (apkExists) {
                IconButton(onClick = {
                    if (apkFile.length() >= 50L * 1024 * 1024) showApkSizeWarning = true
                    else if (!canInstallPackages(context)) openInstallPermissionSettings(context)
                    else launchApkInstaller(context, apkFile)
                }) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Install last build (${formatApkSize(apkFile.length())})",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete project",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
