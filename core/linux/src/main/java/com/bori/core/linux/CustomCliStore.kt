package com.bori.core.linux

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own CLI programs (Linux environment → "Your own programs"): a program file they
 * uploaded, a command for something they installed themselves, or a name with their own install
 * script. Kept as JSON in Bori Code's private files, outside the rootfs, so a reinstall of the distro
 * keeps the list (the programs themselves go with the rootfs). Bori Code doesn't promise these work.
 */
@Singleton
class CustomCliStore @Inject constructor(
    private val proot: ProotLauncher,
) {
    private val file: File get() = File(proot.linuxDir, "custom-clis.json")

    @Synchronized
    fun all(): List<CodingCli> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { fromJson(array.getJSONObject(it)) }
    }.onFailure { Log.w(TAG, "Reading custom CLIs", it) }.getOrDefault(emptyList())

    fun forDistro(distroId: String): List<CodingCli> = all().filter { it.distroId == distroId }

    fun get(distroId: String, id: String): CodingCli? = forDistro(distroId).firstOrNull { it.id == id }

    /** Adds [cli], replacing one with the same id in the same distro. */
    @Synchronized
    fun put(cli: CodingCli) {
        val rest = all().filterNot { it == cli }
        save(rest + cli)
    }

    @Synchronized
    fun remove(cli: CodingCli) = save(all().filterNot { it == cli })

    private fun save(list: List<CodingCli>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString(2))
        if (!tmp.renameTo(file)) {
            file.writeText(array.toString(2))
            tmp.delete()
        }
    }

    private fun toJson(cli: CodingCli) = JSONObject().apply {
        put("id", cli.id)
        put("title", cli.title)
        put("command", cli.command)
        put("distro", cli.distroId)
        when (val i = cli.installer) {
            CodingCli.Installer.Uploaded -> put("kind", KIND_FILE)
            is CodingCli.Installer.Custom -> {
                put("kind", KIND_SCRIPT)
                put("install", i.install)
                put("uninstall", i.uninstall)
            }
            else -> error("Not a custom CLI")
        }
    }

    private fun fromJson(o: JSONObject): CodingCli? {
        val distro = o.optString("distro").ifBlank { return null }
        val installer = when (o.optString("kind")) {
            KIND_FILE -> CodingCli.Installer.Uploaded
            KIND_SCRIPT -> CodingCli.Installer.Custom(o.optString("install"), o.optString("uninstall"))
            else -> return null
        }
        return create(
            id = o.optString("id").ifBlank { return null },
            title = o.optString("title"),
            command = o.optString("command").ifBlank { return null },
            distroId = distro,
            installer = installer,
        )
    }

    companion object {
        private const val TAG = "CustomCliStore"
        private const val KIND_FILE = "file"
        private const val KIND_SCRIPT = "script"

        /** A program name safe as a file name in /usr/local/bin and in a shell command. */
        val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")

        /** [SAFE_NAME] from what the user typed or the picked file's name ("My Tool.sh" -> "my-tool.sh"). */
        fun safeName(raw: String): String = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9._+-]+"), "-")
            .trim('-', '.')
            .take(64)

        fun create(id: String, title: String, command: String, distroId: String, installer: CodingCli.Installer) = CodingCli(
            id = id,
            title = title.ifBlank { command.substringBefore(' ') },
            command = command.trim(),
            summary = when (installer) {
                CodingCli.Installer.Uploaded -> "Your program file in /usr/local/bin."
                is CodingCli.Installer.Custom ->
                    if (installer.install.isBlank()) "Runs `${command.trim()}`." else "Installed with your own script."
                else -> ""
            },
            group = CodingCli.Group.CUSTOM,
            installer = installer,
            distroId = distroId,
        )
    }
}
