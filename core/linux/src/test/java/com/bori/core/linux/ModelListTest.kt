package com.bori.core.linux

import com.bori.core.linux.cli.CliAgentClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelListTest {
    @Test fun idsAndQuotedNames() {
        val out = """
            Available models for your account:
              gemini-4-hydrogen-medium     Gemini 4 Hydrogen (Medium)
              * gemini-4-hydrogen-xhigh    Gemini 4 Hydrogen (Extra)  (current)
              - gemini-4-carbon-high       Gemini 4 Carbon (High)
              Orchestrator (Carbon)
              Orchestrator (Argon)
              claude-opus-5-5-medium
              gemini-3.8-flash-medium

            Run `agy --model <id>` to pick one.
        """.trimIndent()
        assertEquals(
            listOf(
                "gemini-4-hydrogen-medium", "gemini-4-hydrogen-xhigh", "gemini-4-carbon-high",
                "Orchestrator (Carbon)", "Orchestrator (Argon)", "claude-opus-5-5-medium", "gemini-3.8-flash-medium",
            ),
            CliAgentClient.parseModelList(out),
        )
    }

    @Test fun nothingUseful() {
        assertEquals(emptyList<String>(), CliAgentClient.parseModelList("proot warning: something\n\nNo models:\n"))
    }
}
