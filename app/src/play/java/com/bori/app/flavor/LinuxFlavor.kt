package com.bori.app.flavor

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import com.bori.app.terminal.LinuxShells
import com.bori.app.terminal.ShellCommand
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

// Play build: no Linux environment (Play doesn't allow downloading and running native code).
// Same functions as the full build's LinuxFlavor.kt, doing nothing.

/** Whether this build has the Linux environment: the one switch shared code checks. */
const val HAS_LINUX = false

class NoLinuxShells @Inject constructor() : LinuxShells {
    override fun installedDistros(): List<String> = emptyList()
    override fun shellCommand(distroId: String, hostCwd: File?, startCommand: String?): ShellCommand? = null
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LinuxShellsModule {
    @Binds
    abstract fun shells(impl: NoLinuxShells): LinuxShells
}

@Suppress("UNUSED_PARAMETER")
fun startLinuxJobs(app: Application, scope: CoroutineScope) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
fun LinuxMoreRow(onOpenLinux: () -> Unit) = Unit

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.linuxRoute(onBack: () -> Unit, onOpenTerminal: () -> Unit) = Unit
