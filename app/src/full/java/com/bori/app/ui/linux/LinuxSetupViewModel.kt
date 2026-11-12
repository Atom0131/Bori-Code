package com.bori.app.ui.linux

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.project.ProjectStateHolder
import com.bori.app.terminal.ShellKind
import com.bori.app.terminal.TerminalSessionManager
import com.bori.core.linux.CodingCli
import com.bori.core.linux.CustomCliStore
import com.bori.core.linux.Distro
import com.bori.core.linux.DistroCatalog
import com.bori.core.linux.DistroInstaller
import com.bori.core.linux.DistroStatus
import com.bori.core.linux.InstallState
import com.bori.core.linux.ProotLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** A coding CLI inside an installed distro. */
data class CliRow(
    val cli: CodingCli,
    /** Installed version; null when not installed. Ignored while [checked] is false. */
    val version: String?,
    val checked: Boolean,
)

/** One distro on the Linux environment screen. */
data class DistroRow(
    val distro: Distro,
    val status: DistroStatus,
    val isDefault: Boolean,
    /** Disk use when installed; null while it's being measured or when not installed. */
    val sizeBytes: Long?,
    /** Empty unless the distro is installed. */
    val clis: List<CliRow> = emptyList(),
    /** Coding tools (pip, gcc, …) in it; empty unless installed. */
    val tools: List<CliRow> = emptyList(),
    /** The GitHub account signed in inside it; null when not signed in. Ignored while [githubChecked] is false. */
    val githubLogin: String? = null,
    val githubChecked: Boolean = false,
)

