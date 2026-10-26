package com.bori.app.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Displays the project file tree as a scrollable list.
 *
 * Folders can be expanded/collapsed by tapping them. Files open in the editor
 * when tapped. Long-pressing any node triggers [onLongPress] for the context menu.
 */
@Composable
fun FileTreePanel(
    nodes: List<FileNode>,
    expandedPaths: Set<String>,
    onToggleExpand: (FileNode) -> Unit,
    onFileClick: (FileNode) -> Unit,
    onLongPress: (FileNode) -> Unit,
    activeFilePath: String = "",
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(flattenTree(nodes, expandedPaths)) { (node, depth) ->
            FileTreeRow(
                node = node,
                depth = depth,
                isExpanded = node.isDirectory && node.path in expandedPaths,
                isActive = !node.isDirectory && node.path == activeFilePath,
                onToggleExpand = { onToggleExpand(node) },
                onFileClick = { onFileClick(node) },
                onLongPress = { onLongPress(node) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: FileNode,
    depth: Int,
    isExpanded: Boolean,
    isActive: Boolean,
    onToggleExpand: () -> Unit,
    onFileClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .combinedClickable(
                onClick = { if (node.isDirectory) onToggleExpand() else onFileClick() },
                onLongClick = onLongPress,
            )
            .padding(
                start = (16 + depth * 12).dp,
                top = 8.dp,
                bottom = 8.dp,
                end = 8.dp,
            ),
    ) {
        if (node.isDirectory) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Spacer(Modifier.width(24.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = node.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isActive) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Flattens the tree into a linear list of (node, depth) pairs, respecting
 * which folders are currently expanded.
 */
private fun flattenTree(
    nodes: List<FileNode>,
    expandedPaths: Set<String>,
    depth: Int = 0,
): List<Pair<FileNode, Int>> = buildList {
    for (node in nodes) {
        add(node to depth)
        if (node.isDirectory && node.path in expandedPaths) {
            addAll(flattenTree(node.children.orEmpty(), expandedPaths, depth + 1))
        }
    }
}
