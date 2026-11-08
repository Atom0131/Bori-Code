package com.bori.app.ui.projects

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.bori.app.ui.common.BoriTopBar
import com.bori.app.ui.common.ShellContentInsets
import com.bori.app.ui.editor.BinaryFiles
import com.bori.app.ui.editor.ImagePreviewDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copying files picked on the phone into a project folder; shared by the editor and the file explorer. */
object ProjectFileOps {
    /** Copies each picked file into [dir]; returns the names it wrote and the errors it hit. */
    fun importInto(context: Context, dir: File, uris: List<Uri>): Pair<List<String>, List<String>> {
        dir.mkdirs()
        val added = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (uri in uris) {
            runCatching {
                val target = freeName(dir, displayName(context, uri))
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                added += target.name
            }.onFailure { errors += (it.message ?: it.javaClass.simpleName) }
        }
        return added to errors
    }

    fun displayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
        return name?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "file"
    }

    /** [name] in [dir], or "name (2).ext", "name (3).ext"… when it's taken. */
    fun freeName(dir: File, name: String): File {
        var file = File(dir, name)
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (file.exists()) file = File(dir, "$base (${n++})$ext")
        return file
    }
}

/** Build output and tool folders hidden unless "Show hidden" is on. */
private val NOISE = setOf("build", ".gradle", ".idea", ".kotlin")

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun iconFor(file: File): ImageVector = when {
    file.isDirectory -> Icons.Default.Folder
    BinaryFiles.isImage(file.name) -> Icons.Default.Image
    BinaryFiles.isVideo(file.name) -> Icons.Default.Movie
    file.extension.equals("pdf", true) -> Icons.Default.PictureAsPdf
    BinaryFiles.isBinary(file.name) -> Icons.Default.InsertDriveFile
    else -> Icons.Default.Description
}

