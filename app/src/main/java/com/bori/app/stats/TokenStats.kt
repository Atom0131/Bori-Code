package com.bori.app.stats

import com.bori.core.ai.model.ChatChunk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/**
 * Token counts and speed for the stats overlay and notification: Chat's replies (from the stream and
 * its usage report) and Claude Code's terminal tabs (from its own transcript, read after each hook).
 */
object TokenStats {

    data class State(
        /** The latest speed, from whichever reply ended or is streaming most recently. */
        val tokensPerSecond: Float? = null,
        /** True while [tokensPerSecond] is a live estimate of a Chat reply still streaming. */
        val live: Boolean = false,
        /** Where the latest numbers come from: "Chat" or a terminal tab's title. */
        val source: String? = null,
        /** The context the latest reply was sent (input + cached), the agent's working memory. */
        val contextTokens: Long? = null,
        /** Output tokens written this app session, Chat and Claude Code together. */
        val outputTokens: Long = 0,
    ) {
        val any: Boolean get() = tokensPerSecond != null || outputTokens > 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Chat's current stream.
    private var streamStart = 0L
    private var streamChars = 0
    private var lastLive = 0L

    fun onChatChunk(chunk: ChatChunk) {
        val now = System.nanoTime()
        when (chunk) {
            is ChatChunk.TextDelta, is ChatChunk.ReasoningDelta, is ChatChunk.ToolCallDelta -> {
                if (streamStart == 0L) {
                    streamStart = now
                    streamChars = 0
                }
                streamChars += when (chunk) {
                    is ChatChunk.TextDelta -> chunk.text.length
                    is ChatChunk.ReasoningDelta -> chunk.text.length
                    is ChatChunk.ToolCallDelta -> chunk.inputJsonDelta.length
                    else -> 0
                }
                // A rough figure (about 4 characters a token) while it streams, at most twice a second.
                if (now - lastLive > 500_000_000L) {
                    lastLive = now
                    val seconds = (now - streamStart) / 1e9
                    if (seconds > 0.5) {
                        _state.value = _state.value.copy(
                            tokensPerSecond = (streamChars / 4.0 / seconds).toFloat(), live = true, source = "Chat",
                        )
                    }
                }
            }
            is ChatChunk.Usage -> {
                val seconds = if (streamStart == 0L) 0.0 else (now - streamStart) / 1e9
                val s = _state.value
                _state.value = s.copy(
                    tokensPerSecond = if (seconds > 0.3 && chunk.completionTokens > 0) (chunk.completionTokens / seconds).toFloat() else s.tokensPerSecond,
                    live = false,
                    source = "Chat",
                    contextTokens = chunk.promptTokens.toLong().takeIf { it > 0 } ?: s.contextTokens,
                    outputTokens = s.outputTokens + chunk.completionTokens.coerceAtLeast(0),
                )
                streamStart = 0L
            }
            is ChatChunk.Done, is ChatChunk.Error -> {
                if (_state.value.live) _state.value = _state.value.copy(live = false)
                streamStart = 0L
            }
            else -> Unit
        }
    }

    private val claude = HashMap<String, ClaudeUsage>()

    /**
     * The Claude Code tabs to follow while stats show: (tab title, transcript file). Set by
     * TerminalSessionManager; [pollClaude] reads them between hooks, so a long turn updates live.
     */
    @Volatile var claudeSources: () -> List<Pair<String, File>> = { emptyList() }

    fun pollClaude() {
        runCatching { claudeSources() }.getOrNull()?.forEach { (title, file) -> onClaudeTranscript(title, file) }
    }

    /** After a Claude Code hook in tab [title]: reads what its transcript added since last time. */
    @Synchronized
    fun onClaudeTranscript(title: String, file: File) {
        val known = claude[file.path]
        val usage = known ?: ClaudeUsage().also { claude[file.path] = it }
        val before = usage.outputTokens
        if (!usage.readNew(file)) return
        // The first read of a transcript (a resumed conversation) is its history, not this session's work.
        if (known == null) return
        val s = _state.value
        _state.value = s.copy(
            tokensPerSecond = usage.lastSpeed ?: s.tokensPerSecond,
            live = false,
            source = title,
            contextTokens = usage.lastContext ?: s.contextTokens,
            outputTokens = s.outputTokens + (usage.outputTokens - before),
        )
    }
}

/**
 * Claude Code's usage from its transcript (`~/.claude/projects/<p>/<session>.jsonl`). Each API reply
 * is written as one line per content block, every one repeating the reply's `message.id` and usage,
 * so replies are counted once by id. Only bytes added since the last read are parsed.
 */
class ClaudeUsage {
    private var offset = 0L
    private var partial = ""
    private val seen = HashMap<String, Long>()
    /** The id, first-line start time and latest line time of the reply being written. */
    private var currentId: String? = null
    private var currentStart: Instant? = null
    private var previousLineAt: Instant? = null

    var outputTokens = 0L
        private set
    var lastContext: Long? = null
        private set
    var lastSpeed: Float? = null
        private set

    /** Reads new lines from [file]; false when nothing new. */
    fun readNew(file: File): Boolean {
        val length = file.length()
        if (length < offset) { offset = 0; partial = "" } // A new or rewritten file.
        if (length == offset) return false
        // A long transcript's start doesn't matter: the first line read may be cut, and is skipped.
        if (offset == 0L && length > MAX_READ) offset = length - MAX_READ
        val text = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val bytes = ByteArray((length - offset).coerceAtMost(MAX_READ).toInt())
                raf.readFully(bytes)
                offset += bytes.size
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull() ?: return false
        addLines(text)
        return true
    }

    /** Parses whole lines; a trailing half line waits for the next read. */
    fun addLines(text: String) {
        val all = partial + text
        val end = all.lastIndexOf('\n')
        partial = if (end < 0) all else all.substring(end + 1)
        if (end < 0) return
        all.substring(0, end).lineSequence().forEach { addLine(it) }
    }

    private fun addLine(line: String) {
        if (line.isBlank()) return
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        val at = runCatching { Instant.parse(json.optString("timestamp")) }.getOrNull()
        val message = json.optJSONObject("message")
        val id = message?.optString("id")?.takeIf { it.isNotEmpty() }
        val usage = message?.optJSONObject("usage")
        if (json.optString("type") == "assistant" && id != null && usage != null) {
            if (id != currentId) {
                currentId = id
                currentStart = previousLineAt
            }
            val out = usage.optLong("output_tokens")
            val old = seen[id] ?: 0L
            if (out > old) {
                seen[id] = out
                outputTokens += out - old
            }
            lastContext = usage.optLong("input_tokens") + usage.optLong("cache_read_input_tokens") +
                usage.optLong("cache_creation_input_tokens")
            val start = currentStart
            if (start != null && at != null) {
                val seconds = (at.toEpochMilli() - start.toEpochMilli()) / 1000.0
                if (seconds > 0.5 && out > 0) lastSpeed = (out / seconds).toFloat()
            }
        }
        if (at != null) previousLineAt = at
    }

    private companion object {
        const val MAX_READ = 2L * 1024 * 1024
    }
}
