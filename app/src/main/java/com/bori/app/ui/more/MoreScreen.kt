package com.bori.app.ui.more

import com.bori.app.ui.common.ShellContentInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bori.app.ui.common.LinkRow
import com.bori.app.ui.common.BoriTopBar
import com.bori.app.ui.common.ReadableColumn
import com.bori.app.flavor.LinuxMoreRow

/** The More tab: the places you visit now and then (settings, Linux setup, logs, licenses). */
@Composable
fun MoreScreen(
    onOpenSettings: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Scaffold(contentWindowInsets = ShellContentInsets, topBar = { BoriTopBar(title = "More") }) { padding ->
        ReadableColumn(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            LinkRow("Settings", "AI models and keys, appearance, build, backup", Icons.Default.Settings, onOpenSettings)
            LinuxMoreRow(onOpenLinux)
            LinkRow("App logs", "Logs from Bori Code and the apps you build", Icons.Default.BugReport, onOpenLogs)
            LinkRow("About & licenses", "Version and open-source notices", Icons.Default.Info, onOpenAbout)
        }
    }
}