private fun mimeOf(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

private fun contentUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/** Text-ish files open in the editor; everything else that isn't an image goes to another app. */
private fun isDocument(file: File): Boolean =
    BinaryFiles.isBinary(file.name) ||
        file.extension.lowercase() in setOf("doc", "docx", "odt", "rtf", "xls", "xlsx", "ppt", "pptx", "epub", "mp3", "m4a", "wav", "ogg")

private enum class FileAction { RENAME, DELETE, NEW_FOLDER }

/**
 * A plain file explorer for one project: a list of folders and files, tap to go in or open,
 * and a big "Add files" button that copies anything from the phone into the folder you're in.
 * The CLI agents see the same folder in Linux, so a doc added here can be handed to them by name.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectFilesScreen(
    root: File,
    title: String,
    onOpenInEditor: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dir by remember(root) { mutableStateOf(root) }
    var showHidden by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var action by remember { mutableStateOf<Pair<FileAction, File>?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var previewImage by remember { mutableStateOf<String?>(null) }
    var topMenu by remember { mutableStateOf(false) }

    LaunchedEffect(dir, showHidden, refresh) {
        entries = withContext(Dispatchers.IO) {
            (dir.listFiles()?.toList() ?: emptyList())
                .filter { showHidden || (!it.name.startsWith(".") && it.name !in NOISE) }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val target = dir
        scope.launch {
            val (added, errors) = withContext(Dispatchers.IO) { ProjectFileOps.importInto(context, target, uris) }
            refresh++
            val msg = when {
                added.size == 1 -> "Added ${added[0]}"
                added.isNotEmpty() -> "Added ${added.size} files"
                else -> "Couldn't add: ${errors.firstOrNull()}"
            }
            snackbar.showSnackbar(msg)
        }
    }

    fun relPath(file: File) = file.relativeTo(root).path

    fun open(file: File) {
        when {
            file.isDirectory -> dir = file
            BinaryFiles.isImage(file.name) -> previewImage = file.absolutePath
            isDocument(file) -> runCatching {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(contentUri(context, file), mimeOf(file))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(intent, file.name))
            }.onFailure { scope.launch { snackbar.showSnackbar("No app can open ${file.name}") } }
            else -> { onOpenInEditor(file); onDismiss() }
        }
    }

    fun share(file: File) = runCatching {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(file))
            .putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, file.name))
    }

    fun copyPath(file: File) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("path", relPath(file)))
        Toast.makeText(context, "Copied ${relPath(file)}. Paste it to the agent.", Toast.LENGTH_SHORT).show()
    }

    run {
        BackHandler { if (dir != root) dir = dir.parentFile ?: root else onDismiss() }
        run {
            Scaffold(
                contentWindowInsets = ShellContentInsets,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    BoriTopBar(
                        title = title,
                        subtitle = "Files",
                        onBack = { if (dir != root) dir = dir.parentFile ?: root else onDismiss() },
                        actions = {
                            IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.UploadFile, contentDescription = "Add files from phone")
                            }
                            IconButton(onClick = { topMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("New folder") },
                                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                    onClick = { topMenu = false; nameInput = ""; action = FileAction.NEW_FOLDER to dir },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") },
                                    leadingIcon = { Icon(if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) },
                                    onClick = { topMenu = false; showHidden = !showHidden },
                                )
                            }
                        },
                    )
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        icon = { Icon(Icons.Default.UploadFile, null) },
                        text = { Text("Add files") },
                    )
                },
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    // Breadcrumbs: tap any part to jump back to it.
                    val crumbs = generateSequence(dir) { if (it == root) null else it.parentFile }.toList().reversed()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                    ) {
                        crumbs.forEachIndexed { i, f ->
                            if (i > 0) Icon(
                                Icons.Default.ChevronRight, null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { dir = f }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text(if (f == root) title else f.name, maxLines = 1)
                            }
                        }
                    }
                    HorizontalDivider()
                    if (entries.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "This folder is empty.\nTap Add files to copy something in from your phone.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                            items(entries, key = { it.absolutePath }) { file ->
                                FileRow(
                                    file = file,
                                    onClick = { open(file) },
                                    onLongClick = { menuFor = file },
                                )
                                Box {
                                    DropdownMenu(expanded = menuFor == file, onDismissRequest = { menuFor = null }) {
                                        DropdownMenuItem(
                                            text = { Text("Copy path for agent") },
                                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                            onClick = { menuFor = null; copyPath(file) },
                                        )
                                        if (file.isFile) DropdownMenuItem(
                                            text = { Text("Share") },
                                            leadingIcon = { Icon(Icons.Default.Share, null) },
                                            onClick = { menuFor = null; share(file) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                            onClick = { menuFor = null; nameInput = file.name; action = FileAction.RENAME to file },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                                            onClick = { menuFor = null; action = FileAction.DELETE to file },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        previewImage?.let { ImagePreviewDialog(path = it, onDismiss = { previewImage = null }) }

        action?.let { (kind, target) ->
            fun done(msg: String?) {
                action = null
                refresh++
                if (msg != null) scope.launch { snackbar.showSnackbar(msg) }
            }
            when (kind) {
                FileAction.DELETE -> AlertDialog(
                    onDismissRequest = { action = null },
                    title = { Text("Delete ${target.name}?") },
                    text = { Text(if (target.isDirectory) "The folder and everything in it will be deleted." else "This can't be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { target.deleteRecursively() }
                                done(if (ok) "Deleted ${target.name}" else "Couldn't delete ${target.name}")
                            }
                        }) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
                )
                FileAction.RENAME, FileAction.NEW_FOLDER -> AlertDialog(
                    onDismissRequest = { action = null },
                    title = { Text(if (kind == FileAction.RENAME) "Rename" else "New folder") },
                    text = {
                        OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, singleLine = true)
                    },
                    confirmButton = {
                        TextButton(
                            enabled = nameInput.isNotBlank() && '/' !in nameInput,
                            onClick = {
                                val name = nameInput.trim()
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        if (kind == FileAction.RENAME) {
                                            val dest = File(target.parentFile, name)
                                            !dest.exists() && target.renameTo(dest)
                                        } else {
                                            File(target, name).mkdirs()
                                        }
                                    }
                                    done(if (ok) null else "Couldn't ${if (kind == FileAction.RENAME) "rename" else "create"} $name")
                                }
                            },
                        ) { Text("OK") }
                    },
                    dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: File, onClick: () -> Unit, onLongClick: () -> Unit) {
    val date = remember(file, file.lastModified()) {
        SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(file.lastModified()))
    }
    val detail = remember(file) {
        if (file.isDirectory) {
            val n = file.list()?.size ?: 0
            if (n == 1) "1 item" else "$n items"
        } else formatSize(file.length())
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            iconFor(file), null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$detail · $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
