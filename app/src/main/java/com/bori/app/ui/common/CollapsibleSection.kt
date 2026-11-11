package com.bori.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.bori.theme.kit.BoriCard
import com.bori.theme.kit.SettingsRowIcon

// Settings, Git and More are built from these. They follow the Bori Habit Tracker's settings kit
// (via the theme engine): one card per section, a tinted round icon, a live one-line summary, and no
// divider lines (DESIGN.md: separate by tone and spacing). Glass under Crystal, tonal cards otherwise.

/**
 * The app's collapsible section: a card whose header (icon, title, one-line summary of the current
 * state) opens [content] inside it. Open/closed survives rotation and folding.
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
    BoriCard(modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 6.dp)) {
        Column(Modifier.animateContentSize(tween(250, easing = FastOutSlowInEasing))) {
            SectionHeader(
                title = title,
                summary = summary,
                icon = icon,
                trailing = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                onClick = onToggle,
                horizontalPadding = 16.dp,
            )
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing),
                    content = content,
                )
            }
        }
    }
}

/** A row that opens another screen instead of expanding; put several in a [LinkGroup]. */
@Composable
fun LinkRow(title: String, summary: String, icon: ImageVector?, onClick: () -> Unit) {
    SectionHeader(title, summary, icon, Icons.Default.ChevronRight, onClick, horizontalPadding = 16.dp)
}

/** One card holding [LinkRow]s, like a settings group in the habit tracker. */
@Composable
fun LinkGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoriCard(modifier = modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(vertical = 6.dp), content = content)
    }
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
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsRowIcon(icon)
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}
