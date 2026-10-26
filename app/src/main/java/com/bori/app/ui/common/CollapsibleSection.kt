package com.bori.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's collapsible section: a tappable header (icon, title, one-line summary of the
 * current state) that shows [content] when expanded. Open/closed survives rotation and folding.
 * Started in Settings; reused on Git and the chat model picker so the screens look alike.
 */
@Composable
fun CollapsibleSection(
    title: String,
    summary: String,
    icon: ImageVector?,
    initiallyExpanded: Boolean = false,
    contentSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    CollapsibleSection(
        title, summary, icon, expanded, { expanded = !expanded },
        contentSpacing = contentSpacing, content = content,
    )
}

/** State-hoisted form, for callers that decide which sections are open (e.g. one at a time). */
@Composable
fun CollapsibleSection(
    title: String,
    summary: String,
    icon: ImageVector?,
    expanded: Boolean,
    onToggle: () -> Unit,
    horizontalPadding: Dp = 0.dp,
    contentSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionHeader(
        title = title,
        summary = summary,
        icon = icon,
        trailing = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        onClick = onToggle,
        horizontalPadding = horizontalPadding,
    )
    AnimatedVisibility(visible = expanded) {
        Column(
            modifier = Modifier.padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
            content = content,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = horizontalPadding))
}

/** A section-style row that opens another screen instead of expanding. */
@Composable
fun LinkRow(title: String, summary: String, icon: ImageVector?, onClick: () -> Unit) {
    SectionHeader(title, summary, icon, Icons.Default.ChevronRight, onClick)
    HorizontalDivider()
}

@Composable
fun SectionHeader(
    title: String,
    summary: String,
    icon: ImageVector?,
    trailing: ImageVector,
    onClick: () -> Unit,
    horizontalPadding: Dp = 0.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