@HiltViewModel
class LinuxSetupViewModel @Inject constructor(
    private val installer: DistroInstaller,
    private val proot: ProotLauncher,
    private val terminals: TerminalSessionManager,
    private val projectStateHolder: ProjectStateHolder,
    private val githubAuth: GitHubAuth,
    private val customClis: CustomCliStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** A note for the user after adding one of their own programs (a warning or an error); null when none. */
    private val _customMessage = MutableStateFlow<String?>(null)
    val customMessage: StateFlow<String?> = _customMessage.asStateFlow()

    fun dismissCustomMessage() {
        _customMessage.value = null
    }

    val githubAuthState: StateFlow<GitHubAuthState> = githubAuth.state

    /** False without the OAuth App keys: the card offers only gh's code sign-in. */
    val githubBrowserSignIn: Boolean get() = githubAuth.available

    val installState: StateFlow<InstallState> = installer.state

    private val _rows = MutableStateFlow<List<DistroRow>>(emptyList())
    val rows: StateFlow<List<DistroRow>> = _rows.asStateFlow()

    private val _freeBytes = MutableStateFlow(0L)
    val freeBytes: StateFlow<Long> = _freeBytes.asStateFlow()

    init {
        refresh()
        // Re-read statuses and sizes whenever a job finishes.
        viewModelScope.launch {
            var wasRunning = false
            installer.state.collect { state ->
                val running = state is InstallState.Running
                if (wasRunning && !running) refresh()
                wasRunning = running
            }
        }
        // Show the account once a browser sign-in has gone through to Linux.
        viewModelScope.launch {
            var wasFinishing = false
            githubAuth.state.collect { state ->
                if (wasFinishing && state !is GitHubAuthState.Finishing) refreshGitHub()
                wasFinishing = state is GitHubAuthState.Finishing
            }
        }
    }

    /** The built-ins offered in [distro] (Aider isn't on musl), then the user's own for it. */
    private fun clisFor(distro: Distro): List<CodingCli> =
        CodingCli.builtIns.filter { it.availableIn(distro) } + customClis.forDistro(distro.id)

    fun refresh() {
        val default = proot.defaultDistro
        val rows = DistroCatalog.all.mapNotNull { d ->
            val status = installer.status(d.id)
            if (!d.offered && status == DistroStatus.NOT_INSTALLED) return@mapNotNull null
            val clis = if (status == DistroStatus.INSTALLED) clisFor(d).map { CliRow(it, null, checked = false) } else emptyList()
            val tools = if (status == DistroStatus.INSTALLED) {
                CodingCli.tools.filter { it.availableIn(d) }.map { CliRow(it, null, checked = false) }
            } else {
                emptyList()
            }
            DistroRow(d, status, isDefault = d.id == default, sizeBytes = null, clis = clis, tools = tools)
        }
        _rows.value = rows
        _freeBytes.value = installer.freeBytes()
        viewModelScope.launch {
            rows.filter { it.status != DistroStatus.NOT_INSTALLED }.forEach { row ->
                val size = installer.sizeOnDisk(row.distro.id)
                updateRow(row.distro.id) { it.copy(sizeBytes = size) }
            }
        }
        // One guest start per distro checks every CLI (about a second, plus each installed CLI's --version).
        viewModelScope.launch {
            rows.filter { it.status == DistroStatus.INSTALLED }.forEach { row ->
                val versions = installer.cliVersions(row.distro.id, (row.clis + row.tools).map { it.cli })
                updateRow(row.distro.id) { r ->
                    r.copy(
                        clis = r.clis.map { it.copy(version = versions[it.cli.id], checked = true) },
                        tools = r.tools.map { it.copy(version = versions[it.cli.id], checked = true) },
                    )
                }
                val login = installer.githubLogin(row.distro.id)
                updateRow(row.distro.id) { it.copy(githubLogin = login, githubChecked = true) }
            }
        }
    }

    /** A free id for the user's own CLI named [name] in [distroId]; `my-` keeps it apart from the built-ins. */
    private fun customId(distroId: String, name: String): String {
        val base = "my-" + CustomCliStore.safeName(name).ifBlank { "program" }
        val taken = customClis.forDistro(distroId).map { it.id }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
    }

    /** Adds something the user installed themselves: [command] starts it. */
    fun addCommandCli(distroId: String, title: String, command: String) {
        if (command.isBlank()) return
        customClis.put(
            CustomCliStore.create(customId(distroId, title.ifBlank { command }), title, command, distroId, CodingCli.Installer.Custom("", "")),
        )
        refresh()
    }

    /** Adds the user's own CLI with their [install] script, and runs it (in the install job, with its log). */
    fun addScriptCli(distroId: String, title: String, command: String, install: String, uninstall: String) {
        if (command.isBlank() || install.isBlank()) return
        val cli = CustomCliStore.create(
            customId(distroId, title.ifBlank { command }), title, command, distroId, CodingCli.Installer.Custom(install.trim(), uninstall.trim()),
        )
        customClis.put(cli)
        refresh()
        installer.installCli(distroId, cli)
    }

    /** Copies the program file at [uri] into [distroId] as [name] (the command that starts it). */
    fun addProgramFile(distroId: String, uri: Uri, name: String, title: String) {
        viewModelScope.launch {
            val safe = CustomCliStore.safeName(name)
            val result = runCatching {
                val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
                installer.addProgramFile(distroId, safe, input)
            }
            result.onSuccess { warning ->
                val id = customClis.forDistro(distroId).firstOrNull { it.program == safe }?.id ?: customId(distroId, safe)
                customClis.put(CustomCliStore.create(id, title, safe, distroId, CodingCli.Installer.Uploaded))
                _customMessage.value = warning?.let { "$it It's added; Bori Code doesn't guarantee it works." }
                refresh()
            }.onFailure { e ->
                _customMessage.value = "Couldn't add the program: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** The name a picked file would get as a command: its file name, made safe. */
    fun suggestedName(uri: Uri): String {
        val display = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        return CustomCliStore.safeName(display.substringAfterLast('/'))
    }

    /** Re-reads the GitHub sign-in (after a sign-in tab); rows not checked yet are left to [refresh]. */
    fun refreshGitHub() {
        viewModelScope.launch {
            _rows.value.filter { it.githubChecked }.forEach { row ->
                val login = installer.githubLogin(row.distro.id)
                updateRow(row.distro.id) { it.copy(githubLogin = login) }
            }
        }
    }

    private fun updateRow(distroId: String, change: (DistroRow) -> DistroRow) {
        _rows.value = _rows.value.map { if (it.distro.id == distroId) change(it) else it }
    }

    fun installCli(distroId: String, cli: CodingCli) = installer.installCli(distroId, cli)

    /** Installs every coding tool [distroId] doesn't have yet, in one package-manager run. */
    fun installMissingTools(distroId: String) {
        val missing = _rows.value.firstOrNull { it.distro.id == distroId }?.tools
            ?.filter { it.checked && it.version == null }?.map { it.cli }.orEmpty()
        if (missing.isNotEmpty()) installer.installCli(distroId, CodingCli.toolsBundle(missing))
    }

    fun removeCli(distroId: String, cli: CodingCli) = installer.removeCli(distroId, cli)

    /**
     * Opens a Linux tab in [distroId] (in the active project when there is one) that starts [cli].
     * Its first run is where the user signs in.
     */
    fun openCli(distroId: String, cli: CodingCli) {
        val projectDir = projectStateHolder.activeProject.value?.rootPath?.let(::File)
        terminals.open(ShellKind.Linux(distroId), hostCwd = projectDir, startCommand = cli.command, title = cli.title)
    }

    /** GitHub's approve page in the browser ([GitHubAuth]); it comes back to Bori Code by itself. */
    fun signInGitHub(distroId: String, switchAccount: Boolean = false) = githubAuth.start(distroId, switchAccount)

    /** Back on this screen while the approve page was still open: the user left it unfinished. */
    fun cancelGitHubIfWaiting() = githubAuth.cancelIfWaiting()

    fun dismissGitHubError() = githubAuth.dismiss()

    /**
     * Opens a Linux tab running GuestPolicy's `bori-github` script: gh's code sign-in, or
     * sign-out when [signOut]. The status is re-read when the user comes back to this screen.
     */
    fun openGitHub(distroId: String, signOut: Boolean = false) {
        val command = if (signOut) "bori-github logout" else "bori-github"
        terminals.open(ShellKind.Linux(distroId), startCommand = command, title = "GitHub")
    }

    /** Terminal tabs open in [distroId]; they're closed before that distro is changed. */
    fun openTabs(distroId: String): Int = terminals.tabs.value.count { (it.kind as? ShellKind.Linux)?.distroId == distroId }

    fun install(distroId: String) {
        closeTabs(distroId)
        installer.install(distroId)
    }

    fun reinstall(distroId: String) {
        closeTabs(distroId)
        installer.reinstall(distroId)
    }

    fun retrySetup(distroId: String) {
        closeTabs(distroId)
        installer.retrySetup(distroId)
    }

    fun delete(distroId: String) {
        closeTabs(distroId)
        installer.delete(distroId)
    }

    fun makeDefault(distroId: String) {
        proot.defaultDistro = distroId
        refresh()
    }

    fun cancel() = installer.cancel()

    fun dismissResult() = installer.clearResult()

    private fun closeTabs(distroId: String) {
        terminals.tabs.value
            .filter { (it.kind as? ShellKind.Linux)?.distroId == distroId }
            .forEach { terminals.close(it.id) }
    }
}
