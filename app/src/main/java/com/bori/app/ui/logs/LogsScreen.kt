package com.bori.app.ui.logs

import com.bori.app.ui.common.ShellContentInsets
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bori.app.diagnostics.AppLogReader

private enum class LogFilter { ALL, APP, BORI, ERRORS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val allEntries by viewModel.entries.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val readLogsGranted by viewModel.readLogsGranted.collectAsState()
    val activePackage = viewModel.activePackageName.collectAsState().value
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var filter by remember { mutableStateOf(LogFilter.APP) }
    var pinToBottom by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val displayed = remember(allEntries, filter, activePackage) {
        when (filter) {
            LogFilter.ALL -> allEntries
            LogFilter.APP -> allEntries.filter { entry ->
                activePackage != null && (entry.tag.contains(activePackage, ignoreCase = true)
                        || entry.message.contains(activePackage, ignoreCase = true))
            }
            LogFilter.BORI -> allEntries.filter { it.tag.contains("Bori", ignoreCase = true) }
            LogFilter.ERRORS -> allEntries.filter { it.level == 'E' || it.level == 'W' }
        }
    }

    // Auto-scroll when pinned
    LaunchedEffect(displayed.size, pinToBottom) {
        if (pinToBottom && displayed.isNotEmpty()) {
            listState.animateScrollToItem(displayed.size - 1)
        }
    }

    Scaffold(

        contentWindowInsets = ShellContentInsets,
        topBar = {
            TopAppBar(
                title = { Text("App Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { pinToBottom = !pinToBottom },
                    ) {
                        Icon(
                            Icons.Default.VerticalAlignBottom,
                            contentDescription = if (pinToBottom) "Unpin scroll" else "Pin to bottom",
                            tint = if (pinToBottom) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(displayed.joinToString("\n") { it.raw }))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = { shareLogs(context, displayed) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // READ_LOGS grant banner
            if (!readLogsGranted) {
                val adbCmd = "adb shell pm grant ${context.packageName} android.permission.READ_LOGS"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "⚠ To view your app's logs, run this ADB command once:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            adbCmd,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(adbCmd)) },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("Copy command")
                        }
                    }
                }
            }

            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = {
                            Text(
                                when (f) {
                                    LogFilter.ALL -> "All"
                                    LogFilter.APP -> "App"
                                    LogFilter.BORI -> "Bori Code"
                                    LogFilter.ERRORS -> "Errors"
                                }
                            )
                        },
                    )
                }
            }

            if (!isLoading && displayed.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    Text(
                        text = if (filter == LogFilter.APP && activePackage == null)
                            "No project open — open a project to filter its logs"
                        else
                            "No log entries",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(displayed, key = { it.raw + displayed.indexOf(it) }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: AppLogReader.LogEntry) {
    val bgColor = when (entry.level) {
        'E' -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        'W' -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = when (entry.level) {
        'E' -> MaterialTheme.colorScheme.onErrorContainer
        'W' -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(color = bgColor) {
        Text(
            text = entry.raw,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = textColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .horizontalScroll(rememberScrollState()),
            softWrap = false,
        )
    }
}

private fun shareLogs(context: Context, entries: List<AppLogReader.LogEntry>) {
    val text = entries.joinToString("\n") { it.raw }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Bori Code logs")
    }
    context.startActivity(Intent.createChooser(intent, "Share Logs"))
}
