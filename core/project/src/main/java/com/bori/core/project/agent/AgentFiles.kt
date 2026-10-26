package com.bori.core.project.agent

import java.io.File

/** Who is working on a project right now, from `.bori/agent.lock`. */
data class AgentLock(val agent: String, val task: String, val startedMs: Long) {
    val isStale: Boolean get() = System.currentTimeMillis() - startedMs > AgentFiles.LOCK_TTL_MS
}

/**
 * The files the built-in agent, Claude Code and Antigravity share in each project's `.bori/`
 * folder, so they work from one plan instead of three separate ones:
 * - `plan.md`: the goal, scope, checklist, status and who does what;
 * - `handoff.md`: what the last agent did, the last Bori build result and the next step;
 * - `agent.lock`: which agent is working now, so the others don't edit the same files.
 *
 * Plain files, so they survive Android killing a process and every agent can read them.
 */
object AgentFiles {

    const val DIR = ".bori"
    const val PLAN = "plan.md"
    const val HANDOFF = "handoff.md"
    const val LOCK = "agent.lock"
    const val LOCK_TTL_MS = 2 * 60 * 60 * 1000L

    /** The name the built-in agent uses in the lock. */
    const val BUILT_IN_AGENT = "Bori agent"

    private const val BUILD_HEADING = "## Last Bori build"

    fun dir(root: File) = File(root, DIR)

    fun readPlan(root: File): String? = read(File(dir(root), PLAN))

    fun readHandoff(root: File): String? = read(File(dir(root), HANDOFF))

    fun writeHandoff(root: File, text: String) {
        File(dir(root).apply { mkdirs() }, HANDOFF).writeText(text)
    }

    /** Writes the starter plan, unless the project already has one. */
    fun writePlanTemplate(root: File, projectName: String) {
        val file = File(dir(root).apply { mkdirs() }, PLAN)
        if (!file.exists()) file.writeText(TEMPLATE_PLAN.replace("{name}", projectName))
    }

    fun readLock(root: File): AgentLock? {
        val text = read(File(dir(root), LOCK)) ?: return null
        val fields = text.lines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
        }.toMap()
        val agent = fields["agent"]?.ifBlank { null } ?: return null
        return AgentLock(agent, fields["task"].orEmpty(), fields["started"]?.toLongOrNull() ?: 0L)
    }

    /** A fresh lock held by someone other than [self], or null when [self] is free to work. */
    fun otherAgentLock(root: File, self: String = BUILT_IN_AGENT): AgentLock? =
        readLock(root)?.takeIf { !it.isStale && !it.agent.equals(self, ignoreCase = true) }

    fun acquire(root: File, agent: String, task: String) {
        File(dir(root).apply { mkdirs() }, LOCK).writeText(
            "agent=$agent\ntask=${task.replace('\n', ' ').take(120)}\nstarted=${System.currentTimeMillis()}\n",
        )
    }

    /** Removes the lock only when [agent] holds it, so one agent never frees another's lock. */
    fun release(root: File, agent: String) {
        val lock = readLock(root) ?: return
        if (lock.agent.equals(agent, ignoreCase = true)) File(dir(root), LOCK).delete()
    }

    /** Puts the latest on-device build result in handoff.md, replacing the previous one. */
    fun appendBuildResult(root: File, ok: Boolean, summary: String) {
        val existing = readHandoff(root).orEmpty()
        val start = existing.indexOf(BUILD_HEADING)
        val kept = if (start < 0) existing else {
            val next = existing.indexOf("\n## ", start + BUILD_HEADING.length)
            existing.substring(0, start) + if (next < 0) "" else existing.substring(next + 1)
        }
        val section = buildString {
            append(BUILD_HEADING).append('\n')
            append(if (ok) "Result: OK" else "Result: FAILED").append('\n')
            append("Time: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())).append('\n')
            if (summary.isNotBlank()) append(summary.trim()).append('\n')
        }
        val base = kept.trimEnd()
        writeHandoff(root, if (base.isEmpty()) section else "$base\n\n$section")
    }

    private fun read(file: File): String? =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.ifBlank { null }

    val TEMPLATE_PLAN = """
        |# Plan: {name}
        |STATUS: DRAFT
        |
        |## Goal
        |-
        |
        |## In scope (files)
        |-
        |
        |## Out of scope
        |-
        |
        |## Checklist
        |- [ ]
        |
        |## Roles
        |- Bori agent: quick edits, offline work
        |- Claude Code: big changes and planning
        |- Antigravity: Gemini-side work, scripts
        |
        |<!-- Shared by every agent in this project. Set STATUS: APPROVED once you agree with it.
        |     Agents write what they did to .bori/handoff.md and hold .bori/agent.lock while working. -->
        |""".trimMargin()
}
