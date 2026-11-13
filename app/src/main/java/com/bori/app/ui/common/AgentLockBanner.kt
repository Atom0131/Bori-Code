package com.bori.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bori.app.ui.workspace.LocalPaneVisible
import com.bori.app.ui.theme.Inter
import com.bori.theme.components.crystalTileSurface
import com.bori.theme.util.BoriAlertDialog
import androidx.compose.foundation.layout.Box
import com.bori.core.project.agent.AgentLock
import kotlinx.coroutines.delay

/** Calls [refresh] every few seconds while this pane is on screen; never in the background. */
@Composable
fun PollAgentLock(projectKey: Any?, refresh: () -> Unit) {
    val visible = LocalPaneVisible.current
    LaunchedEffect(projectKey, visible) {
        while (visible) {
            refresh()
            delay(5_000)
        }
    }
}

/** "Claude Code is working on this project": shown while another agent holds .bori/agent.lock. */
@Composable
fun AgentLockBanner(lock: AgentLock?, compact: Boolean = false) {
    if (lock == null) return
    val s = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 4.dp)
            .crystalTileSurface(fill = s.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = s.secondary, modifier = if (compact) Modifier.size(16.dp) else Modifier)
            Text(
                // Compact (little room above the keyboard): one line; the dialog has the full text.
                text = if (compact) "${lock.agent} is working on this project" else lockSummary(lock) + " Edits here may clash with its work.",
                maxLines = if (compact) 1 else Int.MAX_VALUE,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                // Inter, not bodySmall's font: the editor's code theme makes body text monospace.
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Inter),
                color = s.onSecondaryContainer,
            )
        }
    }
}

/** Asks before acting while another agent holds the lock. */
@Composable
fun AgentLockDialog(lock: AgentLock, action: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    BoriAlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Another agent is working", fontFamily = Inter) },
        text = { Text(lockSummary(lock) + " If you $action now, you may both edit the same files.", fontFamily = Inter) },
        confirmButton = { Button(onClick = onConfirm) { Text("${action.replaceFirstChar { it.uppercase() }} anyway") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private fun lockSummary(lock: AgentLock): String {
    val minutes = ((System.currentTimeMillis() - lock.startedMs) / 60_000).coerceAtLeast(0)
    val since = if (minutes < 1) "just now" else "$minutes min ago"
    val task = lock.task.takeIf { it.isNotBlank() }?.let { " on \"$it\"" }.orEmpty()
    return "${lock.agent} is working on this project$task (started $since)."
}
