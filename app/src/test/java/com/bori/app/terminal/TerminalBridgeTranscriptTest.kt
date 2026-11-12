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
}
