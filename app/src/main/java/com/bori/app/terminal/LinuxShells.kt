package com.bori.app.terminal

import java.io.File

/**
 * The Linux environment as the terminal sees it. Only the full build has one (ProotShells, bound
 * in flavor/LinuxFlavor.kt); the Play build binds an empty one, so no Linux code ships there.
 */
interface LinuxShells {
    fun installedDistros(): List<String>

    /**
     * A login shell in [distroId], in [hostCwd]'s guest path, running [startCommand] first if
     * given. Null when this build has no Linux environment.
     */
    fun shellCommand(distroId: String, hostCwd: File?, startCommand: String?): ShellCommand?
}

/** A host process to start: [executable] with the full [argv] (argv[0] included), [env] and [cwd]. */
data class ShellCommand(
    val executable: String,
    val argv: List<String>,
    val env: List<String>,
    val cwd: String,
)
