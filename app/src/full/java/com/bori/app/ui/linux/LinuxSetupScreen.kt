package com.bori.app.ui.linux

import com.bori.app.ui.common.GlassCard
import com.bori.theme.util.BoriAlertDialog
import com.bori.app.ui.common.BoriTopBar
import com.bori.theme.components.crystalScaffoldContentColor
import com.bori.theme.components.crystalScaffoldColor
import com.bori.app.ui.common.readableWidth
import com.bori.app.ui.common.ShellContentInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    var addingCustomTo by remember { mutableStateOf<String?>(null) }
    val customMessage by viewModel.customMessage.collectAsStateWithLifecycle()
    val githubAuth by viewModel.githubAuthState.collectAsStateWithLifecycle()
    val busy = state is InstallState.Running
    // Back from a GitHub sign-in tab or the browser: show the new account. Back from the browser
    // without GitHub's redirect means the approve page was left unfinished.
    LifecycleResumeEffect(Unit) {
        viewModel.cancelGitHubIfWaiting()
        viewModel.refreshGitHub()
        onPauseOrDispose {}
    }

    Scaffold(

        contentWindowInsets = ShellContentInsets, containerColor = crystalScaffoldColor(), contentColor = crystalScaffoldContentColor(),
        topBar = {
            BoriTopBar(
                title = "Linux environment",
                onBack = onBack,
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
                "A Linux system inside Bori Code for the terminal and the coding CLIs (Claude Code, Antigravity, Codex, Gemini, " +
                    "OpenCode, Kimi and more, or your own). " +
                    "Your projects stay where they are and show up in it as ~/workspace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${formatMb(freeBytes)} free on this device", style = MaterialTheme.typography.labelLarge)
            com.bori.app.ui.common.LocalNetworkNotice(
                "Linux programs need it to reach your PC or other machines (ssh, git, Tailscale). The internet works without it.",
            )

            rows.forEach { row ->
                val d = row.distro
                val archive = d.archiveForDevice()
                val mine = (state as? InstallState.Running)?.let { it.distroId == d.id && it.cliId == null } == true
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (row.isDefault && row.status == DistroStatus.INSTALLED) {
                                Text("Default", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(d.summary, style = MaterialTheme.typography.bodySmall)
                        if (!d.offered) {
                            Text(
                                "No longer offered for new installs: ${d.name} hasn't declared it won't do age verification. " +
                                    "Yours keeps working.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
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
                    }
                }

                if (row.clis.isNotEmpty()) {
                    val installed = row.clis.filter { it.checked && it.version != null && !it.cli.custom }
                    val others = row.clis.filter { !it.cli.custom && it !in installed }
                    val own = row.clis.filter { it.cli.custom }
                    // Installed CLIs first; the rest of the catalog folds away once one is installed.
                    var showAll by rememberSaveable(d.id) { mutableStateOf(false) }
                    val expanded = showAll || installed.isEmpty()
                    val cliSection: @Composable (CliRow) -> Unit = { cliRow ->
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
                    SectionCard("Coding CLIs") {
                        installed.forEach { cliSection(it) }
                        if (installed.isNotEmpty() && others.isNotEmpty()) {
                            TextButton(onClick = { showAll = !showAll }) {
                                Text(if (showAll) "Hide the other CLIs" else "Show ${others.size} more CLIs to install")
                            }
                        }
                        if (expanded) {
                            others.groupBy { it.cli.group }.forEach { (group, groupRows) ->
                                Text(
                                    group.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                groupRows.forEach { cliSection(it) }
                            }
                            Text(
                                "DeepSeek, GLM and MiniMax have no CLI of their own: use OpenCode or Crush with their API key.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SectionCard(CodingCli.Group.CUSTOM.title) {
                        Text(
                            "Add a CLI Bori Code doesn't list: upload a program file, name one you installed yourself, " +
                                "or give its install command. Not guaranteed to work.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        own.forEach { cliSection(it) }
                        OutlinedButton(onClick = { addingCustomTo = d.id }, enabled = !busy) { Text("Add your own…") }
                    }
                    if (row.tools.isNotEmpty()) SectionCard(CodingCli.Group.TOOLS.title) {
                        Text(
                            "What agents reach for when they build and run code. Without them an agent stops with " +
                                "errors like \"pip: command not found\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val bundleRunning = (state as? InstallState.Running)
                            ?.takeIf { it.distroId == d.id && it.cliId == CodingCli.TOOLS_BUNDLE_ID }
                        val bundleFailed = (state as? InstallState.Failed)
                            ?.takeIf { it.distroId == d.id && it.cliId == CodingCli.TOOLS_BUNDLE_ID }
                        val missing = row.tools.count { it.checked && it.version == null }
                        if (bundleRunning != null) {
                            ProgressBlock(bundleRunning, onStop = viewModel::cancel)
                        } else {
                            bundleFailed?.let {
                                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                LogTail(it.log)
                                TextButton(onClick = viewModel::dismissResult) { Text("Dismiss") }
                            }
                            if (missing > 0) {
                                Button(onClick = { viewModel.installMissingTools(d.id) }, enabled = !busy) {
                                    Text("Install all missing ($missing)")
                                }
                            }
                        }
                        row.tools.forEach { cliSection(it) }
                    }
                    SectionCard("GitHub") {
                        GitHubSection(
                            row = row,
                            auth = githubAuth,
                            browserSignIn = viewModel.githubBrowserSignIn,
                            onSignIn = { switchAccount -> viewModel.signInGitHub(d.id, switchAccount) },
                            onUseCode = {
                                viewModel.openGitHub(d.id)
                                onOpenTerminal()
                            },
                            onDismissError = viewModel::dismissGitHubError,
                            onSignOut = {
                                viewModel.openGitHub(d.id, signOut = true)
                                onOpenTerminal()
                            },
                        )
                    }
                }
            }

            Text(
                "Only distros that have declared they won't do age verification are offered. Installs keep going if you leave Bori Code; the notification shows their progress.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    addingCustomTo?.let { distroId ->
        AddCustomCliDialog(
            suggestName = viewModel::suggestedName,
            onUpload = { uri, name, title -> viewModel.addProgramFile(distroId, uri, name, title) },
            onCommand = { title, command -> viewModel.addCommandCli(distroId, title, command) },
            onScript = { title, command, install, uninstall -> viewModel.addScriptCli(distroId, title, command, install, uninstall) },
            onDismiss = { addingCustomTo = null },
        )
    }

    customMessage?.let { message ->
        BoriAlertDialog(
            onDismissRequest = viewModel::dismissCustomMessage,
            title = { Text("Your own program") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissCustomMessage) { Text("OK") } },
        )
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
        BoriAlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (action.reinstall) "Reinstall ${action.name}?" else "Delete ${action.name}?") },
            text = {
                Text(
                    "This erases everything inside ${action.name}: installed tools, settings, and the coding CLIs' " +
                        "sign-ins (you'd sign in again). Your projects aren't affected." +
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

/** One GitHub sign-in inside the distro, shared by git, gh and both coding CLIs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GitHubSection(
    row: DistroRow,
    auth: GitHubAuthState,
    browserSignIn: Boolean,
    onSignIn: (switchAccount: Boolean) -> Unit,
    onUseCode: () -> Unit,
    onDismissError: () -> Unit,
    onSignOut: () -> Unit,
) {
    val login = row.githubLogin
    val working = when (auth) {
        is GitHubAuthState.Waiting -> auth.distroId == row.distro.id
        is GitHubAuthState.Finishing -> auth.distroId == row.distro.id
        else -> false
    }
    val error = (auth as? GitHubAuthState.Failed)?.takeIf { it.distroId == row.distro.id }?.message
    // Without the OAuth App keys (forks, CI builds) the buttons run gh's code sign-in.
    val signIn: (Boolean) -> Unit = if (browserSignIn) onSignIn else { _ -> onUseCode() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "One sign-in for the coding CLIs and git in the terminal, so they can clone, push and create your repos. " +
                "Your GitHub login stays inside Linux.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            when {
                auth is GitHubAuthState.Waiting && working -> "Waiting for GitHub… approve Bori Code in your browser."
                auth is GitHubAuthState.Finishing && working -> "Finishing the sign-in…"
                !row.githubChecked -> "Checking…"
                login != null -> "Signed in as $login"
                else -> "Not signed in"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (row.githubChecked && !working) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (login == null) {
                    Button(onClick = { onDismissError(); signIn(false) }) { Text("Sign in to GitHub") }
                } else {
                    OutlinedButton(onClick = { onDismissError(); signIn(true) }) { Text("Switch account") }
                    OutlinedButton(onClick = onSignOut) { Text("Sign out") }
                }
                if (browserSignIn) {
                    TextButton(onClick = { onDismissError(); onUseCode() }) { Text("Use a code instead") }
                }
            }
        }
    }
}

@Composable
private fun CliRemovalDialog(pending: PendingCliRemoval, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BoriAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${pending.cli.title}?") },
        text = {
            val installer = pending.cli.installer
            Text(
                if (installer is CodingCli.Installer.Custom && installer.uninstall.isBlank()) {
                    "Takes it off Bori Code's list. The program itself stays in Linux; remove it there if you want it gone."
                } else {
                    "Removes the program. Your sign-in stays inside Linux (Bori Code never touches it); to sign out, " +
                        "use the CLI's own logout before removing it."
                },
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
        if (cli.custom) {
            Text(
                "Your own · not guaranteed to work · runs `${cli.command}`",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            when {
                !row.checked -> "Checking…"
                row.version == "installed" -> "Installed"
                row.version != null -> "Installed: ${row.version}"
                cli.custom && !cli.installable -> "Not found in Linux"
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
                        if (cli.installable) FilledTonalButton(onClick = onInstall, enabled = !busy) { Text("Install") }
                        if (cli.custom) OutlinedButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
                    } else {
                        if (!cli.tool) Button(onClick = onOpen) { Text("Open") }
                        if (cli.installable) OutlinedButton(onClick = onInstall, enabled = !busy) { Text("Update") }
                        if (!cli.tool) OutlinedButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
                    }
                }
                if (row.version != null && !cli.custom && !cli.tool) {
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

/** One part of a distro's setup (its CLIs, your own programs, coding tools, GitHub) on its own card. */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** The three ways to add your own CLI. */
private enum class CustomKind(val label: String) { FILE("Upload a file"), COMMAND("By command"), SCRIPT("Install command") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCustomCliDialog(
    suggestName: (Uri) -> String,
    onUpload: (uri: Uri, name: String, title: String) -> Unit,
    onCommand: (title: String, command: String) -> Unit,
    onScript: (title: String, command: String, install: String, uninstall: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(CustomKind.FILE) }
    var title by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("") }
    var install by rememberSaveable { mutableStateOf("") }
    var uninstall by rememberSaveable { mutableStateOf("") }
    var file by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            file = uri
            if (command.isBlank()) command = suggestName(uri)
        }
    }
    val nameOk = com.bori.core.linux.CustomCliStore.SAFE_NAME.matches(command.trim())
    val ready = when (kind) {
        CustomKind.FILE -> file != null && nameOk
        CustomKind.COMMAND -> command.isNotBlank()
        CustomKind.SCRIPT -> command.isNotBlank() && install.isNotBlank()
    }
    BoriAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add your own CLI") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CustomKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                    }
                }
                Text(
                    when (kind) {
                        CustomKind.FILE -> "A Linux program (for this phone's CPU) or a script starting with #!. It's copied to /usr/local/bin."
                        CustomKind.COMMAND -> "Something you installed yourself in the terminal (npm, pip, cargo, curl…). Give the command that starts it."
                        CustomKind.SCRIPT -> "Bori Code runs your install command as root in Linux, like it does for the listed CLIs. Update runs it again."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (kind == CustomKind.FILE) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text(if (file == null) "Pick a file…" else "Picked: ${suggestName(file!!)}")
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name shown in Bori Code") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(if (kind == CustomKind.FILE) "Command name" else "Command that starts it") },
                    singleLine = true,
                    isError = kind == CustomKind.FILE && command.isNotBlank() && !nameOk,
                    supportingText = if (kind == CustomKind.FILE) {
                        { Text("Letters, numbers, dots and dashes") }
                    } else {
                        null
                    },
                )
                if (kind == CustomKind.SCRIPT) {
                    OutlinedTextField(
                        value = install,
                        onValueChange = { install = it },
                        label = { Text("Install command, e.g. npm i -g some-cli") },
                        minLines = 2,
                    )
                    OutlinedTextField(
                        value = uninstall,
                        onValueChange = { uninstall = it },
                        label = { Text("Uninstall command (optional)") },
                    )
                }
                Text(
                    "Not guaranteed to work: Bori Code's Linux runs under proot, and some programs need things it can't do.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    when (kind) {
                        CustomKind.FILE -> onUpload(file!!, command.trim(), title.trim())
                        CustomKind.COMMAND -> onCommand(title.trim(), command.trim())
                        CustomKind.SCRIPT -> onScript(title.trim(), command.trim(), install, uninstall)
                    }
                    onDismiss()
                },
            ) { Text(if (kind == CustomKind.SCRIPT) "Add and install" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
