package com.bori.core.linux

import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A coding CLI Bori Code can install inside a distro: one of the built-ins in [CodingCli.builtIns], or
 * one the user added ([custom], from [CustomCliStore]). Bori Code only installs, updates and removes the
 * program: signing in happens in the CLI itself, and its credentials stay in the guest (~/.claude,
 * ~/.gemini, ~/.codex, …). Bori Code never reads, copies or syncs them; a third-party app may not use a
 * Claude subscription login, and the CLI being the only holder of it keeps that true.
 */
class CodingCli(
    val id: String,
    val title: String,
    /** The command a terminal tab runs to start it (and sign in on first run). */
    val command: String,
    val summary: String,
    val group: Group,
    val installer: Installer,
    /** False when it has no build for musl (Alpine). */
    val musl: Boolean = true,
    /** Set for the user's own CLIs: the one distro it was added to. Built-ins work in every distro. */
    val distroId: String? = null,
) {
    enum class Group(val title: String) {
        LABS("From the AI labs"),
        OPEN("Open source, any provider"),
        CHINA("Chinese models"),
        CUSTOM("Your own programs"),
    }

    sealed interface Installer {
        /** `npm install -g`; [allowScripts] when its postinstall fetches its native binary. */
        data class Npm(val pkg: String, val allowScripts: Boolean = false) : Installer

        /** A Python CLI as a uv tool, on its own Python ([python]) so the distro's newer one doesn't break it. */
        data class Uv(val pkg: String, val python: String) : Installer

        /** Google's update manifest, checked against its SHA-512 in the guest. */
        data object Antigravity : Installer

        /** The user's own: their install script (may be blank for "by command") and optional uninstall script. */
        data class Custom(val install: String, val uninstall: String) : Installer

        /** The user's own program file, copied into /usr/local/bin by [DistroInstaller.addProgramFile]. */
        data object Uploaded : Installer
    }

    val custom: Boolean get() = group == Group.CUSTOM

    /** The program [command] starts, for the installed check (`node ~/x.js` -> `node`). */
    val program: String get() = command.trim().substringBefore(' ')

    /** Whether it's offered in [distro] at all. */
    fun availableIn(distro: Distro): Boolean = (distroId == null || distroId == distro.id) && (musl || !distro.musl)

    /** Bori Code runs the install/update script for these; uploads and "by command" ones have none. */
    val installable: Boolean
        get() = when (val i = installer) {
            is Installer.Custom -> i.install.isNotBlank()
            Installer.Uploaded -> false
            else -> true
        }

    val uninstallScript: String
        get() = when (val i = installer) {
            is Installer.Npm -> "set -e; npm uninstall -g ${i.pkg}"
            is Installer.Uv -> "set -e; uv tool uninstall ${i.pkg.substringBefore('[')}"
            Installer.Antigravity -> "set -e; rm -f /usr/local/bin/antigravity"
            is Installer.Custom -> i.uninstall.ifBlank { "true" }
            Installer.Uploaded -> "set -e; rm -f /usr/local/bin/$program"
        }

    /**
     * The install (or update: same script, latest version) script for [distro]. Antigravity's comes
     * from Google's update manifest, fetched here; the tarball is checked against the manifest's
     * SHA-512 inside the guest before anything is unpacked. Blocking (network).
     */
    fun installScript(distro: Distro): String = when (val i = installer) {
        // npm 11.19+ skips install scripts unless allowed; some CLIs' postinstall sets up their native binary.
        // An update uninstalls first: npm would otherwise rename the old copy aside and delete it after
        // installing the new one. Postinstalls that hard-link the binary get absolute symlinks to the
        // original path from proot's link2symlink, so deleting the old copy deletes the new binary.
        is Installer.Npm -> """
            set -e
            npm uninstall -g ${i.pkg} >/dev/null 2>&1 || true
            npm install -g ${if (i.allowScripts) "--allow-scripts=${i.pkg} " else ""}${i.pkg}@latest
            npm cache clean --force >/dev/null 2>&1 || true
            $program --version
        """.trimIndent()
        // uv fetches the Python it needs (Void's and Arch's are newer than most Python CLIs support).
        is Installer.Uv -> """
            set -e
            command -v uv >/dev/null 2>&1 || ${distro.installPackages} uv
            UV_TOOL_BIN_DIR=/usr/local/bin uv tool install --force --python ${i.python} '${i.pkg}'
            uv cache clean >/dev/null 2>&1 || true
            $program --version
        """.trimIndent()
        Installer.Antigravity -> {
            val manifest = fetchAntigravityManifest(distro)
            """
                set -e
                cd /tmp
                rm -rf bori-antigravity && mkdir bori-antigravity && cd bori-antigravity
                echo "Downloading Antigravity CLI ${manifest.version}"
                curl -fL --progress-bar -o cli.tar.gz '${manifest.url}'
                echo '${manifest.sha512}  cli.tar.gz' | sha512sum -c -
                tar -xzf cli.tar.gz antigravity
                install -m 755 antigravity /usr/local/bin/antigravity
                cd /tmp && rm -rf bori-antigravity
                antigravity --version
            """.trimIndent()
        }
        is Installer.Custom -> i.install
        Installer.Uploaded -> error("$title is a program file; add it again to replace it")
    }

    private data class Manifest(val version: String, val url: String, val sha512: String)

    private fun fetchAntigravityManifest(distro: Distro): Manifest {
        val arch = when (Build.SUPPORTED_ABIS.firstOrNull { it in distro.archives }) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "amd64"
            else -> error("Antigravity CLI has no build for this device's CPU")
        }
        val platform = "linux_$arch" + if (distro.musl) "_musl" else ""
        val conn = (URL("$ANTIGRAVITY_MANIFESTS/$platform.json").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        val json = try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("Antigravity update server: HTTP ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
        val m = Manifest(json.getString("version"), json.getString("url"), json.getString("sha512"))
        // These go into a shell script, so only accept what a real manifest contains.
        require(Regex("[0-9A-Za-z.+-]{1,40}").matches(m.version)) { "Unexpected Antigravity version" }
        require(Regex("https://storage\\.googleapis\\.com/[0-9A-Za-z._/+-]+").matches(m.url)) { "Unexpected Antigravity download URL" }
        require(Regex("[0-9a-f]{128}").matches(m.sha512)) { "Unexpected Antigravity checksum" }
        return m
    }

    override fun equals(other: Any?): Boolean = other is CodingCli && other.id == id && other.distroId == distroId
    override fun hashCode(): Int = id.hashCode() * 31 + distroId.hashCode()

    companion object {
        const val NPM_PACKAGE = "@anthropic-ai/claude-code"

        private const val ANTIGRAVITY_MANIFESTS =
            "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests"

        val CLAUDE_CODE = CodingCli(
            id = "claude",
            title = "Claude Code",
            command = "claude",
            summary = "Anthropic's coding agent. Sign in with your Claude account or an API key.",
            group = Group.LABS,
            installer = Installer.Npm(NPM_PACKAGE, allowScripts = true),
        )

        val ANTIGRAVITY = CodingCli(
            id = "antigravity",
            title = "Antigravity CLI",
            command = "antigravity",
            summary = "Google's coding agent. Sign in with your Google account.",
            group = Group.LABS,
            installer = Installer.Antigravity,
        )

        /**
         * The built-ins, in the order the Linux screen lists them. npm packages and their bin names
         * checked with `npm view` on 2026-11-10; each ships a Linux arm64 build (musl too, unless
         * [musl] is false). DeepSeek, GLM and MiniMax have no CLI of their own: OpenCode and Crush take
         * their keys. iFlow CLI was left out (no release since 2026-05, reported shut down).
         */
        val builtIns: List<CodingCli> = listOf(
            CLAUDE_CODE,
            ANTIGRAVITY,
            CodingCli(
                id = "codex",
                title = "Codex CLI",
                command = "codex",
                summary = "OpenAI's coding agent. Sign in with ChatGPT or an OpenAI API key.",
                group = Group.LABS,
                installer = Installer.Npm("@openai/codex"),
            ),
            CodingCli(
                id = "gemini",
                title = "Gemini CLI",
                command = "gemini",
                summary = "Google's open-source agent. Sign in with Google (free daily quota) or a Gemini API key.",
                group = Group.LABS,
                installer = Installer.Npm("@google/gemini-cli"),
            ),
            CodingCli(
                id = "copilot",
                title = "GitHub Copilot CLI",
                command = "copilot",
                summary = "GitHub's coding agent. Needs a Copilot plan; sign in with your GitHub account.",
                group = Group.LABS,
                installer = Installer.Npm("@github/copilot"),
            ),
            CodingCli(
                id = "opencode",
                title = "OpenCode",
                command = "opencode",
                summary = "Open source. Works with Claude, OpenAI, Gemini, DeepSeek, GLM, Kimi, OpenRouter or a local model.",
                group = Group.OPEN,
                installer = Installer.Npm("opencode-ai", allowScripts = true),
            ),
            CodingCli(
                id = "crush",
                title = "Crush",
                command = "crush",
                summary = "Charm's open-source agent. Bring a key from any provider (DeepSeek, GLM and others included).",
                group = Group.OPEN,
                installer = Installer.Npm("@charmland/crush", allowScripts = true),
            ),
            CodingCli(
                id = "aider",
                title = "Aider",
                command = "aider",
                summary = "Open-source pair programmer in Python. Bring a key from any provider. Not on Alpine.",
                group = Group.OPEN,
                installer = Installer.Uv("aider-chat", python = "3.12"),
                musl = false,
            ),
            CodingCli(
                id = "kimi",
                title = "Kimi Code",
                command = "kimi",
                summary = "Moonshot AI's coding agent for the Kimi models. Sign in with your Kimi account or an API key.",
                group = Group.CHINA,
                installer = Installer.Npm("@moonshot-ai/kimi-code", allowScripts = true),
            ),
            CodingCli(
                id = "qwen",
                title = "Qwen Code",
                command = "qwen",
                summary = "Alibaba's agent for the Qwen models. Needs an API key (DashScope, OpenRouter) or an Alibaba plan.",
                group = Group.CHINA,
                installer = Installer.Npm("@qwen-code/qwen-code"),
            ),
            CodingCli(
                id = "codebuddy",
                title = "CodeBuddy Code",
                command = "codebuddy",
                summary = "Tencent's coding agent. Sign in with your CodeBuddy account.",
                group = Group.CHINA,
                installer = Installer.Npm("@tencent-ai/codebuddy-code"),
            ),
        )

        /** A built-in by id; the user's own CLIs come from [CustomCliStore.get]. */
        fun get(id: String): CodingCli? = builtIns.firstOrNull { it.id == id }

        /**
         * Prints `id<TAB>version` (or `id<TAB>` when missing) for each of [clis], in one guest start
         * instead of one per CLI. `--version` gets no stdin and 15 s; the user's own CLIs are only looked
         * up (`installed`), since their programs may not have a `--version`.
         */
        fun versionsScript(clis: List<CodingCli>): String = clis.joinToString("\n") { cli ->
            val p = shellQuote(cli.program)
            val probe = if (cli.custom) {
                "command -v $p >/dev/null 2>&1 && echo installed"
            } else {
                "command -v $p >/dev/null 2>&1 && { timeout 15 $p --version </dev/null 2>/dev/null | head -n 1 | grep . || echo installed; }"
            }
            "printf '%s\\t%s\\n' ${shellQuote(cli.id)} \"$($probe)\""
        }

        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
