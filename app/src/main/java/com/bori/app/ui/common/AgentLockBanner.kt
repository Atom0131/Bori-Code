package com.bori.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bori.app.ui.workspace.LocalPaneVisible
import com.bori.app.ui.theme.Inter
import com.bori.app.ui.theme.isAppInDarkTheme
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
fun AgentLockBanner(lock: AgentLock?) {
    if (lock == null) return
    val dark = isAppInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (dark) Color(0xFF1E2A33) else Color(0xFFE3F2FD),
        border = BorderStroke(1.dp, if (dark) Color(0xFF2F5A78) else Color(0xFF90CAF9)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = if (dark) Color(0xFF90CAF9) else Color(0xFF1565C0))
            Text(
                text = lockSummary(lock) + " Edits here may clash with its work.",
                // Inter, not bodySmall's font: the editor's code theme makes body text monospace.
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Inter),
                color = if (dark) Color(0xFFBBDEFB) else Color(0xFF0D47A1),
            )
        }
    }
}

/** Asks before acting while another agent holds the lock. */
@Composable
fun AgentLockDialog(lock: AgentLock, action: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
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
