package com.bori.core.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Claude Code's managed deny rules: its own folder stays usable, other tools' secrets don't. */
class GuestPolicyRulesTest {
    private val rules = GuestPolicy.claudeDenyRules()

    /** Claude Code's gitignore-style rule paths, enough for these rules: `**` any depth, `*` one segment. */
    private fun denies(tool: String, path: String): Boolean = rules.any { rule ->
        val m = Regex("""(\w+)\(/(/.*)\)""").matchEntire(rule) ?: return@any false
        if (m.groupValues[1] != tool) return@any false
        val pattern = m.groupValues[2]
        val regex = buildString {
            var i = 0
            while (i < pattern.length) {
                when {
                    pattern.startsWith("/**", i) -> { append("(/.*)?"); i += 3 }
                    pattern[i] == '*' -> { append("[^/]*"); i++ }
                    else -> { append(Regex.escape(pattern[i].toString())); i++ }
                }
            }
        }
        Regex(regex).matches(path)
    }

    @Test fun claudeKeepsItsOwnFolder() {
        listOf(
            "/root/.claude/plans/a-plan.md",
            "/root/.claude/projects/-root-workspace-app/memory/note.md",
            "/root/.claude/projects/-root-workspace-app/abc/tool-results/out.txt",
            "/root/.claude/agents/reviewer.md",
            "/root/.claude/skills/x/SKILL.md",
            "/root/workspace/app/.bori/plan.md",
        ).forEach { path ->
            assertFalse("Edit denied: $path", denies("Edit", path))
            assertFalse("Read denied: $path", denies("Read", path))
        }
    }

    @Test fun secretsAndSettingsStayDenied() {
        assertTrue(denies("Read", "/root/.config/gh/hosts.yml"))
        assertTrue(denies("Read", "/root/.gemini/oauth_creds.json"))
        assertTrue(denies("Edit", "/root/.gemini/antigravity-cli/settings.json"))
        assertTrue(denies("Read", "/root/.ssh/id_ed25519"))
        assertTrue(denies("Edit", "/root/.claude/settings.json"))
        assertTrue(denies("Read", "/root/.claude/.credentials.json"))
        assertTrue(denies("Edit", "/root/.bashrc"))
        assertTrue(denies("Edit", "/usr/local/bin/bori-status"))
        assertTrue(denies("Edit", "/etc/claude-code/managed-settings.json"))
    }
}
