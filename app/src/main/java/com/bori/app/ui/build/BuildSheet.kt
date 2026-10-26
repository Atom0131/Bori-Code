package com.bori.app.ui.build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.bori.core.build.model.BuildVariant
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bori.core.build.model.BuildEvent
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildSheet(
    viewModel: BuildViewModel,
    onDismiss: () -> Unit,
    onViewLogs: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val isInstalled by viewModel.isInstalled.collectAsState()
    val variant by viewModel.variant.collectAsState()
    val hasCustomKeystore by viewModel.hasCustomKeystore.collectAsState()
    val isBuilding = uiState is BuildViewModel.UiState.Building
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Auto-scroll to latest log entry
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Re-check install state when user returns from system installer
    val doneState = uiState as? BuildViewModel.UiState.Done
    DisposableEffect(lifecycleOwner, doneState?.packageName) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && doneState != null) {
                viewModel.refreshInstallState(doneState.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Build Output",
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = {
                        val text = logs.joinToString("\n") { it.message }
                        clipboard.setText(AnnotatedString(text))
                    },
                    enabled = logs.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy build log",
                        tint = if (logs.isNotEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                }
            }

            // Variant selector — Debug / Release
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                BuildVariant.entries.forEachIndexed { index, v ->
                    SegmentedButton(
                        selected = variant == v,
                        onClick = { viewModel.setVariant(v) },
                        shape = SegmentedButtonDefaults.itemShape(index, BuildVariant.entries.size),
                        enabled = !isBuilding,
                        label = { Text(v.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            // Keystore warning when release is selected but no keystore configured
            if (variant == BuildVariant.RELEASE && !hasCustomKeystore) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                    Text(
                        text = "No keystore configured — configure one in Settings → Release Signing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Progress indicator while building
            if (uiState is BuildViewModel.UiState.Building) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Log output
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 400.dp),
            ) {
                items(logs) { logEntry ->
                    val color = when (logEntry.level) {
                        BuildEvent.Log.Level.INFO -> MaterialTheme.colorScheme.onSurface
                        BuildEvent.Log.Level.WARN -> MaterialTheme.colorScheme.tertiary
                        BuildEvent.Log.Level.ERROR -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = logEntry.message,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = color,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Empty state
                if (logs.isEmpty() && uiState is BuildViewModel.UiState.Building) {
                    item {
                        Text(
                            text = "Build starting…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Status line
            when (val state = uiState) {
                is BuildViewModel.UiState.Done -> {
                    val sizeStr = remember(state.apkPath) {
                        val f = File(state.apkPath)
                        if (f.exists()) "  ·  ${formatApkSize(f.length())}" else ""
                    }
                    Text(
                        "✓ Build successful$sizeStr",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is BuildViewModel.UiState.Failed ->
                    Text(
                        "✗ ${state.error}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                else -> Box(modifier = Modifier)
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
                if (doneState != null) {
                    val apkPath = doneState.apkPath
                    val packageName = doneState.packageName

                    TextButton(onClick = onViewLogs) {
                        Text("View Logs")
                    }

                    OutlinedButton(onClick = {
                        val apkFile = File(apkPath)
                        if (!canInstallPackages(context)) openInstallPermissionSettings(context)
                        else launchApkInstaller(context, apkFile)
                    }) {
                        Text("Install APK")
                    }

                    if (isInstalled) {
                        Button(onClick = { viewModel.launchApp(packageName) }) {
                            Text("Launch App")
                        }
                    }
                }
            }
        }
    }
}
