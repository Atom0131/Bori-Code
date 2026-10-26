package com.bori.app.ui.linux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.project.ProjectStateHolder
import com.bori.app.terminal.ShellKind
import com.bori.app.terminal.TerminalSessionManager
import com.bori.core.linux.CodingCli
import com.bori.core.linux.Distro
import com.bori.core.linux.DistroCatalog
import com.bori.core.linux.DistroInstaller
import com.bori.core.linux.DistroStatus
import com.bori.core.linux.InstallState
import com.bori.core.linux.ProotLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
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
)

@HiltViewModel
class LinuxSetupViewModel @Inject constructor(
    private val installer: DistroInstaller,
    private val proot: ProotLauncher,
    private val terminals: TerminalSessionManager,
    private val projectStateHolder: ProjectStateHolder,
) : ViewModel() {

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
    }

    fun refresh() {
        val default = proot.defaultDistro
        val rows = DistroCatalog.all.map { d ->
            val status = installer.status(d.id)
            val clis = if (status == DistroStatus.INSTALLED) CodingCli.entries.map { CliRow(it, null, checked = false) } else emptyList()
            DistroRow(d, status, isDefault = d.id == default, sizeBytes = null, clis = clis)
        }
        _rows.value = rows
        _freeBytes.value = installer.freeBytes()
        viewModelScope.launch {
            rows.filter { it.status != DistroStatus.NOT_INSTALLED }.forEach { row ->
                val size = installer.sizeOnDisk(row.distro.id)
                updateRow(row.distro.id) { it.copy(sizeBytes = size) }
            }
        }
        // Each probe starts the guest once (about a second), so they run one after another.
        viewModelScope.launch {
            rows.filter { it.status == DistroStatus.INSTALLED }.forEach { row ->
                CodingCli.entries.forEach { cli ->
                    val version = installer.cliVersion(row.distro.id, cli)
                    updateRow(row.distro.id) { r ->
                        r.copy(clis = r.clis.map { if (it.cli == cli) it.copy(version = version, checked = true) else it })
                    }
                }
            }
        }
    }

    private fun updateRow(distroId: String, change: (DistroRow) -> DistroRow) {
        _rows.value = _rows.value.map { if (it.distro.id == distroId) change(it) else it }
    }

    fun installCli(distroId: String, cli: CodingCli) = installer.installCli(distroId, cli)

    fun removeCli(distroId: String, cli: CodingCli) = installer.removeCli(distroId, cli)

    /**
     * Opens a Linux tab in [distroId] (in the active project when there is one) that starts [cli].
     * Its first run is where the user signs in.
     */
    fun openCli(distroId: String, cli: CodingCli) {
        val projectDir = projectStateHolder.activeProject.value?.rootPath?.let(::File)
        terminals.open(ShellKind.Linux(distroId), hostCwd = projectDir, startCommand = cli.command, title = cli.title)
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
