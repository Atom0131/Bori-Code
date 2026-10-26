package com.bori.app.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val QUICK_OPEN_EXTENSIONS = setOf(
    "kt", "kts", "java", "xml", "gradle", "json", "md", "txt", "properties", "toml", "pro",
)

@Composable
fun QuickOpenDialog(
    projectRoot: String?,
    viewModel: EditorViewModel,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val allFiles = remember { mutableStateListOf<File>() }
    var isScanning by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }

    // Scan all project files once
    LaunchedEffect(projectRoot) {
        isScanning = true
        val root = projectRoot?.let { File(it) }
        if (root != null && root.exists()) {
            val files = withContext(Dispatchers.IO) {
                root.walkTopDown()
                    .filter { f ->
                        f.isFile &&
                            f.extension.lowercase() in QUICK_OPEN_EXTENSIONS &&
                            !f.path.contains("${File.separator}.git${File.separator}") &&
                            !f.path.contains("${File.separator}build${File.separator}")
                    }
                    .take(1000)
                    .toList()
            }
            allFiles.clear()
            allFiles.addAll(files)
        }
        isScanning = false
    }

    val filtered = remember(query, allFiles.size) {
        if (query.isBlank()) allFiles.take(50)
        else {
            val lq = query.lowercase()
            allFiles.filter { it.name.lowercase().contains(lq) }
                .sortedBy { it.name.length }
                .take(50)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search files by name…") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .focusRequester(focusRequester),
                )

                HorizontalDivider()

                if (isScanning) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (query.isBlank()) "No files in project." else "No files match \"$query\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(filtered) { _, file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.openFile(
                                            FileNode(name = file.name, path = file.absolutePath, isDirectory = false)
                                        )
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = file.parentFile?.absolutePath?.substringAfter(projectRoot ?: "") ?: "",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
