package com.bori.app.terminal

import java.io.File

/** A "/" command Chat offers for the agent it sends to. */
data class SlashCommand(val name: String, val description: String, val source: Source = Source.BUILT_IN) {
    enum class Source { BUILT_IN, USER, PROJECT }
}

/**
 * The "/" commands of each coding CLI: its own built-in ones, plus the user's from the command files
 * that CLI loads (in the guest's home and in the project). Reads command files only: never a sign-in,
 * token or settings file.
 */
object AgentCommands {

    /** Commands for the CLI started with [command] (null or unknown: the user's own program, no built-ins). */
    fun list(command: String?, guestHome: File?, projectDir: File?): List<SlashCommand> {
        val own = buildList {
            when (command) {
                "claude" -> {
                    guestHome?.let { addAll(claudeDir(File(it, ".claude"), SlashCommand.Source.USER)) }
                    projectDir?.let { addAll(claudeDir(File(it, ".claude"), SlashCommand.Source.PROJECT)) }
                }
                "antigravity" -> {
                    guestHome?.let { addAll(skills(File(it, ".gemini/antigravity/skills"), SlashCommand.Source.USER)) }
                    projectDir?.let { addAll(skills(File(it, ".agents/skills"), SlashCommand.Source.PROJECT)) }
                }
                "gemini", "qwen" -> {
                    val dir = if (command == "gemini") ".gemini/commands" else ".qwen/commands"
                    guestHome?.let { addAll(tomlCommands(File(it, dir), SlashCommand.Source.USER)) }
                    projectDir?.let { addAll(tomlCommands(File(it, dir), SlashCommand.Source.PROJECT)) }
                }
                "codex" -> guestHome?.let { home ->
                    mdFiles(File(home, ".codex/prompts"), recursive = false).forEach { (name, f) ->
                        add(SlashCommand("/prompts:$name", describe(f), SlashCommand.Source.USER))
                    }
                }
                "opencode" -> {
                    guestHome?.let { home ->
                        mdFiles(File(home, ".config/opencode/command"), recursive = true).forEach { (name, f) ->
                            add(SlashCommand("/$name", describe(f), SlashCommand.Source.USER))
                        }
                    }
                    projectDir?.let { p ->
                        mdFiles(File(p, ".opencode/command"), recursive = true).forEach { (name, f) ->
                            add(SlashCommand("/$name", describe(f), SlashCommand.Source.PROJECT))
                        }
                    }
                }
            }
        }
        // The user's own come first when a name is taken twice, then what the CLI itself listed at its
        // last install or update (CliCommands), then the built-in list for what that check can't see.
        val known = BUILT_IN[command].orEmpty().toMap()
        val checked = command?.let { c -> guestHome?.let { checked(it, c) } }.orEmpty()
            .map { (n, d) -> SlashCommand(n, d.ifBlank { known[n].orEmpty() }) }
        val builtIns = known.map { (n, d) -> SlashCommand(n, d) }
        return (own + checked + builtIns).distinctBy { it.name }.sortedBy { it.name }
    }

    /** Filters [all] for what's typed after "/": names starting with it first, then names containing it. */
    fun filter(all: List<SlashCommand>, typed: String): List<SlashCommand> {
        val q = typed.removePrefix("/").lowercase()
        if (q.isEmpty()) return all
        val starts = all.filter { it.name.removePrefix("/").lowercase().startsWith(q) }
        val contains = all.filter { it !in starts && q in it.name.lowercase() }
        return starts + contains
    }

