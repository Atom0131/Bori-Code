package com.bori.app.ui.linux

import com.bori.app.ui.common.readableWidth
import com.bori.app.ui.common.ShellContentInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bori.core.linux.CodingCli
import com.bori.core.linux.DistroStatus
import com.bori.core.linux.InstallState

/** What a confirm dialog is about to do. */
private data class PendingAction(val distroId: String, val name: String, val reinstall: Boolean)

/** Confirming removal of a CLI from a distro. */
private data class PendingCliRemoval(val distroId: String, val cli: CodingCli)

/**
 * More → Linux environment: install, set up, reinstall or delete the Linux distros the
 * terminal and the coding CLIs run in.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LinuxSetupScreen(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    viewModel: LinuxSetupViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val freeBytes by viewModel.freeBytes.collectAsStateWithLifecycle()
    val state by viewModel.installState.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<PendingAction?>(null) }
    var confirmCliRemoval by remember { mutableStateOf<PendingCliRemoval?>(null) }
    val busy = state is InstallState.Running

    Scaffold(

        contentWindowInsets = ShellContentInsets,
        topBar = {
            TopAppBar(
                title = { Text("Linux environment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .readableWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A Linux system inside Bori Code for the terminal and the coding CLIs (Claude Code, Antigravity). " +
                    "Your projects stay where they are and show up in it as ~/workspace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${formatMb(freeBytes)} free on this device", style = MaterialTheme.typography.labelLarge)

            rows.forEach { row ->
                val d = row.distro
                val archive = d.archiveForDevice()
                val mine = (state as? InstallState.Running)?.let { it.distroId == d.id && it.cliId == null } == true
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (row.isDefault && row.status == DistroStatus.INSTALLED) {
                                AssistChip(onClick = {}, label = { Text("Default") })
                            }
                        }
                        Text(d.summary, style = MaterialTheme.typography.bodySmall)
                        Text(
                            when (row.status) {
                                DistroStatus.NOT_INSTALLED -> if (archive == null) {
                                    "Not available for this device's CPU"
                                } else {
                                    "${formatMb(archive.sizeBytes)} download, about ${d.installedSizeMb} MB after setup"
                                }
                                DistroStatus.SETUP_INCOMPLETE -> "Setup didn't finish. Retry to install the packages."
                                DistroStatus.INSTALLED -> "Installed" + (row.sizeBytes?.let { ", ${formatMb(it)} on disk" } ?: "")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (row.status == DistroStatus.SETUP_INCOMPLETE) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )

                        if (mine) {
                            ProgressBlock(state as InstallState.Running, onStop = viewModel::cancel)
                        }
                        (state as? InstallState.Failed)?.takeIf { it.distroId == d.id && it.cliId == null }?.let { failed ->
                            Text(failed.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            LogTail(failed.log)
                            TextButton(onClick = viewModel::dismissResult) { Text("Dismiss") }
                        }

                        if (!mine) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (row.status) {
                                    DistroStatus.NOT_INSTALLED -> Button(
                                        onClick = { viewModel.install(d.id) },
                                        enabled = !busy && archive != null,
                                    ) { Text("Install") }
                                    DistroStatus.SETUP_INCOMPLETE -> {
                                        Button(onClick = { viewModel.retrySetup(d.id) }, enabled = !busy) { Text("Retry setup") }
                                        OutlinedButton(
                                            onClick = { confirm = PendingAction(d.id, d.name, reinstall = false) },
                                            enabled = !busy,
                                        ) { Text("Delete") }
                                    }
                                    DistroStatus.INSTALLED -> {
                                        if (!row.isDefault) {
                                            Button(onClick = { viewModel.makeDefault(d.id) }, enabled = !busy) { Text("Make default") }
                                        }
                                        OutlinedButton(
                                            onClick = { confirm = PendingAction(d.id, d.name, reinstall = true) },
                                            enabled = !busy,
                                        ) { Text("Reinstall") }
                                        OutlinedButton(
                                            onClick = { confirm = PendingAction(d.id, d.name, reinstall = false) },
                                            enabled = !busy,
                                        ) { Text("Delete") }
                                    }
                                }
                            }
                        }

                        if (row.clis.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text("Coding CLIs", style = MaterialTheme.typography.titleSmall)
                            row.clis.forEach { cliRow ->
                                CliSection(
                                    row = cliRow,
                                    state = state,
                                    distroId = d.id,
                                    busy = busy,
                                    onInstall = { viewModel.installCli(d.id, cliRow.cli) },
                                    onOpen = {
                                        viewModel.openCli(d.id, cliRow.cli)
                                        onOpenTerminal()
                                    },
                                    onRemove = { confirmCliRemoval = PendingCliRemoval(d.id, cliRow.cli) },
                                    onStop = viewModel::cancel,
                                    onDismiss = viewModel::dismissResult,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                "Only distros that don't do age verification are offered. Installs keep going if you leave Bori Code; the notification shows their progress.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    confirmCliRemoval?.let { pending ->
        CliRemovalDialog(
            pending = pending,
            onConfirm = {
                viewModel.removeCli(pending.distroId, pending.cli)
                confirmCliRemoval = null
            },
            onDismiss = { confirmCliRemoval = null },
        )
    }

    confirm?.let { action ->
        val tabs = viewModel.openTabs(action.distroId)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (action.reinstall) "Reinstall ${action.name}?" else "Delete ${action.name}?") },
            text = {
                Text(
                    "This erases everything inside ${action.name}: installed tools, settings, and the Claude Code " +
                        "and Antigravity sign-ins (you'd sign in again). Your projects aren't affected." +
                        if (tabs > 0) "\n\n$tabs open terminal tab(s) in ${action.name} will close." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (action.reinstall) viewModel.reinstall(action.distroId) else viewModel.delete(action.distroId)
                    confirm = null
                }) { Text(if (action.reinstall) "Reinstall" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CliRemovalDialog(pending: PendingCliRemoval, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${pending.cli.title}?") },
        text = {
            Text(
                "Removes the program. Your sign-in stays inside Linux (Bori Code never touches it); to sign out, " +
                    "use the CLI's own logout before removing it.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CliSection(
    row: CliRow,
    state: InstallState,
    distroId: String,
    busy: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cli = row.cli
    val running = (state as? InstallState.Running)?.takeIf { it.distroId == distroId && it.cliId == cli.id }
    val failed = (state as? InstallState.Failed)?.takeIf { it.distroId == distroId && it.cliId == cli.id }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(cli.title, style = MaterialTheme.typography.bodyLarge)
        Text(cli.summary, style = MaterialTheme.typography.bodySmall)
        Text(
            when {
                !row.checked -> "Checking…"
                row.version != null -> "Installed: ${row.version}"
                else -> "Not installed"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (running != null) {
            ProgressBlock(running, onStop = onStop)
        } else {
            failed?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                LogTail(it.log)
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            if (row.checked) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.version == null) {
                        Button(onClick = onInstall, enabled = !busy) { Text("Install") }
                    } else {
                        Button(onClick = onOpen) { Text("Open") }
                        OutlinedButton(onClick = onInstall, enabled = !busy) { Text("Update") }
                        OutlinedButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
                    }
                }
                if (row.version != null) {
                    Text(
                        "Open starts it in a terminal tab. The first time, sign in there; the sign-in stays inside Linux.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBlock(state: InstallState.Running, onStop: () -> Unit) {
    val step = state.step
    Text(
        when (step) {
            is InstallState.Step.Downloading -> "Downloading… ${formatMb(step.bytes)} of ${formatMb(step.total)}"
            InstallState.Step.Verifying -> "Checking the download…"
            is InstallState.Step.Extracting -> "Unpacking… ${step.files} files"
            InstallState.Step.Packages -> "Installing packages (Node, git, ripgrep, …)"
            InstallState.Step.Deleting -> "Deleting…"
            InstallState.Step.CliInstalling -> "Installing… (this can take a few minutes)"
            InstallState.Step.CliRemoving -> "Removing…"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (step is InstallState.Step.Downloading && step.total > 0) {
        LinearProgressIndicator(progress = { step.bytes.toFloat() / step.total }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    LogTail(state.log)
    if (step != InstallState.Step.Deleting && step != InstallState.Step.CliRemoving) {
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

@Composable
private fun LogTail(log: List<String>) {
    if (log.isEmpty()) return
    Text(
        log.takeLast(8).joinToString("\n"),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 8,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(8.dp),
    )
}

private fun formatMb(bytes: Long): String = "${bytes / (1024 * 1024)} MB"
