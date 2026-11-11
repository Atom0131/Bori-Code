package com.bori.core.linux

import android.os.Build
import android.os.StatFs
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Where a distro stands on this device. */
enum class DistroStatus { NOT_INSTALLED, SETUP_INCOMPLETE, INSTALLED }

/** What the installer is doing right now, for the Linux environment screen. */
sealed interface InstallState {
    data object Idle : InstallState

    /** [cliId] is set when the job installs or removes a [CodingCli] rather than the distro itself. */
    data class Running(
        val distroId: String,
        val step: Step,
        val log: List<String> = emptyList(),
        val cliId: String? = null,
    ) : InstallState

    data class Failed(val distroId: String, val message: String, val log: List<String>, val cliId: String? = null) : InstallState

    data class Done(val distroId: String, val cliId: String? = null) : InstallState

    sealed interface Step {
        data class Downloading(val bytes: Long, val total: Long) : Step
        data object Verifying : Step
        data class Extracting(val files: Int) : Step
        data object Packages : Step
        data object Deleting : Step
        data object CliInstalling : Step
        data object CliRemoving : Step
    }
}

/**
 * Installs and removes the distros in [DistroCatalog]: download (resumable) → SHA-256 check →
 * extract into a staging folder → rename into place → run the distro's setup script under proot.
 * One job at a time; it runs in its own scope so leaving the screen doesn't stop it.
 *
 * Extraction stays inside the staging folder: entries with `..`, or whose parent resolves outside
 * it (through an absolute guest symlink such as /bin/sh -> /bin/busybox), are skipped. Android
 * apps can't make hard links, so tar hard links become copies.
 */
