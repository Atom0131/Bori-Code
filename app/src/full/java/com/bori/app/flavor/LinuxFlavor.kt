package com.bori.app.flavor

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bori.app.service.LinuxInstallService
import com.bori.app.terminal.LinuxShells
import com.bori.app.terminal.ProotShells
import com.bori.app.ui.common.LinkRow
import com.bori.app.ui.linux.LinuxSetupScreen
import com.bori.app.ui.linux.LinuxSetupViewModel
import com.bori.core.linux.DistroInstaller
import com.bori.core.linux.DistroStatus
import com.bori.core.linux.InstallState
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Full build: how the Linux environment plugs into the shared app. The Play build's
// LinuxFlavor.kt has the same functions as no-ops, so none of :core:linux ships there.

/** Whether this build has the Linux environment: the one switch shared code checks. */
const val HAS_LINUX = true

@Module
@InstallIn(SingletonComponent::class)
abstract class LinuxShellsModule {
    @Binds
    abstract fun shells(impl: ProotShells): LinuxShells
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LinuxEntryPoint {
    fun distroInstaller(): DistroInstaller
}

/** A distro or CLI job runs in a foreground service, so it survives leaving the app. */
fun startLinuxJobs(app: Application, scope: CoroutineScope) {
    val installer = EntryPointAccessors.fromApplication(app, LinuxEntryPoint::class.java).distroInstaller()
    scope.launch {
        var running = false
        installer.state.collect { state ->
            val now = state is InstallState.Running
            if (now && !running) LinuxInstallService.start(app)
            running = now
        }
    }
}

/** More → Linux environment, summarizing installed distros and CLIs. */
@Composable
fun LinuxMoreRow(onOpenLinux: () -> Unit) {
    val linuxViewModel: LinuxSetupViewModel = hiltViewModel()
    val rows by linuxViewModel.rows.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { linuxViewModel.refresh() }
    val installed = rows.filter { it.status == DistroStatus.INSTALLED }
    val linuxSummary = if (installed.isEmpty()) {
        "Not set up · runs Claude Code, Antigravity and other CLIs"
    } else {
        installed.joinToString(" · ") { row ->
            val clis = row.clis.filter { it.checked && it.version != null }.joinToString(", ") { it.cli.title }
            if (clis.isEmpty()) row.distro.name else "${row.distro.name}: $clis"
        }
    }
    LinkRow("Linux environment", linuxSummary, Icons.Default.Terminal, onOpenLinux)
}

/** The Settings-like Linux environment screen, at route "linux". */
fun NavGraphBuilder.linuxRoute(onBack: () -> Unit, onOpenTerminal: () -> Unit) {
    composable("linux") { LinuxSetupScreen(onBack = onBack, onOpenTerminal = onOpenTerminal) }
}