    /** The commands the CLI listed itself, saved by Bori Code after installing or updating it. */
    private fun checked(guestHome: File, command: String): List<Pair<String, String>> = runCatching {
        val file = File(guestHome, ".bori/agent-commands/$command.json")
        if (!file.isFile || file.length() > MAX_CHECKED_BYTES) return emptyList()
        val a = org.json.JSONObject(file.readText()).optJSONArray("commands") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.startsWith("/") && it.length > 1 } ?: return@mapNotNull null
            name to o.optString("description")
        }
    }.getOrDefault(emptyList())

    // ---- the user's own commands

    /** Claude Code: commands/<dir>/<name>.md (folders become "dir:name") and skills/<name>/SKILL.md. */
    private fun claudeDir(dir: File, source: SlashCommand.Source): List<SlashCommand> =
        mdFiles(File(dir, "commands"), recursive = true).map { (name, f) ->
            SlashCommand("/" + name.replace('/', ':'), describe(f), source)
        } + skills(File(dir, "skills"), source)

    private fun skills(dir: File, source: SlashCommand.Source): List<SlashCommand> =
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
            val skill = File(d, "SKILL.md").takeIf { it.isFile } ?: return@mapNotNull null
            SlashCommand("/" + (frontmatter(skill)["name"] ?: d.name), describe(skill), source)
        }

    /** Gemini / Qwen: commands/<dir>/<name>.toml with a `description = "…"` line; folders become "dir:name". */
    private fun tomlCommands(dir: File, source: SlashCommand.Source): List<SlashCommand> =
        files(dir, "toml", recursive = true).map { (name, f) ->
            val desc = head(f).lineSequence().firstNotNullOfOrNull { TOML_DESC.find(it)?.groupValues?.get(1) }
            SlashCommand("/" + name.replace('/', ':'), desc ?: "Your command", source)
        }

    private fun mdFiles(dir: File, recursive: Boolean) = files(dir, "md", recursive)

    /** (name relative to [dir] without the extension, file) for each file ending in [ext]. */
    private fun files(dir: File, ext: String, recursive: Boolean): List<Pair<String, File>> {
        if (!dir.isDirectory) return emptyList()
        val walk = if (recursive) dir.walkTopDown().maxDepth(4) else dir.walkTopDown().maxDepth(1)
        return walk.filter { it.isFile && it.extension == ext }.take(MAX_FILES)
            .map { it.relativeTo(dir).path.removeSuffix(".$ext") to it }.toList()
    }

    /** The frontmatter `description:`, or else the first line of text. */
    private fun describe(f: File): String {
        frontmatter(f)["description"]?.let { return it }
        val body = head(f).let { t -> if (t.startsWith("---")) t.substringAfter("\n---", "") else t }
        return body.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull { it.isNotEmpty() }
            ?.take(120) ?: "Your command"
    }

    private fun frontmatter(f: File): Map<String, String> {
        val t = head(f)
        if (!t.startsWith("---")) return emptyMap()
        return t.removePrefix("---").substringBefore("\n---").lineSequence().mapNotNull { line ->
            val k = line.substringBefore(':', "").trim()
            val v = line.substringAfter(':', "").trim().trim('"', '\'')
            if (k.isEmpty() || v.isEmpty()) null else k to v.take(160)
        }.toMap()
    }

    private fun head(f: File): String = runCatching {
        f.inputStream().use { input ->
            val buf = ByteArray(HEAD_BYTES)
            var n = 0
            while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            String(buf, 0, n, Charsets.UTF_8)
        }
    }.getOrDefault("")

    private const val HEAD_BYTES = 4096
    private const val MAX_CHECKED_BYTES = 512L * 1024
    private const val MAX_FILES = 200
    private val TOML_DESC = Regex("^\\s*description\\s*=\\s*[\"'](.*?)[\"']\\s*$")

    // ---- built-in commands, by the command Bori Code starts each CLI with

    private val CLAUDE = listOf(
        "/add-dir" to "Add a working directory",
        "/agents" to "Manage subagents",
        "/bug" to "Report a bug to Anthropic",
        "/clear" to "Clear the conversation and start fresh",
        "/compact" to "Summarize the conversation to free up context",
        "/config" to "Open settings",
        "/context" to "Show what's using the context window",
        "/cost" to "Show this session's token use",
        "/doctor" to "Check the installation",
        "/exit" to "Quit Claude Code",
        "/export" to "Export the conversation",
        "/help" to "Show help and commands",
        "/hooks" to "Manage hooks",
        "/init" to "Write a CLAUDE.md for this project",
        "/login" to "Sign in",
        "/logout" to "Sign out",
        "/mcp" to "Manage MCP servers",
        "/memory" to "Edit memory files",
        "/model" to "Choose the model",
        "/permissions" to "Manage tool permissions",
        "/plan" to "Plan mode: plan before editing",
        "/plugin" to "Manage plugins",
        "/resume" to "Resume an earlier conversation",
        "/review" to "Review a pull request",
        "/rewind" to "Go back to an earlier point",
        "/security-review" to "Review pending changes for security issues",
        "/status" to "Show version, model and account",
        "/statusline" to "Set up the status line",
        "/todos" to "Show the to-do list",
        "/usage" to "Show plan usage limits",
        "/vim" to "Toggle vim editing",
    )

    private val ANTIGRAVITY = listOf(
        "/add-dir" to "Add a directory to the workspace",
        "/agents" to "List available agents",
        "/artifact" to "Show an artifact",
        "/btw" to "Ask a side question without interrupting",
        "/clear" to "Clear the conversation",
        "/compact" to "Summarize the conversation to free up context",
        "/config" to "Open settings",
        "/context" to "Show what's using the context window",
        "/copy" to "Copy the last reply",
        "/diff" to "Show the changes made",
        "/effort" to "Set reasoning effort",
        "/exit" to "Quit",
        "/export" to "Export the conversation",
        "/feedback" to "Send feedback",
        "/fork" to "Fork this conversation",
        "/goal" to "Set a goal for the agent",
        "/help" to "Show help and commands",
        "/hooks" to "Manage hooks",
        "/keybindings" to "Edit keyboard shortcuts",
        "/login" to "Sign in",
        "/logout" to "Sign out",
        "/mcp" to "Manage MCP servers",
        "/memory" to "Edit memory",
        "/model" to "Choose the model",
        "/permissions" to "Manage permissions",
        "/plan" to "Plan mode",
        "/plugin" to "Manage plugins",
        "/reflect" to "Reflect on the conversation",
        "/resume" to "Resume an earlier conversation",
        "/review" to "Review changes",
        "/rewind" to "Go back to an earlier point",
        "/settings" to "Open settings",
        "/skills" to "List skills",
        "/status" to "Show status",
        "/statusline" to "Set up the status line",
        "/swarm" to "Run helper agents",
        "/tasks" to "Show background tasks",
        "/usage" to "Show usage",
        "/voice" to "Voice input",
    )

    private val CODEX = listOf(
        "/approvals" to "Choose what Codex may do without asking",
        "/compact" to "Summarize the conversation to free up context",
        "/diff" to "Show the git diff",
        "/init" to "Write an AGENTS.md for this project",
        "/logout" to "Sign out",
        "/mcp" to "List MCP tools",
        "/mention" to "Mention a file",
        "/model" to "Choose the model and reasoning effort",
        "/new" to "Start a new conversation",
        "/quit" to "Quit Codex",
        "/review" to "Review the current changes",
        "/status" to "Show session settings and token use",
    )

    private val GEMINI = listOf(
        "/about" to "Show version info",
        "/auth" to "Change the sign-in method",
        "/bug" to "File an issue",
        "/chat" to "Save, resume or list conversations",
        "/clear" to "Clear the screen and conversation",
        "/compress" to "Summarize the conversation to free up context",
        "/copy" to "Copy the last reply",
        "/directory" to "Manage workspace directories",
        "/docs" to "Open the documentation",
        "/editor" to "Choose the editor",
        "/extensions" to "List extensions",
        "/help" to "Show help",
        "/init" to "Write a GEMINI.md for this project",
        "/mcp" to "List MCP servers and tools",
        "/memory" to "Manage memory",
        "/model" to "Choose the model",
        "/privacy" to "Privacy notice",
        "/quit" to "Quit",
        "/restore" to "Restore files from a checkpoint",
        "/settings" to "Open settings",
        "/stats" to "Show session statistics",
        "/theme" to "Change the theme",
        "/tools" to "List available tools",
        "/vim" to "Toggle vim mode",
    )

    private val OPENCODE = listOf(
        "/compact" to "Summarize the session",
        "/details" to "Toggle tool details",
        "/editor" to "Write the message in an external editor",
        "/exit" to "Quit",
        "/export" to "Export the conversation",
        "/help" to "Show help",
        "/init" to "Write an AGENTS.md for this project",
        "/models" to "Choose the model",
        "/new" to "Start a new session",
        "/redo" to "Redo the undone message",
        "/sessions" to "List and switch sessions",
        "/share" to "Share this session",
        "/themes" to "Change the theme",
        "/undo" to "Undo the last message",
    )

    private val AIDER = listOf(
        "/add" to "Add files to the chat",
        "/architect" to "Architect mode: plan, then edit",
        "/ask" to "Ask without editing files",
        "/clear" to "Clear the chat history",
        "/code" to "Ask for code changes",
        "/commit" to "Commit outside changes",
        "/diff" to "Show changes since the last message",
        "/drop" to "Remove files from the chat",
        "/exit" to "Quit",
        "/git" to "Run a git command",
        "/help" to "Ask about aider",
        "/lint" to "Lint and fix files",
        "/ls" to "List files in and out of the chat",
        "/map" to "Show the repository map",
        "/model" to "Switch the main model",
        "/read-only" to "Add files for reference only",
        "/reset" to "Drop all files and clear history",
        "/run" to "Run a shell command",
        "/test" to "Run tests and fix failures",
        "/tokens" to "Report token use",
        "/undo" to "Undo the last aider commit",
        "/web" to "Add a web page to the chat",
    )

    private val COPILOT = listOf(
        "/add-dir" to "Add a trusted directory",
        "/agent" to "Choose an agent",
        "/clear" to "Clear the conversation",
        "/cwd" to "Change the working directory",
        "/exit" to "Quit",
        "/feedback" to "Send feedback",
        "/help" to "Show help",
        "/login" to "Sign in",
        "/logout" to "Sign out",
        "/mcp" to "Manage MCP servers",
        "/model" to "Choose the model",
        "/session" to "Show session info",
        "/usage" to "Show usage",
    )

    private val KIMI = listOf(
        "/clear" to "Clear the context",
        "/compact" to "Summarize the context",
        "/exit" to "Quit",
        "/help" to "Show help",
        "/init" to "Write an AGENTS.md for this project",
        "/model" to "Choose the model",
        "/setup" to "Set up the API",
    )

    private val CRUSH = listOf(
        "/help" to "Show help",
        "/init" to "Write a context file for this project",
        "/model" to "Choose the model",
        "/new" to "Start a new session",
        "/sessions" to "Switch sessions",
    )

    private val BUILT_IN: Map<String, List<Pair<String, String>>> = mapOf(
        "claude" to CLAUDE,
        "antigravity" to ANTIGRAVITY,
        "codex" to CODEX,
        "gemini" to GEMINI,
        "qwen" to GEMINI.filterNot { it.first in setOf("/auth", "/extensions", "/privacy") },
        "opencode" to OPENCODE,
        "aider" to AIDER,
        "copilot" to COPILOT,
        "kimi" to KIMI,
        "crush" to CRUSH,
        "codebuddy" to CLAUDE.filter { it.first in setOf("/clear", "/compact", "/help", "/init", "/mcp", "/model", "/memory", "/resume", "/exit") },
    )
}
