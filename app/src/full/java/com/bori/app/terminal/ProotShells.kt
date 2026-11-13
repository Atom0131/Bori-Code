package com.bori.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.bori.core.linux.CodingCli
import com.bori.core.linux.CustomCliStore
import com.bori.core.linux.DistroCatalog
import com.bori.core.linux.GuestPolicy
import com.bori.core.linux.ProotLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Terminal shells inside an installed distro, through proot (full build only). */
@Singleton
class ProotShells @Inject constructor(
    @ApplicationContext private val context: Context,
    private val proot: ProotLauncher,
    private val guestPolicy: GuestPolicy,
    private val customClis: CustomCliStore,
) : LinuxShells {

    override fun installedDistros(): List<String> = proot.installedDistros()

    override fun shellCommand(distroId: String, hostCwd: File?, startCommand: String?, tabId: Int): ShellCommand? {
        guestPolicy.apply(distroId)
        val rootfs = proot.rootfs(distroId)
        // A login shell: bash if the distro has it, else the always-present sh.
        val shell = if (proot.guestFileExists(rootfs, "bin/bash")) "/bin/bash" else "/bin/sh"
        val cmd = proot.command(
            distroId = distroId,
            guestArgv = if (startCommand == null) listOf(shell, "-l") else listOf(shell, "-lc", "$startCommand; exec $shell -l"),
            guestCwd = proot.guestWorkspacePath(hostCwd),
            binds = proot.workspaceBinds(),
            guestEnv = mapOf("BORI_TAB" to tabId.toString()),
        )
        return ShellCommand(cmd.executable, cmd.argv, cmd.env, cmd.cwd)
    }

    override fun guestHome(distroId: String): File = File(proot.rootfs(distroId), "root")

    override fun deviceStatusFile(): File = File(proot.handoffDir, ProotLauncher.DEVICE_FILE)

    override fun installedClis(distroId: String): List<CliLauncher> {
        val distro = DistroCatalog.get(distroId) ?: return emptyList()
        return (CodingCli.builtIns.filter { it.availableIn(distro) } + customClis.forDistro(distroId))
            .filter { proot.guestHasCommand(distroId, it.program) }
            .map { CliLauncher(it.id, it.title, it.command, it.custom) }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var agentListener: ((Int, String, String) -> Unit)? = null

    override fun setAgentStateListener(listener: (tabId: Int, state: String, json: String) -> Unit) {
        agentListener = listener
    }

    /** `agent-<tab>.state` from the guest's status script: the first line is the state, the rest the hook's JSON. */
    private fun onAgentState(path: String) {
        val tabId = AGENT_STATE.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val file = File(proot.handoffDir, path)
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        text ?: return
        val state = text.substringBefore('\n').trim()
        val json = text.substringAfter('\n', "")
        mainHandler.post { agentListener?.invoke(tabId, state, json) }
    }

    /** Text from the guest's `termux-clipboard-set` shim: onto Android's clipboard, with a toast. */
    private fun onClipboard() {
        val file = File(proot.handoffDir, CLIPBOARD_FILE)
        val text = runCatching {
            file.inputStream().use { input -> String(input.readNBytesCompat(MAX_CLIP_BYTES), Charsets.UTF_8) }
        }.getOrNull()
        file.delete()
        if (text.isNullOrEmpty()) return
        val oneLine = text.trim().replace(Regex("\\s+"), " ")
        val message = if (GITHUB_CODE.matches(oneLine)) {
            "GitHub code $oneLine copied. Paste it on the GitHub page."
        } else {
            "Copied from Linux: " + if (oneLine.length > 40) oneLine.take(40) + "…" else oneLine
        }
        mainHandler.post {
            runCatching {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Linux", text))
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }.onFailure { Log.w(TAG, "Clipboard from guest", it) }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Opens URLs the guest's xdg-open shim drops in the handoff folder (CLI sign-in pages), in
     * Android's browser. Held in a field: a FileObserver stops when it's garbage collected.
     */
    @Suppress("DEPRECATION") // FileObserver(File) is API 29+; minSdk is 26.
    private val browserHandoff = object : FileObserver(
        proot.handoffDir.apply { mkdirs() }.absolutePath, MOVED_TO or CLOSE_WRITE,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path != null && path.endsWith(".state")) return onAgentState(path)
            if (path == CLIPBOARD_FILE) return onClipboard()
            if (path != HANDOFF_FILE) return
            val file = File(proot.handoffDir, HANDOFF_FILE)
            val url = runCatching { file.readText().trim() }.getOrNull()
            file.delete()
            if (url.isNullOrEmpty()) return
            val uri = Uri.parse(url)
            if (uri.scheme != "https" && uri.scheme != "http") {
                Log.w(TAG, "Ignored non-web URL from guest: ${uri.scheme}")
                return
            }
            mainHandler.post {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { Log.w(TAG, "No browser for sign-in URL", it) }
            }
        }
    }.also { it.startWatching() }

    private companion object {
        const val TAG = "Terminal"
        const val HANDOFF_FILE = "open-url"
        const val CLIPBOARD_FILE = "clipboard"
        const val MAX_CLIP_BYTES = 65536
        /** gh's one-time device code, e.g. B730-F1A9. */
        val GITHUB_CODE = Regex("[A-Z0-9]{4}-[A-Z0-9]{4}")
        val AGENT_STATE = Regex("agent-(\\d+)\\.state")
    }
}
