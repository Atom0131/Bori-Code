package com.bori.app.terminal

import android.util.Log
import com.termux.terminal.TerminalSession
import java.io.File

/**
 * Presses Enter for a coding CLI's approval prompts, so a long agent run doesn't need a tap for every
 * tool call. A port of the Windows agy auto-enter helper (win_auto_script_3/auto_enter.py), with its
 * two checks:
 *  - Antigravity's own transcript: its last plan step lists tool calls that haven't run yet.
 *  - The bottom of the screen: a Yes / Enter prompt ([PROMPTS]), while the CLI isn't busy.
 * It fires once per screen, and turns itself off after [IDLE_STOP_MS] without a prompt.
 * Approving is all it does: it never answers a question or a plan, and the guest's deny rules
 * (GuestPolicy) still apply to whatever it approves.
 */
class AutoEnter(
    private val session: TerminalSession,
    /** The guest's /root on the host, for Antigravity's transcripts; null when the tab isn't agy. */
    private val agyHome: File?,
) {
    /** Why the last Enter went, for the notification. */
    var lastReason: String? = null
        private set

    /** Transcripts last written before this (less a little slack) belong to an older agy session. */
    private val startedAt = System.currentTimeMillis()
    private var lastActivity = startedAt
    private var lastEnterAt = 0L
    private var lastScreenEnterAt = 0L
    private val stuck = HashSet<String>()
    private var firedScreen: Int? = null
    private var seenScreen: Int? = null
    private val attempts = HashMap<String, Int>()
    private val attemptAt = HashMap<String, Long>()

    private var stuckPending = false

    /** True once after agy ignored three Enters on one tool call ([lastReason] says so). */
    fun takeStuck(): Boolean = stuckPending.also { stuckPending = false }

    /** Whether it's been quiet long enough to turn itself off. */
    fun idle(now: Long): Boolean = now - lastActivity >= IDLE_STOP_MS

    /** Looks for a prompt and presses Enter if there is one; returns true when it did. */
    fun check(now: Long): Boolean {
        if (now - lastEnterAt < DEBOUNCE_MS) return false
        // A shell waiting for a command never needs an Enter, whatever the lines above say.
        if (ShellPrompt.REGEX.containsMatchIn(cursorLine())) return false
        val scan = bottomLines()
        val text = scan.joinToString("\n")
        // "Working..." in the last few lines means it's running, not asking.
        if (BUSY.containsMatchIn(scan.takeLast(BUSY_LINES).joinToString("\n"))) return false
        val turn = agyHome?.let { home ->
            AgyTranscript.latest(home, since = startedAt - SINCE_SLACK_MS)?.let { file ->
                val age = now - file.lastModified()
                if (age > AgyTranscript.MAX_AGE_MS) null else AgyTranscript.read(file)?.let { it to age }
            }
        }
        var reason = turn?.let { (t, age) -> transcriptReason(t, age, now) }
        // When agy's transcript says the turn is done or asks the user, the screen's "Yes" is theirs to pick.
        val screenOk = turn == null || (!turn.first.complete && !turn.first.asksUser)
        if (reason == null && screenOk && now - lastScreenEnterAt >= SCREEN_COOLDOWN_MS) {
            reason = screenReason(scan, text)
            if (reason != null) lastScreenEnterAt = now
        }
        reason ?: return false
        session.write("\r")
        lastEnterAt = now
        lastActivity = now
        lastReason = reason
        return true
    }

    /** The last non-blank lines of the visible screen, trimmed (output may not reach its bottom yet). */
    private fun bottomLines(): List<String> {
        val emulator = session.emulator ?: return emptyList()
        val text = emulator.screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows - 1)
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(BOTTOM_LINES)
    }

    /** The cursor's line, up to the cursor. */
    private fun cursorLine(): String {
        val emulator = session.emulator ?: return ""
        val screen = emulator.screen
        val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(emulator.cursorRow))
        return String(row.mText, 0, row.findStartOfColumn(emulator.cursorCol.coerceIn(0, emulator.mColumns)))
    }

    private fun screenReason(bottom: List<String>, text: String): String? {
        val key = text.hashCode()
        // The screen moved on: a new prompt may fire again.
        if (key != seenScreen) {
            seenScreen = key
            firedScreen = null
        }
        if (key == firedScreen || VETO.containsMatchIn(text)) return null
        for (line in bottom) {
            // Past steps, and commands typed at a shell (which may contain "[y/n]" themselves).
            if (HISTORY.containsMatchIn(line) || ShellPrompt.REGEX.containsMatchIn(line) || IGNORES.any { it.containsMatchIn(line) }) continue
            val m = PROMPTS.firstNotNullOfOrNull { it.find(line) } ?: continue
            firedScreen = key
            return "prompt \"${m.value.trim()}\""
        }
        return null
    }

    /** Antigravity's latest transcript says a tool call is waiting for approval. */
    private fun transcriptReason(turn: AgyTranscript.Turn, age: Long, now: Long): String? {
        if (age < TRANSCRIPT_SETTLE_MS) return null
        val pending = turn.pending?.takeIf { !turn.asksUser } ?: return null
        val tries = attempts[pending.key] ?: 0
        val since = now - (attemptAt[pending.key] ?: 0L)
        if (tries >= MAX_TRIES) {
            // agy didn't take three Enters: say so once instead of going quiet.
            if (since >= RETRY_MS && stuck.add(pending.key)) {
                lastReason = "agy ignored Enter 3×"
                stuckPending = true
                Log.w(TAG, "agy ignored Enter 3x on ${pending.name} (${pending.key})")
            }
            return null
        }
        if (tries > 0 && since < RETRY_MS) return null
        attempts[pending.key] = tries + 1
        attemptAt[pending.key] = now
        return "tool ${pending.name} waiting"
    }

    companion object {
        const val IDLE_STOP_MS = 20 * 60 * 1000L
        private const val DEBOUNCE_MS = 800L
        private const val TAG = "AutoEnter"
        private const val BOTTOM_LINES = 10
        private const val BUSY_LINES = 6
        private const val SCREEN_COOLDOWN_MS = 3_000L
        private const val SINCE_SLACK_MS = 2_000L
        private const val TRANSCRIPT_SETTLE_MS = 1_200L
        private const val MAX_TRIES = 3
        private const val RETRY_MS = 2_500L

        /** From the script's config.json, plus Claude Code's numbered menu ("❯ 1. Yes"). */
        private val PROMPTS = listOf(
            "\\[y/n\\]", "\\(y/n\\)", "\\[yes/no\\]", "\\[y/n/\\?\\]",
            "●\\s*Yes", "❯\\s*Yes", "❯\\s*1\\.\\s*Yes", ">\\s*Yes", ">\\s*1\\.\\s*Yes",
            "Run this command\\?",
            "do you want to run", "do you want to proceed", "do you want to make this edit",
            "do you want to create", "hit enter", "press enter", "press return",
        ).map { Regex(it, RegexOption.IGNORE_CASE) }

        /** An empty input prompt is the CLI waiting for the user, not for approval. */
        private val IGNORES = listOf("^>\\s*$", "^gemini>\\s*$", "^antigravity>\\s*$", "^agy>\\s*$")
            .map { Regex(it, RegexOption.IGNORE_CASE) }

        /** Working, not asking. "esc to interrupt" is Claude Code's status line while it runs. */
        private val BUSY = CliStatus.BUSY

        /** Past steps in agy's history, which can contain the same words. */
        private val HISTORY = Regex("^[?●○◦•*]\\s+(Thought for|Edited|Explored|Ran|Running)")

        /** A plan to accept (Claude Code's "No, keep planning") is the user's call, never an Enter. */
        private val VETO = Regex("keep planning|RequestFeedback", RegexOption.IGNORE_CASE)
    }
}
