package com.bori.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bori.core.build.model.Diagnostic
import com.bori.core.editor.ClasspathIndex
import com.bori.core.editor.SymbolEntry

@Composable
fun BottomPanel(
    activeTab: BottomTab,
    onTabChange: (BottomTab) -> Unit,
    onClose: () -> Unit,
    panelHeight: Int,
    onHeightChange: (Int) -> Unit,
    diagnostics: List<Diagnostic>,
    symbols: List<SymbolEntry>,
    dependencies: List<String>,
    onDiagnosticClick: (Diagnostic) -> Unit,
    onAddImport: (filePath: String, fqn: String) -> Unit,
    onSymbolClick: (SymbolEntry) -> Unit,
    onAddDependency: (String) -> Unit,
    onRemoveDependency: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider()

        // Drag handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .pointerInput(panelHeight) {
                    detectVerticalDragGestures { _, dragAmount ->
                        val deltaDp = with(density) { (-dragAmount).toDp().value.toInt() }
                        onHeightChange((panelHeight + deltaDp).coerceIn(80, 400))
                    }
                },
        )

        // Tab header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomTab.entries.forEach { tab ->
                val isActive = tab == activeTab
                val label = when (tab) {
                    BottomTab.PROBLEMS -> {
                        val e = diagnostics.count { it.severity == Diagnostic.Severity.ERROR }
                        val w = diagnostics.count { it.severity == Diagnostic.Severity.WARNING }
                        buildString {
                            append("PROBLEMS")
                            if (e > 0) append(" ✕$e")
                            if (w > 0) append(" ⚠$w")
                        }
                    }
                    BottomTab.OUTLINE       -> "OUTLINE (${symbols.size})"
                    BottomTab.DEPENDENCIES  -> "DEPS (${dependencies.size})"
                }
                TextButton(
                    onClick = { onTabChange(tab) },
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close panel",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        // Panel content
        Box(modifier = Modifier.fillMaxWidth().height(panelHeight.dp)) {
            when (activeTab) {
                BottomTab.PROBLEMS -> ProblemsContent(
                    diagnostics = diagnostics,
                    onDiagnosticClick = onDiagnosticClick,
                    onAddImport = onAddImport,
                    modifier = Modifier.fillMaxWidth(),
                )
                BottomTab.OUTLINE -> SymbolOutlinePanel(
                    symbols = symbols,
                    onSymbolClick = onSymbolClick,
                    modifier = Modifier.fillMaxWidth(),
                )
                BottomTab.DEPENDENCIES -> DependenciesPanel(
                    dependencies = dependencies,
                    expanded = true,
                    onExpandedChange = {},
                    onAdd = onAddDependency,
                    onRemove = onRemoveDependency,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ProblemsContent(
    diagnostics: List<Diagnostic>,
    onDiagnosticClick: (Diagnostic) -> Unit,
    onAddImport: (filePath: String, fqn: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (diagnostics.isEmpty()) {
        Box(modifier = modifier.padding(16.dp)) {
            Text(
                text = "No problems detected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier = modifier) {
        items(diagnostics) { d ->
            val unresolvedRef = remember(d.message) {
                Regex("Unresolved reference:? (\\w+)").find(d.message)?.groupValues?.get(1)
            }
            val importOptions = remember(unresolvedRef) {
                if (unresolvedRef != null) ClasspathIndex.lookup(unresolvedRef) else emptyList()
            }
            var showImportMenu by remember { mutableStateOf(false) }
            val isError = d.severity == Diagnostic.Severity.ERROR
            val color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.tertiary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDiagnosticClick(d) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isError) "✕" else "⚠",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier.width(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${d.filePath.substringAfterLast('/')}:${d.line}  ${d.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (importOptions.isNotEmpty()) {
                    Box {
                        IconButton(
                            onClick = {
                                if (importOptions.size == 1) onAddImport(d.filePath, importOptions.first())
                                else showImportMenu = true
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.AutoFixHigh,
                                contentDescription = "Add import",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        DropdownMenu(expanded = showImportMenu, onDismissRequest = { showImportMenu = false }) {
                            importOptions.forEach { fqn ->
                                DropdownMenuItem(
                                    text = { Text(fqn, style = MaterialTheme.typography.bodySmall) },
                                    onClick = { onAddImport(d.filePath, fqn); showImportMenu = false },
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
