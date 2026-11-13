package com.bori.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliStatusTest {
    private fun screen(s: String) = s.trimIndent().lines()

    @Test fun claudeSpinnerWithDetail() {
        val st = CliStatus.parse(screen("""
            ⏺ I'll look at the build file first.

            ✻ Flabbergasting… (12s · ↑ 1.2k tokens · esc to interrupt)

            ╭──────────────────────────────╮
            │ >                            │
            ╰──────────────────────────────╯
        """))!!
        assertEquals("Flabbergasting…", st.verb)
        assertEquals("12s · ↑ 1.2k tokens", st.detail)
    }

    @Test fun everyGlyphAndVerb() {
        for (g in listOf("·", "✢", "✳", "✶", "✻", "✽", "*")) {
            for (v in listOf("Thinking…", "Pondering...", "Clauding…", "Compacting conversation…")) {
                assertEquals(v, CliStatus.parse(listOf("$g $v (esc to interrupt)"))?.verb)
            }
        }
    }

    @Test fun noParensAndTip() {
        val st = CliStatus.parse(screen("""
            ✶ Thinking…
              ⎿  Tip: Use /memory to edit what Claude remembers
        """))!!
        assertEquals("Thinking…", st.verb)
        assertNull(st.detail)
        assertEquals("Tip: Use /memory to edit what Claude remembers", st.tip)
    }

    @Test fun keyHintsAreDropped() {
        val st = CliStatus.parse(listOf("✳ Cooking… (3m 2s · ↓ 4.1k tokens · thinking · esc to interrupt · ctrl+t to show todos)"))!!
        assertEquals("3m 2s · ↓ 4.1k tokens · thinking", st.detail)
    }

    @Test fun messageBulletAndDoneLineAreNotStatus() {
        assertNull(CliStatus.parse(screen("""
            ⏺ Done… the build passes now.
            ✻ Worked for 1m 3s
            >
        """)))
    }

    @Test fun otherCliFallback() {
        assertEquals("Running command...", CliStatus.parse(listOf("  Running command... (esc to interrupt)"))?.verb)
    }

    @Test fun idlePromptIsNothing() {
        assertNull(CliStatus.parse(screen("""
            ⏺ All done.
            >
        """)))
    }

    @Test fun bashApprovalMenu() {
        val m = CliStatus.menu(screen("""
            ╭───────────────────────────────────────────────╮
            │ Bash command                                  │
            │                                               │
            │   ./gradlew assembleDebug                     │
            │   Build the debug APK                         │
            │                                               │
            │ Do you want to proceed?                       │
            │ ❯ 1. Yes                                      │
            │   2. Yes, and don't ask again for ./gradlew   │
            │   3. No, and tell Claude what to do (esc)     │
            ╰───────────────────────────────────────────────╯
        """))!!
        assertEquals("Do you want to proceed?", m.question)
        assertEquals("./gradlew assembleDebug\nBuild the debug APK", m.subject)
        assertEquals(listOf(1, 2, 3), m.options.map { it.number })
        assertEquals("Yes", m.options[0].label)
        assertTrue(m.options[0].selected)
        assertTrue(m.options[2].asksText)
    }

    @Test fun questionMenuWithDescriptionsAndFooter() {
        val m = CliStatus.menu(screen("""
             ☐ Library

            Which chart library should I use?

            ❯ 1. Vico
                 Compose-first, small
              2. MPAndroidChart
                 Older, View based
              3. Type something.

            Enter to select · ↑/↓ to navigate · Esc to cancel
        """))!!
        assertEquals("Which chart library should I use?", m.question)
        assertEquals("Compose-first, small", m.options[0].description)
        assertEquals("Older, View based", m.options[1].description)
        assertTrue(m.options[2].asksText)
        assertTrue(m.canCancel)
    }

    @Test fun numberedListInTextIsNotAMenu() {
        assertNull(CliStatus.menu(screen("""
            ⏺ Here's the plan:
              1. Add the screen
              2. Wire the view model
            >
        """)))
    }

    @Test fun menuMustStartAtOne() {
        assertNull(CliStatus.menu(screen("""
            Pick one?
            ❯ 2. B
              3. C
        """)))
    }

    @Test fun unboxedPermissionPromptWithRules() {
        val m = CliStatus.menu(screen("""
            ⏺ Bash(./gradlew test)
            ────────────────────────────────────────────────
             Bash command

               ./gradlew test
               Run the unit tests

             Do you want to proceed?
             ❯ 1. Yes
               2. Yes, and don't ask again for ./gradlew commands in
                  /root/workspace/app
               3. No, and tell Claude what to do differently (esc)

             Esc to cancel · Tab to amend · ctrl+e to explain
        """))!!
        assertEquals("Do you want to proceed?", m.question)
        assertEquals("./gradlew test\nRun the unit tests", m.subject)
        assertEquals(3, m.options.size)
        assertEquals("/root/workspace/app", m.options[1].description)
        assertTrue(m.canCancel)
    }

    @Test fun blankLinesBetweenQuestionAndOptions() {
        val m = CliStatus.menu(screen("""
            Accept this plan?


            ❯ 1. Yes, and auto-accept edits

              2. Yes, and manually approve edits

              3. No, keep planning
        """))!!
        assertEquals("Accept this plan?", m.question)
        assertEquals(3, m.options.size)
    }

    @Test fun inputLineIsPromptNotMenu() {
        assertTrue(CliStatus.atPrompt(screen("""
            ⏺ Okay, I won't run it.

            ────────────────────────────
            > 
            ────────────────────────────
              ? for shortcuts
        """)))
        assertTrue(CliStatus.atPrompt(listOf("│ > fix the build  │")))
        assertTrue(!CliStatus.atPrompt(screen("""
            Do you want to proceed?
            ❯ 1. Yes
              2. No
        """)))
        assertTrue(!CliStatus.atPrompt(listOf("✻ Thinking… (3s · esc to interrupt)")))
    }

    @Test fun modeLine() {
        assertEquals("Plan mode", CliStatus.mode(screen("""
            >
            ────────────────
              ⏸ plan mode on (shift+tab to cycle)
        """)))
        assertEquals("Accept edits", CliStatus.mode(listOf("  ⏵⏵ accept edits on (shift+tab to cycle)")))
        assertNull(CliStatus.mode(listOf(">", "  ? for shortcuts")))
    }

    @Test fun askUserQuestionFormWithTypedAnswer() {
        val m = CliStatus.menu(screen("""
            ────────────────────────────────────────
             ←  ☐ Bugs  ☐ Next  ✔ Submit  →

            Which bugs do you want fixed first? Tell me what
            you saw: which screen, what you did.

            ❯ 1. I'll describe them
                 Type the bug(s) in the 'Other' box below
              2. Check open items
                 No new bugs.
              3. Type something.
            ────────────────────────────────────────
              4. Chat about this

            Enter to select · Tab/Arrow keys to navigate · Esc to cancel
        """))!!
        assertEquals("Which bugs do you want fixed first? Tell me what you saw: which screen, what you did.", m.question)
        assertNull(m.subject)
        assertTrue(m.steps)
        assertEquals(4, m.options.size)
        assertTrue(m.options[2].inline)
        assertTrue(m.options[2].asksText)
        assertTrue(!m.options[3].asksText)
    }

    @Test fun singleQuestionHasNoSteps() {
        val m = CliStatus.menu(screen("""
            Do you want to proceed?
            ❯ 1. Yes
              2. No, and tell Claude what to do differently (esc)
        """))!!
        assertTrue(!m.steps)
        assertTrue(m.options[1].asksText && !m.options[1].inline)
    }

    @Test fun apiErrorAboveTheInputBox() {
        val lines = screen("""
            > fix the build
              ⎿  API Error: 529 {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

            ╭──────────────────────────────╮
            │ >                            │
            ╰──────────────────────────────╯
              ⏵⏵ accept edits on (shift+tab to cycle)
        """)
        assertTrue(CliStatus.apiError(lines)!!.startsWith("API Error: 529"))
        assertTrue(!CliStatus.mayBeAsking(lines))
    }

    @Test fun retryingErrorWithSpinnerIsNotAnError() {
        assertNull(CliStatus.apiError(screen("""
              ⎿  API Error (Request timed out.) · Retrying in 5 seconds… (attempt 2/10)
            ✻ Pondering… (40s · esc to interrupt)
        """)))
    }

    @Test fun errorFromAnEarlierTurnIsGone() {
        assertNull(CliStatus.apiError(screen("""
              ⎿  API Error: Connection error.
            > try again
            ⏺ Fixed it, the build passes.
            >
        """)))
    }

    @Test fun staleWaitingWithNothingAskedIsNotAsking() {
        // An approval turned down: Claude stopped and shows its message, no menu, no hook.
        val lines = screen("""
            ⏺ Bash(rm -rf build)
              ⎿  Interrupted · What should Claude do instead?
        """)
        assertTrue(!CliStatus.mayBeAsking(lines))
        assertTrue(CliStatus.mayBeAsking(screen("""
            Bash command
              gradle assemble
            Do you want to proceed?
        """)))
    }
}
