package com.bori.core.linux

import org.junit.Assert.assertEquals
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

    @Test fun agySandboxSwitchIsPutBackWhenDropped() {
        // Antigravity 1.5.8 saves with omitempty: our `false` vanishes and the sandbox comes back.
        assertTrue(GuestPolicy.sandboxOffMissing(org.json.JSONObject("""{"model":"x","trustedWorkspaces":["/root/workspace"]}""")))
        assertTrue(GuestPolicy.sandboxOffMissing(org.json.JSONObject("""{"enableTerminalSandbox":true}""")))
        assertFalse(GuestPolicy.sandboxOffMissing(org.json.JSONObject("""{"enableTerminalSandbox":false}""")))
    }

    @Test fun agyWorkspaceEditsAreAllowedAndOtherSettingsKept() {
        val s = org.json.JSONObject("""{"model":"x","permissions":{"deny":["read_file(/root/.claude)"],"allow":["command(git status)"]}}""")
        assertTrue(GuestPolicy.applyBoriAgySettings(s))
        assertEquals("x", s.getString("model"))
        assertFalse(s.getBoolean("enableTerminalSandbox"))
        val allow = s.getJSONObject("permissions").getJSONArray("allow").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(listOf("command(git status)") + GuestPolicy.WORKSPACE_ALLOW, allow)
        assertEquals(1, s.getJSONObject("permissions").getJSONArray("deny").length())
        // Already there: nothing to write.
        assertFalse(GuestPolicy.applyBoriAgySettings(s))
    }

    @Test fun agyStaleEditGrantIsRemoved() {
        val settings = org.json.JSONObject("""{"enableTerminalSandbox":false,"permissions":{"allow":["write_file(/root/workspace/)","edit_file(/root/workspace/)","command(ls)"]}}""")
        assertTrue(GuestPolicy.applyBoriAgySettings(settings))
        val allow = settings.getJSONObject("permissions").getJSONArray("allow")
        assertEquals(listOf("write_file(/root/workspace/)", "command(ls)"), (0 until allow.length()).map { allow.getString(it) })
        assertFalse(GuestPolicy.applyBoriAgySettings(settings))
    }

    @Test fun agyHookKeepsUserHooksAndIsStable() {
        val hooks = org.json.JSONObject("""{"mine":{"Stop":[{"command":"echo hi"}]}}""")
        assertTrue(GuestPolicy.agyHooks(hooks))
        assertFalse(GuestPolicy.agyHooks(hooks))
        assertTrue(hooks.has("mine"))
        val groups = hooks.getJSONObject(GuestPolicy.AGY_HOOK_NAME).getJSONArray("PreToolUse")
        assertEquals(GuestPolicy.AGY_EDIT_TOOLS, (0 until groups.length()).map { groups.getJSONObject(it).getString("matcher") })
    }

    @Test fun agyEditDecisionStaysInTheProjects() {
        assertEquals("allow", GuestPolicy.agyEditDecision("/root/workspace/My App/app/A.kt"))
        assertEquals("allow", GuestPolicy.agyEditDecision("\"/root/workspace/x/B.kt"))
        assertEquals("allow", GuestPolicy.agyEditDecision("/root/.gemini/antigravity-cli/brain/abc/task.md"))
        assertEquals("ask", GuestPolicy.agyEditDecision("/root/.bashrc"))
        assertEquals("ask", GuestPolicy.agyEditDecision("/root/workspace/../.bashrc"))
        assertEquals("ask", GuestPolicy.agyEditDecision("/root/workspace"))
        assertEquals("ask", GuestPolicy.agyEditDecision(""))
    }
}
