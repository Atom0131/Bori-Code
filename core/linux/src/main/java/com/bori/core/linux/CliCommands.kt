package com.bori.core.linux

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Asks an installed coding CLI for its "/" commands, after an install or update, so Chat's "/" list
 * follows new versions. Each check runs in a throwaway folder set as HOME: no sign-in, no history and
 * no settings of the user's are read or written, and nothing is sent to a model.
 * - Claude Code: `claude -p /cost` (a local command, $0) prints an init message with every command name.
 * - CLIs that speak the Agent Client Protocol (Antigravity, Gemini, Qwen, OpenCode, Kimi, Copilot): a new
 *   session sends `available_commands_update` with names and descriptions.
 * Codex, Crush, Aider and the user's own programs have no such list; Chat keeps its built-in one.
 */
object CliCommands {

    data class Command(val name: String, val description: String)

    /** Where the commands found are kept in the guest: /root/.bori/agent-commands/<command>.json. */
    const val DIR = "root/.bori/agent-commands"

    /** The guest scripts that may list [command]'s commands, tried in order until one does. */
    fun probes(command: String): List<String> = when (command) {
        "claude" -> listOf(sandboxed("HOME=\"\$P\" claude -p /cost --output-format stream-json --verbose --no-session-persistence </dev/null 2>/dev/null"))
        else -> ACP[command].orEmpty().map { acp(it) }
    }

    /** The commands in a probe's output, or null when it had none. */
    fun parse(output: String): List<Command>? {
        for (line in output.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("{")) continue
            val o = runCatching { JSONObject(t) }.getOrNull() ?: continue
            // Claude Code's init message.
            o.optJSONArray("slash_commands")?.takeIf { o.optString("subtype") == "init" }?.let { a ->
                return names(a).map { Command(it, "") }.takeIf { it.isNotEmpty() }
            }
            // ACP: session/update → available_commands_update.
            val update = o.optJSONObject("params")?.optJSONObject("update") ?: continue
            if (update.optString("sessionUpdate") != "available_commands_update") continue
            val a = update.optJSONArray("availableCommands") ?: continue
            return (0 until a.length()).mapNotNull { i ->
                val c = a.optJSONObject(i) ?: return@mapNotNull null
                val name = clean(c.optString("name")) ?: return@mapNotNull null
                Command(name, c.optString("description").lineSequence().firstOrNull().orEmpty().trim().take(160))
            }.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** Saves [commands] for [command] in [rootfs], where Chat reads them. */
    fun save(rootfs: File, command: String, commands: List<Command>) {
        val dir = File(rootfs, DIR)
        dir.mkdirs()
        // Must be a real folder inside the rootfs, not a guest symlink out of it.
        if (!dir.canonicalPath.startsWith(rootfs.canonicalPath + File.separator)) return
        val json = JSONObject()
            .put("checked", System.currentTimeMillis())
            .put("commands", JSONArray().apply {
                commands.forEach { put(JSONObject().put("name", it.name).put("description", it.description)) }
            })
        val tmp = File(dir, ".$command.json.tmp")
        tmp.writeText(json.toString())
        val target = File(dir, "$command.json")
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    private fun names(a: JSONArray) = (0 until a.length()).mapNotNull { clean(a.optString(it)) }

    /** "/goal" or "goal" → "/goal"; internal names ("__x") and odd ones are dropped. */
    private fun clean(raw: String): String? {
        val n = raw.trim().removePrefix("/")
        if (n.isEmpty() || n.startsWith("_") || !NAME.matches(n)) return null
        return "/$n"
    }

    /** [body] in a fresh temporary folder (its HOME), removed afterwards. `$P` is the folder. */
    private fun sandboxed(body: String) = """
        P=${'$'}(mktemp -d /tmp/bori-cmds.XXXXXX) || exit 1
        cd "${'$'}P" || exit 1
        $body
        rm -rf "${'$'}P"
        exit 0
    """.trimIndent()

    /**
     * An ACP session in a throwaway HOME: initialize, open a session, print what comes back until the
     * command list arrives (or [ACP_WAIT_S] seconds pass), then close stdin so the CLI exits.
     */
    private fun acp(launch: String) = sandboxed(
        """
        INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}'
        NEW=${'$'}(printf '{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"%s","mcpServers":[]}}' "${'$'}P")
        ( printf '%s\n%s\n' "${'$'}INIT" "${'$'}NEW"; i=0
          while [ ${'$'}i -lt $ACP_WAIT_S ] && [ ! -e "${'$'}P/.done" ]; do sleep 1; i=${'$'}((i+1)); done
        ) | HOME="${'$'}P" $launch 2>/dev/null | while IFS= read -r l; do
          printf '%s\n' "${'$'}l"
          case "${'$'}l" in *available_commands_update*) : > "${'$'}P/.done"; break ;; esac
        done
        """.trimIndent(),
    )

    private const val ACP_WAIT_S = 40
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9:_.-]{0,63}")

    /** How each CLI starts its ACP server; a CLI that doesn't know the flag just fails and the next is tried. */
    private val ACP = mapOf(
        "antigravity" to listOf("antigravity acp"),
        "gemini" to listOf("gemini --experimental-acp", "gemini --acp"),
        "qwen" to listOf("qwen --experimental-acp", "qwen --acp"),
        "opencode" to listOf("opencode acp"),
        "kimi" to listOf("kimi acp", "kimi --acp"),
        "copilot" to listOf("copilot --acp"),
    )
}
