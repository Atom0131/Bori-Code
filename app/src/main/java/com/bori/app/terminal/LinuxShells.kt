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
     * given. [tabId] is passed in as BORI_TAB, so a coding CLI can report its state for that tab.
     * Null when this build has no Linux environment.
     */
    fun shellCommand(distroId: String, hostCwd: File?, startCommand: String?, tabId: Int): ShellCommand?

    /** The coding CLIs installed in [distroId], for the terminal's launcher. */
    fun installedClis(distroId: String): List<CliLauncher>

    /** [distroId]'s /root on the host, where the CLIs keep their state; null without Linux. */
    fun guestHome(distroId: String): File?

    /**
     * Called (on the main thread) when a CLI in tab `tabId` reports `state` through Bori's status
     * script ("working", "notify", "done"), with the hook's JSON.
     */
    fun setAgentStateListener(listener: (tabId: Int, state: String, json: String) -> Unit)
}

/** A coding CLI the terminal can open in a new tab: [command] runs in a Linux shell titled [title]. */
data class CliLauncher(val id: String, val title: String, val command: String)

/** A host process to start: [executable] with the full [argv] (argv[0] included), [env] and [cwd]. */
data class ShellCommand(
    val executable: String,
    val argv: List<String>,
    val env: List<String>,
    val cwd: String,
)
