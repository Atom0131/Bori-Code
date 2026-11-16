package com.bori.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgyTranscriptTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun transcript(home: File, id: String, vararg lines: String): File {
        val f = File(home, ".gemini/antigravity-cli/brain/$id/.system_generated/logs/transcript.jsonl")
        f.parentFile!!.mkdirs()
        f.writeText(lines.joinToString("\n"))
        return f
    }

    private val planner2 = """{"type":"PLANNER_RESPONSE","step_index":7,"tool_calls":[{"name":"run_command"},{"name":"write_file"}]}"""

    @Test fun pendingToolIsTheFirstNotRun() {
        val f = transcript(tmp.root, "a", planner2, """{"type":"GENERIC"}""")
        val turn = AgyTranscript.read(f)!!
        assertEquals("write_file", turn.pending!!.name)
        assertEquals("7:1", turn.pending!!.key)
        assertFalse(turn.asksUser)
    }

    @Test fun feedbackRequestAsksUser() {
        val f = transcript(tmp.root, "b", """{"type":"PLANNER_RESPONSE","RequestFeedback":true,"tool_calls":[{"name":"notify"}]}""")
        assertTrue(AgyTranscript.read(f)!!.asksUser)
        val g = transcript(tmp.root, "c", """{"type":"PLANNER_RESPONSE","tool_calls":[{"name":"ask_question"}]}""")
        assertTrue(AgyTranscript.read(g)!!.asksUser)
    }

    @Test fun noToolsLastIsComplete() {
        val f = transcript(tmp.root, "d", planner2, """{"type":"PLANNER_RESPONSE","tool_calls":[]}""")
        val turn = AgyTranscript.read(f)!!
        assertTrue(turn.complete)
        assertNull(turn.pending)
    }

    @Test fun userInputStartsANewTurn() {
        val f = transcript(tmp.root, "e", planner2, """{"type":"USER_INPUT"}""")
        assertNull(AgyTranscript.read(f)!!.pending)
    }

    @Test fun sinceSkipsOlderSessions() {
        val old = transcript(tmp.root, "old", planner2)
        old.setLastModified(System.currentTimeMillis() - 60_000)
        assertEquals(old, AgyTranscript.latest(tmp.root))
        assertNull(AgyTranscript.latest(tmp.root, since = System.currentTimeMillis() - 10_000))
        val fresh = transcript(tmp.root, "new", planner2)
        assertEquals(fresh, AgyTranscript.latest(tmp.root, since = System.currentTimeMillis() - 10_000))
    }

    @Test fun pendingCarriesItsMainArgument() {
        val f = transcript(tmp.root, "f",
            """{"type":"PLANNER_RESPONSE","step_index":3,"tool_calls":[{"name":"write_file","args":{"TargetFile":"\"/root/workspace/My App/app/Main.kt\""}}]}""")
        assertEquals("app/Main.kt", AgyTranscript.read(f)!!.pending!!.detail)
    }

    @Test fun linksBecomePaths() {
        assertEquals("See `app/Main.kt` now.", AgyTranscript.cleanLinks("See [Main.kt](file:///root/workspace/My%20App/app/Main.kt#L3-L9) now."))
        assertEquals("the plan (`/root/.gemini/x/plan.md`)", AgyTranscript.cleanLinks("[the plan](file:///root/.gemini/x/plan.md)"))
        assertEquals("[web](https://x.org)", AgyTranscript.cleanLinks("[web](https://x.org)"))
    }

    @Test fun stepOutputDropsTimes() {
        assertEquals("Command exited with code 0.", AgyTranscript.stepOutput("Created At: a\nCompleted At: b\nCreated At: a\nCompleted At: b\nCommand exited with code 0."))
    }
}
