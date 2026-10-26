package com.bori.core.linux

import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A coding CLI Bori Code can install inside a distro. Bori Code only installs, updates and removes the
 * program: signing in happens in the CLI itself (`/login`), and its credentials stay in the
 * guest (~/.claude, ~/.gemini). Bori Code never reads, copies or syncs them; a third-party app may
 * not use a Claude subscription login, and the CLI being the only holder of it keeps that true.
 */
enum class CodingCli(
    val id: String,
    val title: String,
    /** The command a terminal tab runs to start it (and sign in on first run). */
    val command: String,
    val summary: String,
) {
    CLAUDE_CODE(
        id = "claude",
        title = "Claude Code",
        command = "claude",
        summary = "Anthropic's coding agent. Sign in with your Claude account or an API key.",
    ),
    ANTIGRAVITY(
        id = "antigravity",
        title = "Antigravity CLI",
        command = "antigravity",
        summary = "Google's coding agent. Sign in with your Google account.",
    ),
    ;

    /** Prints the installed version and exits 0, or exits non-zero when it isn't installed. */
    val versionScript: String get() = "command -v $command >/dev/null 2>&1 && $command --version 2>/dev/null | head -n 1"

    val uninstallScript: String
        get() = when (this) {
            CLAUDE_CODE -> "set -e; npm uninstall -g $NPM_PACKAGE"
            ANTIGRAVITY -> "set -e; rm -f /usr/local/bin/antigravity"
        }

    companion object {
        const val NPM_PACKAGE = "@anthropic-ai/claude-code"

        fun get(id: String): CodingCli? = entries.firstOrNull { it.id == id }

        private const val ANTIGRAVITY_MANIFESTS =
            "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests"
    }

    /**
     * The install (or update: same script, latest version) script for [distro]. Antigravity's
     * comes from Google's update manifest, fetched here; the tarball is checked against the
     * manifest's SHA-512 inside the guest before anything is unpacked. Blocking (network).
     */
    fun installScript(distro: Distro): String = when (this) {
        // npm 11.19+ skips install scripts unless allowed; Claude Code's postinstall sets up its native binary.
        // An update uninstalls first: npm would otherwise rename the old copy aside and delete it after
        // installing the new one. The postinstall hard-links the binary, which proot's link2symlink turns
        // into absolute symlinks to the original path, so deleting the old copy deletes the new binary.
        CLAUDE_CODE -> """
            set -e
            npm uninstall -g $NPM_PACKAGE >/dev/null 2>&1 || true
            npm install -g --allow-scripts=$NPM_PACKAGE $NPM_PACKAGE@latest
            npm cache clean --force >/dev/null 2>&1 || true
            claude --version
        """.trimIndent()
        ANTIGRAVITY -> {
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
}