@Singleton
class DistroInstaller @Inject constructor(
    private val proot: ProotLauncher,
    private val customClis: CustomCliStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var setupProcess: Process? = null

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val downloadsDir: File get() = File(proot.linuxDir, "downloads")

    fun status(distroId: String): DistroStatus {
        val rootfs = proot.rootfs(distroId)
        return when {
            !proot.guestFileExists(rootfs, "bin/sh") -> DistroStatus.NOT_INSTALLED
            proot.setupPendingMarker(distroId).exists() -> DistroStatus.SETUP_INCOMPLETE
            else -> DistroStatus.INSTALLED
        }
    }

    val isBusy: Boolean get() = job?.isActive == true

    /** Free space where the rootfs dirs live. */
    fun freeBytes(): Long = runCatching { StatFs(proot.linuxDir.apply { mkdirs() }.path).availableBytes }.getOrDefault(0)

    /** Bytes the distro's rootfs uses (regular files only, symlinks not followed). */
    suspend fun sizeOnDisk(distroId: String): Long = withContext(Dispatchers.IO) {
        val root = proot.rootfs(distroId)
        if (!root.exists()) return@withContext 0L
        var total = 0L
        runCatching {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        total
    }

    /** Fresh install. The caller makes sure no terminal tab is using the distro. */
    fun install(distroId: String) {
        if (DistroCatalog.get(distroId)?.offered != true) return
        launch(distroId) { distro -> installFresh(distro) }
    }

    /** Deletes the distro (sign-ins inside it included) and installs it again from scratch. */
    fun reinstall(distroId: String) = launch(distroId) { distro ->
        step(distro.id, InstallState.Step.Deleting)
        deleteTree(proot.rootfs(distro.id))
        installFresh(distro)
    }

    private suspend fun installFresh(distro: Distro) {
        val archive = distro.archiveForDevice() ?: error("${distro.name} has no build for this device's CPU")
        val neededBytes = archive.sizeBytes + distro.installedSizeMb * 1024L * 1024L
        if (freeBytes() < neededBytes) {
            error("Not enough space: ${distro.name} needs about ${neededBytes / (1024 * 1024)} MB free")
        }
        step(distro.id, InstallState.Step.Downloading(0, archive.sizeBytes))
        val tarball = download(distro.id, archive)
        step(distro.id, InstallState.Step.Verifying)
        val actual = sha256(tarball)
        if (!actual.equals(archive.sha256, ignoreCase = true)) {
            tarball.delete()
            error("Checksum mismatch for ${archive.fileName}: the download was corrupted or changed. Try again.")
        }

        val staging = File(proot.linuxDir, "${distro.id}.staging")
        deleteTree(staging)
        staging.mkdirs()
        extract(distro.id, tarball, staging)
        configure(staging)

        val rootfs = proot.rootfs(distro.id)
        deleteTree(rootfs)
        // The marker goes down before the rename, so a crash in between still reads as incomplete.
        proot.setupPendingMarker(distro.id).writeText("")
        if (!staging.renameTo(rootfs)) error("Couldn't move the new system into place")
        tarball.delete()

        runSetup(distro)
    }

    /** Re-runs only the package setup, for a distro whose earlier setup failed or was stopped. */
    fun retrySetup(distroId: String) = launch(distroId) { distro -> runSetup(distro) }

    /** Removes the distro and everything in it, including the CLIs' sign-ins inside it. */
    fun delete(distroId: String) = launch(distroId) { distro ->
        step(distro.id, InstallState.Step.Deleting)
        deleteTree(proot.rootfs(distro.id))
        deleteTree(File(proot.linuxDir, "${distro.id}.staging"))
        proot.setupPendingMarker(distro.id).delete()
        if (proot.defaultDistro == distro.id) proot.defaultDistro = proot.installedDistros().firstOrNull()
    }

    /** Stops the running job. A stopped download resumes next time; a stopped setup can be retried. */
    fun cancel() {
        setupProcess?.destroy()
        job?.cancel()
    }

    fun clearResult() {
        if (!isBusy) _state.value = InstallState.Idle
    }

    private fun launch(
        distroId: String,
        cliId: String? = null,
        firstStep: InstallState.Step = InstallState.Step.Verifying,
        block: suspend (Distro) -> Unit,
    ) {
        if (isBusy) return
        val distro = DistroCatalog.get(distroId) ?: return
        _state.value = InstallState.Running(distroId, firstStep, cliId = cliId)
        job = scope.launch {
            try {
                block(distro)
                _state.value = InstallState.Done(distroId, cliId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = InstallState.Failed(distroId, "Stopped", currentLog(), cliId)
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Linux job failed for $distroId ${cliId.orEmpty()}", e)
                _state.value = InstallState.Failed(distroId, e.message ?: e.javaClass.simpleName, currentLog(), cliId)
            }
        }
    }

    private fun currentLog(): List<String> = (_state.value as? InstallState.Running)?.log.orEmpty()

    private fun step(distroId: String, step: InstallState.Step) {
        _state.update { s ->
            val running = s as? InstallState.Running
            InstallState.Running(distroId, step, running?.log.orEmpty(), running?.cliId)
        }
    }

    private fun appendLog(line: String) {
        _state.update { s ->
            if (s !is InstallState.Running) return@update s
            // Progress bars (curl, xbps) redraw with \r, which the reader splits into lines: keep the latest frame only.
            val log = if (line.contains('%') && s.log.lastOrNull()?.contains('%') == true) s.log.dropLast(1) else s.log
            s.copy(log = (log + line).takeLast(LOG_LINES))
        }
    }

    /** Downloads to downloads/<file>, resuming a partial file with a Range request. */
    private suspend fun download(distroId: String, archive: RootfsArchive): File {
        val dir = downloadsDir.apply { mkdirs() }
        val done = File(dir, archive.fileName)
        if (done.length() == archive.sizeBytes) return done
        val part = File(dir, archive.fileName + ".part")
        if (part.length() > archive.sizeBytes) part.delete()

        val conn = (URL(archive.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            if (part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
        }
        try {
            val append = when (conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                else -> error("Download failed: HTTP ${conn.responseCode}")
            }
            var bytes = if (append) part.length() else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bytes += n
                        if (bytes - lastReport > 256 * 1024) {
                            lastReport = bytes
                            step(distroId, InstallState.Step.Downloading(bytes, archive.sizeBytes))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (part.length() != archive.sizeBytes) error("Download incomplete (${part.length()} of ${archive.sizeBytes} bytes)")
        if (!part.renameTo(done)) error("Couldn't save the download")
        return done
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun extract(distroId: String, tarball: File, dest: File) {
        val root = dest.canonicalFile
        val dirModes = mutableListOf<Pair<File, Int>>()
        var files = 0
        val compressed: InputStream = tarball.inputStream().buffered()
        val decompressed = if (tarball.name.endsWith(".xz")) XZCompressorInputStream(compressed) else GzipCompressorInputStream(compressed)
        TarArchiveInputStream(decompressed).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val target = safeTarget(root, entry.name) ?: continue
                when {
                    entry.isDirectory -> {
                        target.mkdirs()
                        dirModes += target to entry.mode
                    }
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        Files.deleteIfExists(target.toPath())
                        Os.symlink(entry.linkName, target.path)
                    }
                    entry.isLink -> {
                        val source = safeTarget(root, entry.linkName) ?: continue
                        target.parentFile?.mkdirs()
                        Files.deleteIfExists(target.toPath())
                        if (Files.isSymbolicLink(source.toPath())) {
                            // Never follow it: copy the link itself, not whatever it points at on the host.
                            Os.symlink(Files.readSymbolicLink(source.toPath()).toString(), target.path)
                        } else {
                            source.copyTo(target)
                            chmod(target, (entry.mode and 0x1FF) or OWNER_RW)
                        }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        Files.deleteIfExists(target.toPath())
                        FileOutputStream(target).use { out -> tar.copyTo(out) }
                        chmod(target, (entry.mode and 0x1FF) or OWNER_RW)
                    }
                    // Device nodes and FIFOs: /dev comes from the host through proot's bind.
                    else -> continue
                }
                if (++files % 200 == 0) step(distroId, InstallState.Step.Extracting(files))
            }
        }
        // Directory modes last, so a read-only directory doesn't block its own contents. The owner
        // always keeps rwx: proot's fake root can't write where the app user can't.
        dirModes.forEach { (dir, mode) -> chmod(dir, (mode and 0x3FF) or OWNER_RWX) }
    }

    /**
     * [name] inside [root], or null if it would land outside: `..` tricks, or a parent that's a
     * symlink pointing out of the staging folder.
     */
    private fun safeTarget(root: File, name: String): File? {
        val rel = name.removePrefix("./").trimStart('/').trimEnd('/')
        if (rel.isEmpty()) return null
        val target = File(root, rel)
        val normalized = target.toPath().normalize()
        if (!normalized.startsWith(root.toPath())) return null
        val parent = target.parentFile ?: return null
        val parentReal = runCatching { parent.canonicalFile }.getOrNull() ?: return null
        if (!parentReal.toPath().startsWith(root.toPath())) {
            Log.w(TAG, "Skipped $name: its folder points outside the rootfs")
            return null
        }
        return File(parentReal, target.name)
    }

    /** DNS and hosts: proot has no network namespace, so the guest uses the device's network directly. */
    private fun configure(rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        listOf("resolv.conf", "hosts").forEach { Files.deleteIfExists(File(etc, it).toPath()) }
        File(etc, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(etc, "hosts").writeText("127.0.0.1\tlocalhost localhost.localdomain\n::1\t\tlocalhost localhost.localdomain\n")
    }

    private suspend fun runSetup(distro: Distro) {
        step(distro.id, InstallState.Step.Packages)
        val code = runInGuest(distro.id, distro.setupScript)
        if (code != 0) error("Package setup failed (exit $code). See the log below, then Retry.")
        proot.setupPendingMarker(distro.id).delete()
        if (proot.defaultDistro == null || DistroCatalog.get(proot.defaultDistro!!) == null ||
            status(proot.defaultDistro!!) != DistroStatus.INSTALLED
        ) {
            proot.defaultDistro = distro.id
        }
    }

    /** Installs [cli] in [distroId], or updates it to the latest version if it's already there. */
    fun installCli(distroId: String, cli: CodingCli) =
        launch(distroId, cli.id, InstallState.Step.CliInstalling) { distro ->
            val script = cli.installScript(distro)
            val code = runInGuest(distro.id, script)
            if (code != 0) error("${cli.title} install failed (exit $code). See the log below.")
            checkCommands(distro.id, cli)
        }

    /**
     * Asks [cli] for its "/" commands (see [CliCommands]) and saves them for Chat's "/" list. A CLI
     * without a list, or a check that fails, keeps Chat's built-in list; the install still succeeded.
     */
    private suspend fun checkCommands(distroId: String, cli: CodingCli) {
        val program = cli.command.trim().substringBefore(' ').substringAfterLast('/')
        val probes = CliCommands.probes(program)
        if (probes.isEmpty()) return
        appendLog("Checking ${cli.title}'s commands for Chat…")
        for (probe in probes) {
            coroutineContext.ensureActive()
            val found = guestOutput(distroId, probe, timeoutSeconds = 90)?.let(CliCommands::parse) ?: continue
            runCatching { CliCommands.save(proot.rootfs(distroId), program, found) }
                .onSuccess { appendLog("Chat's \"/\" list now has ${found.size} of ${cli.title}'s commands") }
                .onFailure { appendLog("Couldn't save ${cli.title}'s commands: ${it.message}") }
            return
        }
        appendLog("${cli.title} didn't list its commands; Chat keeps its own list for it")
    }

    /**
     * Removes [cli]'s program; for the user's own CLIs, also their entry. Its sign-in files stay in
     * the guest; Bori Code doesn't touch them.
     */
    fun removeCli(distroId: String, cli: CodingCli) =
        launch(distroId, cli.id, InstallState.Step.CliRemoving) { distro ->
            val code = runInGuest(distro.id, cli.uninstallScript)
            if (code != 0 && !cli.custom) error("Removing ${cli.title} failed (exit $code)")
            if (cli.custom) customClis.remove(cli)
        }

    /**
     * Each of [clis]' installed version in [distroId] (null when not installed), probed in one guest
     * start. A CLI missing from the result couldn't be checked. Quick, no job.
     */
    suspend fun cliVersions(distroId: String, clis: List<CodingCli>): Map<String, String?> {
        if (clis.isEmpty()) return emptyMap()
        val out = guestOutput(distroId, CodingCli.versionsScript(clis), timeoutSeconds = 120) ?: return emptyMap()
        return out.lineSequence()
            .filter { '\t' in it }
            .associate { line -> line.substringBefore('\t') to line.substringAfter('\t').trim().ifBlank { null } }
    }

    /**
     * Copies a program file the user picked into [distroId]'s /usr/local/bin as [name] and makes it
     * runnable. Returns a warning when it doesn't look like a Linux program or script for this
     * device (it's kept anyway: the user asked for it, and Bori Code doesn't promise it runs).
     */
    suspend fun addProgramFile(distroId: String, name: String, input: InputStream): String? = withContext(Dispatchers.IO) {
        require(CustomCliStore.SAFE_NAME.matches(name)) { "Use letters, numbers, dots or dashes for the name" }
        val rootfs = proot.rootfs(distroId)
        val binDir = File(rootfs, "usr/local/bin")
        binDir.mkdirs()
        // /usr/local/bin must be a real folder inside the rootfs, not a guest symlink out of it.
        require(binDir.canonicalPath.startsWith(rootfs.canonicalPath + File.separator)) { "Can't write to /usr/local/bin" }
        val target = File(binDir, name)
        val tmp = File(binDir, ".$name.bori-tmp")
        val head = ByteArray(20)
        var headLen = 0
        input.use { src ->
            tmp.outputStream().use { dst ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    if (headLen < head.size) {
                        val take = minOf(n, head.size - headLen)
                        System.arraycopy(buf, 0, head, headLen, take)
                        headLen += take
                    }
                    dst.write(buf, 0, n)
                }
            }
        }
        Files.deleteIfExists(target.toPath())
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Couldn't save the program")
        }
        chmod(target, 0x1ED) // 0755
        programWarning(head.copyOf(headLen))
    }

    /** Null for a `#!` script or an ELF program for this device's CPU; else what's wrong with it. */
    private fun programWarning(head: ByteArray): String? {
        if (head.size >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) return null
        val elf = head.size >= 20 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        if (!elf) return "This doesn't look like a Linux program or a script (#!), so it may not run."
        // e_machine, little-endian, at offset 18: 0xB7 aarch64, 0x3E x86_64.
        val machine = (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
        val wanted = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") 0x3E else 0xB7
        return if (machine == wanted) null else "This program is built for another kind of CPU, so it likely won't run."
    }

    /**
     * The GitHub account gh is signed in to in [distroId], or null. Reads only the account name
     * from gh's config, never the token, and works offline.
     */
    suspend fun githubLogin(distroId: String): String? = firstLine(
        distroId,
        "command -v gh >/dev/null 2>&1 && gh config get -h github.com user 2>/dev/null",
    )

    /**
     * Hands [token] (from Bori Code's GitHub browser sign-in) to gh in [distroId] through
     * `bori-github token`, on stdin so it never shows in a process list. Installs gh first if
     * needed. Returns the signed-in account, or null when gh refused the token.
     */
    suspend fun signInGitHub(distroId: String, token: String): String? = withContext(Dispatchers.IO) {
        if (status(distroId) != DistroStatus.INSTALLED) return@withContext null
        val ok = runCatching {
            val process = startGuest(distroId, "${GuestPolicy.GITHUB_SCRIPT} token")
            try {
                process.outputStream.bufferedWriter().use { it.write(token + "\n") }
                // Drain the output (gh's messages; never the token) so the guest can't block on a full pipe.
                process.inputStream.bufferedReader().readText()
                process.waitFor(3, java.util.concurrent.TimeUnit.MINUTES) && process.exitValue() == 0
            } finally {
                process.destroy()
            }
        }.getOrDefault(false)
        if (ok) githubLogin(distroId) else null
    }

    /** The first line [script] prints in [distroId] when it exits 0 within 20 s, else null. */
    private suspend fun firstLine(distroId: String, script: String): String? =
        guestOutput(distroId, script)?.trim()?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }

    /** What [script] prints in [distroId] when it exits 0 within [timeoutSeconds], else null. */
    private suspend fun guestOutput(distroId: String, script: String, timeoutSeconds: Long = 20): String? = withContext(Dispatchers.IO) {
        if (status(distroId) != DistroStatus.INSTALLED) return@withContext null
        runCatching {
            val process = startGuest(distroId, script)
            try {
                // Read on another thread so a full pipe can't stall the guest past the timeout.
                val output = StringBuilder()
                val reader = Thread { runCatching { output.append(process.inputStream.bufferedReader().readText()) } }
                reader.start()
                val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) process.destroy()
                reader.join(2_000)
                output.toString().takeIf { finished && process.exitValue() == 0 }
            } finally {
                process.destroy()
            }
        }.getOrNull()
    }

    private fun startGuest(distroId: String, script: String): Process =
        proot.start(proot.command(distroId, listOf("/bin/sh", "-c", script)))

    /** Runs [script] as root in [distroId], streaming its output into the job log; returns the exit code. */
    private suspend fun runInGuest(distroId: String, script: String): Int {
        val process = startGuest(distroId, script)
        setupProcess = process
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    coroutineContext.ensureActive()
                    if (line.isNotBlank()) appendLog(line)
                }
            }
            val code = process.waitFor()
            coroutineContext.ensureActive()
            return code
        } finally {
            setupProcess = null
            process.destroy()
        }
    }

    /**
     * Deletes [dir] without following symlinks: guest links are absolute (/bin/sh -> /bin/busybox)
     * and, followed on the host, would point at the device's own files.
     */
    private fun deleteTree(dir: File) {
        if (!Files.exists(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                chmod(d.toFile(), OWNER_RWX)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(d)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.path, mode) }
    }

    private companion object {
        const val TAG = "DistroInstaller"
        const val LOG_LINES = 200
        const val OWNER_RW = 0x180 // 0600
        const val OWNER_RWX = 0x1C0 // 0700
    }
}
