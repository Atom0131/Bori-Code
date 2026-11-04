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
}
