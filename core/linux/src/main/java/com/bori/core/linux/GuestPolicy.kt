package com.bori.core.linux

import android.system.Os
import android.util.Log
import com.bori.core.project.AndroidBuildRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locks the coding CLIs (Claude Code, Antigravity) to one shared folder, [ProotLauncher.GUEST_WORKSPACE]
 * (Bori Code's projects folder), and installs the browser bridge. Applied to a rootfs before every
 * Linux session, so the rules come back if something inside the guest removes them.
 *
 * This is policy inside the CLIs, not an OS sandbox: proot has no namespaces, so a shell command
 * the user approves can still touch the whole rootfs. What the policy guarantees is that the
 * CLIs' own file tools stay in the workspace, each CLI is denied the other's login files, and
 * neither can be switched into skip-all-permissions mode.
 *
 * Bori Code never reads the CLIs' credential files; it only writes the settings below.
 */
@Singleton
class GuestPolicy @Inject constructor(
    private val proot: ProotLauncher,
) {
    fun apply(distroId: String) {
        val rootfs = proot.rootfs(distroId)
        File(rootfs, ProotLauncher.GUEST_WORKSPACE.removePrefix("/")).mkdirs()
        // Tarball extraction drops /tmp's sticky bit; Claude Code refuses to put its sockets there without it.
        runCatching { Os.chmod(File(rootfs, "tmp").absolutePath, "1777".toInt(8)) }
            .onFailure { Log.w(TAG, "/tmp mode", it) }
        runCatching { writeClaudePolicy(rootfs) }.onFailure { Log.w(TAG, "Claude policy", it) }
        runCatching { mergeAntigravitySettings(rootfs) }.onFailure { Log.w(TAG, "Antigravity settings", it) }
        runCatching { writeBrowserShim(rootfs) }.onFailure { Log.w(TAG, "xdg-open shim", it) }
        runCatching { writeProjectRules(rootfs) }.onFailure { Log.w(TAG, "Project rules", it) }
        runCatching { writeShellPrompt(rootfs) }.onFailure { Log.w(TAG, "Shell prompt", it) }
        runCatching { trustWorkspaceRepos(rootfs) }.onFailure { Log.w(TAG, "Git safe.directory", it) }
    }

    /**
     * Files in Bori Code's projects folder (Android shared storage) aren't owned by the guest's
     * root, so git refuses every repo there as "dubious ownership" (emulator, 2026-10-25). The
     * workspace is the user's own, so it's trusted system-wide; other git config is kept.
     */
    private fun trustWorkspaceRepos(rootfs: File) {
        val file = File(rootfs, "etc/gitconfig")
        val line = "directory = ${ProotLauncher.GUEST_WORKSPACE}/*"
        val current = if (file.exists()) file.readText() else ""
        if (current.lineSequence().any { it.trim() == line }) return
        val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        file.writeText("$current$separator# Written by Bori Code: projects on shared storage have another owner.\n[safe]\n\t$line\n")
    }

    /** A prompt that shows the folder (Void's default is just `bash-5.3#`). Login shells read profile.d. */
    private fun writeShellPrompt(rootfs: File) {
        val file = File(rootfs, "etc/profile.d/bori-prompt.sh")
        file.parentFile?.mkdirs()
        file.writeText(
            """
            |# Written by Bori Code.
            |[ -n "${'$'}BASH_VERSION" ] && PS1='\[\e[1;32m\]bori\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\${'$'} '
            |""".trimMargin(),
        )
    }

    /**
     * Tells both CLIs that the workspace holds Android projects built by Bori Code, not Gradle, so they don't
     * try to run or install Gradle and keep to what the on-device build supports. Written to files each
     * CLI always loads (Claude's managed CLAUDE.md, an always-on Antigravity rule), not into the
     * user's projects.
     */
    private fun writeProjectRules(rootfs: File) {
        val rules = """
            |# Bori Code Android projects
            |You are running inside Bori Code, an Android IDE on the user's phone. Each folder in
            |${ProotLauncher.GUEST_WORKSPACE} is an Android app project that Bori Code compiles on the phone.
            |
            |- There is no Gradle, JDK or Android SDK in this Linux environment. Don't run `gradlew` or
            |  `gradle`, and don't try to install them. After editing, tell the user to tap Build in
            |  Bori Code's Build tab, then Install APK.
            |- New apps: the easiest start is Bori Code's New Project, which fills in a working template
            |  (with a test button) for you to edit. If you create one yourself, make it a new folder
            |  directly in ${ProotLauncher.GUEST_WORKSPACE} with the same layout as an existing project:
            |  `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
            |  `app/src/main/AndroidManifest.xml`, sources under `app/src/main/java/<package path>/` and
            |  resources under `app/src/main/res/`. Bori Code lists it the next time it opens.
            |
            |## Working with other agents
            |Bori Code's built-in agent and the other CLI (Claude Code or Antigravity) may work on the same
            |project. They share three files in the project's `.bori/` folder:
            |- First read `.bori/plan.md` (the shared plan: goal, scope, checklist, `STATUS:`, roles) and
            |  `.bori/handoff.md` (what the last agent did and the last Bori build result), then run
            |  `git status`. Don't redo work the handoff says is done. Stay inside the plan's scope.
            |- `.bori/agent.lock` says who is working now (`agent=`, `task=`, `started=` in epoch ms). If
            |  another agent holds it and it's under 2 hours old, ask the user before editing. Otherwise
            |  write your own (`agent=Claude Code` or `agent=Antigravity`) and delete it when you finish.
            |- When you finish, update `.bori/handoff.md`: the goal, files changed, what's left and the next
            |  step. Keep its `## Last Bori build` section; Bori Code writes it after each build.
            |
            |""".trimMargin() + AndroidBuildRules.TEXT + "\n"
        File(rootfs, "etc/claude-code/CLAUDE.md").apply { parentFile?.mkdirs() }.writeText(rules)
        // Antigravity CLI 1.4.7 loads global rules from config/rules/, not antigravity-cli/rules/ (emulator, 2026-10-25).
        val rulesDir = File(rootfs, "root/.gemini/config/rules")
        File(rulesDir, "bori-android.md").apply { parentFile?.mkdirs() }
            .writeText("---\ntrigger: always_on\ndescription: \"Bori Code's on-device Android build rules\"\n---\n\n$rules")
    }

    /**
     * Claude Code's managed settings: highest precedence, can't be overridden by user or project
     * settings. Claude already confines edits to its working directory unless approved; the deny
     * rules make everything outside the workspace un-approvable for its file tools.
     */
    private fun writeClaudePolicy(rootfs: File) {
        val outside = listOf("bin", "etc", "home", "lib", "media", "mnt", "opt", "run", "sbin", "srv", "usr", "var")
        val deny = JSONArray().apply {
            outside.forEach { put("Edit(//$it/**)") }
            put("Edit(//root/.*)")
            put("Edit(//root/.*/**)")
            // Each CLI's login stays its own.
            put("Read(//root/.gemini/**)")
            put("Edit(//root/.gemini/**)")
        }
        val policy = JSONObject().apply {
            put("permissions", JSONObject().apply {
                put("deny", deny)
                put("additionalDirectories", JSONArray())
                put("disableBypassPermissionsMode", "disable")
            })
        }
        val file = File(rootfs, "etc/claude-code/managed-settings.json")
        file.parentFile?.mkdirs()
        file.writeText(policy.toPrettyJson())
    }

    /**
     * Antigravity's settings.json belongs to the user (the CLI writes it too), so only Bori Code's keys
     * are set and everything else is kept. An unreadable file is left alone rather than replaced.
     */
    private fun mergeAntigravitySettings(rootfs: File) {
        val file = File(rootfs, "root/.gemini/antigravity-cli/settings.json")
        val settings = if (file.exists()) {
            runCatching { JSONObject(file.readText().ifBlank { "{}" }) }.getOrNull() ?: return
        } else {
            JSONObject()
        }
        settings.put("allowNonWorkspaceAccess", false)
        // Off on purpose: Antigravity's terminal sandbox needs kernel namespaces, which Android
        // apps don't get. Under proot it doesn't fall back cleanly; it blocked even an approved
        // read-only `find` (emulator, 2026-10-25). Its permission prompts are the gate instead.
        settings.put("enableTerminalSandbox", false)
        // Keep the user's other trusted folders, minus ones under the pre-rename /root/projects mount.
        val old = settings.optJSONArray("trustedWorkspaces") ?: JSONArray()
        val trusted = JSONArray()
        (0 until old.length()).map { old.optString(it) }
            .filter { it.isNotEmpty() && !it.startsWith(LEGACY_PROJECTS) && it != ProotLauncher.GUEST_WORKSPACE }
            .forEach { trusted.put(it) }
        trusted.put(ProotLauncher.GUEST_WORKSPACE)
        settings.put("trustedWorkspaces", trusted)
        settings.put("permissions", (settings.optJSONObject("permissions") ?: JSONObject()).apply {
            val denyRules = optJSONArray("deny") ?: JSONArray()
            // Antigravity rules take path prefixes, not globs.
            listOf(
                "read_file(/root/.claude)",
                "read_file(/root/.claude.json)",
                "write_file(/root/.claude)",
                "write_file(/root/.claude.json)",
            ).forEach { rule ->
                if ((0 until denyRules.length()).none { denyRules.optString(it) == rule }) denyRules.put(rule)
            }
            put("deny", denyRules)
        })
        file.parentFile?.mkdirs()
        file.writeText(settings.toPrettyJson())
    }

    /**
     * Runs [block] with Antigravity allowed exactly [rules] (`command(<line>)`,
     * `write_file(<path>)`), then takes them back out. Headless Antigravity can't ask
     * permission, so this is how the user's "Allow once" in Bori Code's chat reaches it. Its rules
     * match by prefix, so an approval only lasts for the one turn [block] runs. Rules the user
     * already had are left in place.
     */
    suspend fun <T> withAntigravityRules(distroId: String, rules: List<String>, block: suspend () -> T): T {
        val file = File(proot.rootfs(distroId), "root/.gemini/antigravity-cli/settings.json")
        val added = editAntigravityAllowRules(file) { allow ->
            rules.filter { rule -> (0 until allow.length()).none { allow.optString(it) == rule } }
                .onEach { allow.put(it) }
        }
        try {
            return block()
        } finally {
            if (added.isNotEmpty()) editAntigravityAllowRules(file) { allow ->
                val kept = (0 until allow.length()).map { allow.optString(it) }.filterNot { it in added }
                while (allow.length() > 0) allow.remove(0)
                kept.forEach { allow.put(it) }
                added
            }
        }
    }

    private fun editAntigravityAllowRules(file: File, change: (JSONArray) -> List<String>): List<String> {
        val settings = runCatching { JSONObject(file.readText().ifBlank { "{}" }) }.getOrNull() ?: return emptyList()
        val permissions = settings.optJSONObject("permissions") ?: JSONObject().also { settings.put("permissions", it) }
        val allow = permissions.optJSONArray("allow") ?: JSONArray().also { permissions.put("allow", it) }
        val changed = change(allow)
        if (changed.isNotEmpty()) file.writeText(settings.toPrettyJson())
        return changed
    }

    /** `xdg-open URL` inside the guest hands the URL to Bori Code, which opens it in Android's browser. */
    private fun writeBrowserShim(rootfs: File) {
        val shim = File(rootfs, "usr/local/bin/xdg-open")
        shim.parentFile?.mkdirs()
        shim.writeText(
            """
            |#!/bin/sh
            |# Written by Bori Code: hands the URL to the app, which opens it in Android's browser.
            |[ -n "${'$'}1" ] || exit 1
            |printf '%s\n' "${'$'}1" > ${ProotLauncher.GUEST_HANDOFF}/open-url.tmp && mv ${ProotLauncher.GUEST_HANDOFF}/open-url.tmp ${ProotLauncher.GUEST_HANDOFF}/open-url
            |""".trimMargin(),
        )
        shim.setExecutable(true, false)
    }

    /** org.json escapes every "/" as "\\/" (valid, but unreadable for files people open by hand). */
    private fun JSONObject.toPrettyJson(): String = toString(2).replace("\\/", "/") + "\n"

    private companion object {
        const val TAG = "GuestPolicy"

        /** Where the workspace was mounted before it became [ProotLauncher.GUEST_WORKSPACE]. */
        const val LEGACY_PROJECTS = "/root/projects"
    }
}
