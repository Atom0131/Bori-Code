package com.bori.core.project

/**
 * Android 12+ kills an app's child processes (its "phantom processes") when the device has more
 * than 32 of them, or when one uses a lot of CPU in the background. proot, shells and the coding
 * CLIs are such processes, so a long session can end with SIGKILL even though Bori Code's foreground
 * service keeps the app itself alive. Nothing in the app can turn that limit off; the user can.
 */
object PhantomProcesses {
    /**
     * Whether a guest session ended by SIGKILL. When proot itself is killed the exit code says so
     * (128 + 9, or -9 from Termux's waitFor); when a process inside is killed, proot exits 1 and
     * prints "vpid N: terminated with signal 9" (emulator, 2026-10-25), so [output] is checked too.
     */
    fun wasKilled(exitCode: Int, output: String = ""): Boolean =
        exitCode == 137 || exitCode == -9 || "terminated with signal 9" in output

    const val HELP =
        "Android stopped it (signal 9). On Android 12 and later this is usually the limit on background " +
            "processes. To lift it: on Android 14+, turn on Developer options → \"Disable child process " +
            "restrictions\"; on Android 12–13, run this from a computer: " +
            "adb shell settings put global settings_enable_monitor_phantom_procs false"
}
