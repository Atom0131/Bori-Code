package com.bori.app.terminal

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads Antigravity CLI's own transcript (`~/.gemini/antigravity-cli/brain/<id>/.system_generated/
 * logs/transcript.jsonl`), as the Windows auto-enter helper does: the last PLANNER_RESPONSE step lists
 * the tool calls of this turn, and each GENERIC step after it is one that ran.
 */
object AgyTranscript {
    const val MAX_AGE_MS = 60 * 60 * 1000L
    private const val TAIL_BYTES = 65_536L
    private val FEEDBACK = Regex("\\\\?\"RequestFeedback\\\\?\"\\s*:\\s*true", RegexOption.IGNORE_CASE)

    /** The next tool call that hasn't run; [key] changes with every step. */
    class Pending(val key: String, val name: String)

    /** Where the turn is: a tool waiting, a question or plan for the user, or finished. */
    class Turn(val pending: Pending?, val asksUser: Boolean, val complete: Boolean)

    /**
     * The newest transcript under [home] (the guest's /root on the host). With [since], transcripts last
     * written before then are skipped, so an older agy session can't be taken for this one.
     */
    fun latest(home: File, since: Long? = null): File? = find(home)
        .filter { since == null || it.lastModified() >= since }
        .maxByOrNull { it.lastModified() }

    private const val WALK_CACHE_MS = 30_000L
    private const val WALK_DEPTH = 6
    private var walkedAt = 0L
    private var walked: List<File> = emptyList()

    /** The known layout first; if agy moves its logs, a bounded walk (cached for 30 s) finds them. */
    private fun find(home: File): List<File> {
        val root = File(home, ".gemini/antigravity-cli")
        val known = File(root, "brain").listFiles()
            ?.map { File(it, ".system_generated/logs/transcript.jsonl") }
            ?.filter { it.isFile }
            .orEmpty()
        if (known.isNotEmpty()) return known
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - walkedAt < WALK_CACHE_MS) return walked
            walked = root.walkTopDown().maxDepth(WALK_DEPTH)
                .filter { it.name == "transcript.jsonl" && it.isFile }
                .toList()
            walkedAt = now
            return walked
        }
    }

    fun read(file: File): Turn? {
        val steps = runCatching { tail(file) }.getOrNull()
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeLast(20)
            ?.mapNotNull { raw -> runCatching { JSONObject(raw) to raw }.getOrNull() }
            ?: return null
        val planner = steps.indexOfLast { it.first.optString("type") == "PLANNER_RESPONSE" }
        if (planner < 0) return null
        val (step, raw) = steps[planner]
        val calls = step.optJSONArray("tool_calls")
        val count = calls?.length() ?: 0
        val after = steps.drop(planner + 1).map { it.first.optString("type") }
        // The user has answered since: a new turn is under way.
        if ("USER_INPUT" in after) return Turn(null, asksUser = false, complete = false)
        val done = after.count { it == "GENERIC" }
        val asks = FEEDBACK.containsMatchIn(raw) ||
            (0 until count).any { calls?.optJSONObject(it)?.optString("name") == "ask_question" }
        if (count == 0) return Turn(null, asks, complete = planner == steps.lastIndex)
        if (done >= count) return Turn(null, asks, complete = false)
        val index = step.optInt("step_index", planner)
        val name = calls?.optJSONObject(done)?.optString("name")?.ifEmpty { null } ?: "tool_call"
        return Turn(Pending("$index:$done", name), asks, complete = false)
    }

    private fun tail(file: File): String = RandomAccessFile(file, "r").use { f ->
        val start = (f.length() - TAIL_BYTES).coerceAtLeast(0)
        f.seek(start)
        val bytes = ByteArray((f.length() - start).toInt())
        f.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    }
}
