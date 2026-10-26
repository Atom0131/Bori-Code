package com.bori.core.linux

import android.content.Context
import com.bori.core.project.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject
import javax.inject.Singleton

/** A host process to start: [executable] with the full [argv] (argv[0] included), [env] and [cwd]. */
data class HostCommand(
    val executable: String,
    val argv: List<String>,
    val env: List<String>,
    val cwd: String,
)

/**
 * Builds the proot command line that runs a program inside a Linux rootfs. The one place that
 * knows proot's flags — the terminal and the CLI agents both start guests through here.
 *
 * proot ships in the full flavor's jniLibs as lib*.so, built from source by scripts/build_proot.sh
 * (talloc linked in, so it loads no libraries of ours), because nativeLibraryDir is the only place
 * an app targeting API 29+ may exec from.
 */
@Singleton
class ProotLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val libDir: String get() = context.applicationInfo.nativeLibraryDir

    /**
     * Root of everything Linux-related: one rootfs per distro, plus proot's tmp dir.
     * --link2symlink stores hard links as symlinks to the rootfs's real host path
     * (/data/data/<pkg>/..., proot resolves /data/user/0), so a rootfs only works from the place
     * it was set up in: never move or rename one after its package setup ran.
     */
    val linuxDir: File get() = File(context.filesDir, "linux")

    fun rootfs(distroId: String): File = File(linuxDir, distroId)

    /** Bori Code's projects folder on the host; every guest sees it at [GUEST_WORKSPACE]. */
    val projectsRoot: File get() = ProjectRepository.projectsRoot(context)

    /** [hostDir] as seen inside the guest when it's in [projectsRoot]; else the workspace root. */
    fun guestWorkspacePath(hostDir: File?): String {
        val rel = hostDir?.takeIf { it.isDirectory }
            ?.let { runCatching { it.canonicalFile.relativeTo(projectsRoot.canonicalFile).path }.getOrNull() }
            ?.takeIf { !it.startsWith("..") }
        return if (rel.isNullOrEmpty()) GUEST_WORKSPACE else "$GUEST_WORKSPACE/$rel"
    }

    /** The bind that puts [projectsRoot] at [GUEST_WORKSPACE]. */
    fun workspaceBinds(): Map<String, String> = mapOf(projectsRoot.absolutePath to GUEST_WORKSPACE)

    /** Whether [name] is an executable on the guest's PATH. A file check: no guest is started. */
    fun guestHasCommand(distroId: String, name: String): Boolean {
        val rootfs = rootfs(distroId)
        return GUEST_PATH.split(':').any { guestFileExists(rootfs, "$it/$name") }
    }

    /** Starts [cmd] with pipes for stdin and stdout (stderr goes to stdout when [mergeStderr]). */
    fun start(cmd: HostCommand, mergeStderr: Boolean = true): Process =
        ProcessBuilder(cmd.argv)
            .directory(File(cmd.cwd))
            .redirectErrorStream(mergeStderr)
            .apply {
                environment().clear()
                cmd.env.forEach { kv -> environment()[kv.substringBefore('=')] = kv.substringAfter('=') }
            }
            .start()

    /** Host side of [GUEST_HANDOFF]: where the guest's xdg-open drops URLs for Bori Code to open. */
    val handoffDir: File get() = File(linuxDir, "handoff")

    /**
     * Whether [guestPath] exists in [rootfs]. Doesn't follow symlinks: guest links are absolute
     * (Alpine's /bin/sh -> /bin/busybox) and would resolve against the host's root.
     */
    fun guestFileExists(rootfs: File, guestPath: String): Boolean =
        Files.exists(File(rootfs, guestPath.removePrefix("/")).toPath(), LinkOption.NOFOLLOW_LINKS)

    /**
     * Present while [DistroInstaller] is still setting a distro up (extracted, packages not done).
     * Kept outside the rootfs so a broken guest can't clear it.
     */
    fun setupPendingMarker(distroId: String): File = File(linuxDir, "$distroId.setup-pending")

    private val prefs get() = context.getSharedPreferences("linux", Context.MODE_PRIVATE)

    /** The distro new Linux tabs open in; the user picks it on the Linux environment screen. */
    var defaultDistro: String?
        get() = prefs.getString(KEY_DEFAULT_DISTRO, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_DISTRO, value).apply()

    /**
     * Distro ids that are fully set up (have a shell, no pending setup), the default first.
     * Folders under [linuxDir] that aren't rootfs dirs (tmp, handoff, downloads) have no bin/sh.
     */
    fun installedDistros(): List<String> {
        val ids = linuxDir.listFiles()
            ?.filter { it.isDirectory && guestFileExists(it, "bin/sh") && !setupPendingMarker(it.name).exists() }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        val default = defaultDistro
        return ids.sortedBy { if (it == default) 0 else 1 }
    }

    /**
     * Runs [guestArgv] inside the [distroId] rootfs, as root, in [guestCwd].
     * [binds] maps host paths to guest paths, on top of /dev, /proc and /sys.
     */
    fun command(
        distroId: String,
        guestArgv: List<String>,
        guestCwd: String = GUEST_HOME,
        binds: Map<String, String> = emptyMap(),
        guestEnv: Map<String, String> = emptyMap(),
    ): HostCommand {
        val rootfs = rootfs(distroId)
        val tmp = File(linuxDir, "tmp").apply { mkdirs() }
        val proot = "$libDir/libproot.so"

        val argv = buildList {
            add(proot)
            addAll(listOf("--kill-on-exit", "--link2symlink", "-0", "-r", rootfs.absolutePath))
            addAll(listOf("-b", "/dev", "-b", "/proc", "-b", "/sys"))
            addAll(listOf("-b", "${rootfs.absolutePath}/tmp:/dev/shm"))
            addAll(listOf("-b", "${handoffDir.apply { mkdirs() }.absolutePath}:$GUEST_HANDOFF"))
            binds.forEach { (host, guest) ->
                if (File(host).exists()) addAll(listOf("-b", "$host:$guest"))
            }
            addAll(listOf("-w", guestCwd))
            // env -i: the host's variables must not leak into the guest.
            add("/usr/bin/env")
            add("-i")
            (defaultGuestEnv() + guestEnv).forEach { (k, v) -> add("$k=$v") }
            addAll(guestArgv)
        }

        val env = listOf(
            "PROOT_LOADER=$libDir/libproot-loader.so",
            "PROOT_TMP_DIR=${tmp.absolutePath}",
        )
        return HostCommand(proot, argv, env, linuxDir.absolutePath)
    }

    private fun defaultGuestEnv(): Map<String, String> = linkedMapOf(
        "HOME" to GUEST_HOME,
        "USER" to "root",
        "TERM" to "xterm-256color",
        "COLORTERM" to "truecolor",
        "LANG" to "C.UTF-8",
        // CLIs open sign-in pages through this (GuestPolicy's shim hands the URL to Bori Code).
        "BROWSER" to "xdg-open",
        "PATH" to GUEST_PATH,
    )

    companion object {
        private const val KEY_DEFAULT_DISTRO = "default_distro"

        const val GUEST_HOME = "/root"

        const val GUEST_PATH = "$GUEST_HOME/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        /**
         * The one folder the coding CLIs work in: Bori Code's projects folder, mounted here. Linux shells
         * start in it, and GuestPolicy locks the CLIs' file tools to it.
         */
        const val GUEST_WORKSPACE = "$GUEST_HOME/workspace"

        /** Private drop box shared with Bori Code (browser handoff); not part of the workspace. */
        const val GUEST_HANDOFF = "/run/bori"
    }
}
