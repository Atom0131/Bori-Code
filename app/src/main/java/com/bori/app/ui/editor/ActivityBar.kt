package com.bori.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun ActivityBar(
    activePanel: SidebarPanel?,
    onPanelClick: (SidebarPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(48.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        ActivityBarItem(
            icon = Icons.Default.Folder,
            label = "Explorer",
            isActive = activePanel == SidebarPanel.EXPLORER,
            onClick = { onPanelClick(SidebarPanel.EXPLORER) },
        )
        ActivityBarItem(
            icon = Icons.Default.Search,
            label = "Search",
            isActive = activePanel == SidebarPanel.SEARCH,
            onClick = { onPanelClick(SidebarPanel.SEARCH) },
        )
        ActivityBarItem(
            icon = Icons.Default.AccountTree,
            label = "Source Control",
            isActive = activePanel == SidebarPanel.GIT,
            onClick = { onPanelClick(SidebarPanel.GIT) },
        )
    }
}

@Composable
private fun ActivityBarItem(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.width(48.dp)) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .align(Alignment.CenterStart)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
