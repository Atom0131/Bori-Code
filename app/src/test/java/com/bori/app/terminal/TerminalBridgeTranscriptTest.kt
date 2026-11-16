package com.bori.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalBridgeTranscriptTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun read(vararg lines: String) =
        TerminalBridge.Follower().read(tmp.newFile("s.jsonl").apply { writeText(lines.joinToString("\n") + "\n") })!!

    @Test fun thinkingToolOutputAndText() {
        val m = read(
            """{"type":"user","message":{"role":"user","content":"build it"}}""",
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"Gradle first."},{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"./gradlew build"}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"BUILD SUCCESSFUL\nin 3s","is_error":false}]}}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Done."}]}}""",
        )
        assertEquals(listOf("user", "thinking", "tool_result", "assistant"), m.map { it.role })
        assertEquals("Gradle first.", m[1].content)
        assertEquals("[Bash] ./gradlew build\nBUILD SUCCESSFUL\nin 3s", m[2].content)
    }

    @Test fun apiErrorIsMarked() {
        val m = read(
            """{"type":"user","message":{"role":"user","content":"go"}}""",
            """{"type":"assistant","isApiErrorMessage":true,"error":"server_error","message":{"content":[{"type":"text","text":"API Error: 529 Overloaded"}]}}""",
        )
        assertEquals("⚠ API Error: 529 Overloaded", m.last().content)
    }

    @Test fun errorOutputIsMarked() {
        val m = read(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/x"}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":[{"type":"text","text":"No such file"}],"is_error":true}]}}""",
        )
        assertEquals("⚠ [Read] /x\nNo such file", m.single().content)
    }

    @Test fun slashCommandAndItsOutput() {
        val m = read(
            """{"type":"user","message":{"content":"<command-name>/cost</command-name>\n<command-message>cost</command-message>\n<command-args></command-args>"}}""",
            """{"type":"user","message":{"content":"<local-command-stdout>Total cost: \u001b[1m${'$'}0.12\u001b[22m</local-command-stdout>"}}""",
            """{"type":"user","message":{"content":"<system-reminder>hidden</system-reminder>"}}""",
        )
        assertEquals(1, m.size)
        assertEquals("[/cost]\nTotal cost: ${'$'}0.12", m[0].content)
    }

    @Test fun longOutputIsClipped() {
        val out = (1..40).joinToString("\\n") { "line $it" }
        val m = read(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t3","name":"Bash","input":{"command":"seq 40"}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t3","content":"$out"}]}}""",
        )
        assertTrue(m[0].content.endsWith("… (more in the terminal)"))
        assertTrue("line 13" !in m[0].content)
    }

    private fun readAgy(vararg lines: String) =
        TerminalBridge.Follower().read(tmp.newFile("transcript.jsonl").apply { writeText(lines.joinToString("\n") + "\n") })!!

    // Step shapes as Antigravity 1.5.8 writes them (2026-11-13), with a generic project.
    @Test fun agyResultsGoUnderTheirCalls() {
        val m = readAgy(
            """{"type":"USER_INPUT","content":"<USER_REQUEST>\nfix the build\n</USER_REQUEST>"}""",
            """{"type":"PLANNER_RESPONSE","step_index":5,"content":"","tool_calls":[{"name":"edit_file","args":{"Path":"\"/root/workspace/My App/app/build.gradle.kts\"","Replacement":"\"x\""}},{"name":"run_command","args":{"CommandLine":"\"git status\""}}]}""",
            """{"type":"GENERIC","status":"DONE","content":"Created At: 2026-11-13T13:27:24-06:00\nCompleted At: 2026-11-13T13:27:26-06:00\nMade 1 replacement in /root/workspace/My App/app/build.gradle.kts starting at line 47 (+4/-4 lines)."}""",
            """{"type":"GENERIC","status":"ERROR","error":"fork/exec: operation not permitted","content":"Created At: 2026-11-13T13:01:55-06:00\nCompleted At: 2026-11-13T13:01:57-06:00\n"}""",
            """{"type":"PLANNER_RESPONSE","content":"Updated [build.gradle.kts](file:///root/workspace/My%20App/app/build.gradle.kts#L46-L50).","tool_calls":[]}""",
        )
        assertEquals(listOf("user", "tool_result", "tool_result", "assistant"), m.map { it.role })
        assertEquals("fix the build", m[0].content)
        assertEquals("[edit_file] app/build.gradle.kts\nMade 1 replacement in app/build.gradle.kts starting at line 47 (+4/-4 lines).", m[1].content)
        assertEquals("⚠ [run_command] git status\nfork/exec: operation not permitted", m[2].content)
        assertEquals("Updated `app/build.gradle.kts`.", m[3].content)
    }

    @Test fun agyCallWithoutResultYetStillShows() {
        val m = readAgy(
            """{"type":"PLANNER_RESPONSE","content":"","tool_calls":[{"name":"write_file","args":{"TargetFile":"\"/root/workspace/p/a.kt\""}}]}""",
        )
        assertEquals("[write_file] a.kt", m.single().content)
    }
}
