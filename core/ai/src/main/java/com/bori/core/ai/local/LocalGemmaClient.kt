package com.bori.core.ai.local

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatMessage
import com.bori.core.ai.model.ChatRequest
import com.bori.core.ai.model.CompletionChunk
import com.bori.core.ai.model.CompletionRequest
import com.bori.core.ai.model.MessageContent
import com.bori.core.ai.model.ToolDefinition
import com.bori.core.ai.provider.AiProviderClient
import com.bori.core.security.AiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

sealed class InferenceState {
    object Idle : InferenceState()
    object Loading : InferenceState()
    object Ready : InferenceState()
    data class Error(val message: String) : InferenceState()
}

@Singleton
class LocalGemmaClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: ModelDownloadManager,
) : AiProviderClient {

    override val provider: AiProvider = AiProvider.LOCAL_GEMMA
    override fun supportsToolUse(): Boolean = true
    override fun maxContextTokens(): Int = if (engineOnGpu || !cpuOnly) gpuMaxTokens else MAX_TOKENS_CPU

    /**
     * GPU KV-cache size. On a 12 GB Galaxy Z Fold, E4B at 16k used about 4.9 GB and Android's
     * low-memory killer closed Bori Code mid-reply, so phones under 16 GB get the CPU's 8k.
     */
    private val gpuMaxTokens: Int by lazy {
        val mem = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        if (mem.totalMem >= 15L * 1024 * 1024 * 1024) MAX_TOKENS else MAX_TOKENS_CPU
    }

    @Volatile
    private var engine: Engine? = null
    /** Model file the loaded [engine] was built from; a different selection reloads it. */
    private var enginePath: String? = null
    /** Whether [engine] runs on the GPU; only a GPU engine gets the CPU retry. */
    @Volatile
    private var engineOnGpu = false

    private val runtimePrefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
    /**
     * Set once the GPU has failed to generate on this device (no OpenCL, as on emulators and
     * Pixels, or a GPU too weak for the model). Every later engine is built on the CPU.
     */
    @Volatile
    private var cpuOnly = runtimePrefs.getBoolean(KEY_CPU_ONLY, false)

    private val clientScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var preWarmJob: Job? = null

    private val _inferenceState = MutableStateFlow<InferenceState>(InferenceState.Idle)
    val inferenceState: StateFlow<InferenceState> = _inferenceState.asStateFlow()

    fun isReady(): Boolean = downloadManager.isModelReady()

    fun preWarm() {
        if (_inferenceState.value is InferenceState.Ready) return
        if (preWarmJob?.isActive == true) return
        preWarmJob = clientScope.launch {
            try { getEngine() } catch (_: Exception) { }
        }
    }

    fun invalidateCache() {
        preWarmJob?.cancel()
        synchronized(this) {
            engine?.close()
            engine = null
            enginePath = null
        }
        _inferenceState.value = InferenceState.Idle
    }

    private fun getEngine(): Engine {
        val modelPath = downloadManager.modelPath
        engine?.takeIf { enginePath == modelPath }?.let { return it }
        synchronized(this) {
            engine?.takeIf { enginePath == modelPath }?.let { return it }
            // The selected model changed since the engine was built
            engine?.close()
            engine = null
            _inferenceState.value = InferenceState.Loading
            val cacheDir = context.cacheDir.path
            var onGpu = !cpuOnly
            val inst = try {
                if (cpuOnly) error("GPU disabled on this device")
                val gpuEngine = Engine(
                    EngineConfig(modelPath = modelPath, backend = Backend.GPU(), maxNumTokens = gpuMaxTokens, cacheDir = cacheDir),
                )
                    .also { it.initialize() }
                try {
                    gpuEngine.createConversation(ConversationConfig()).close()
                } catch (probeEx: Exception) {
                    gpuEngine.close()
                    throw probeEx
                }
                gpuEngine
            } catch (gpuEx: Exception) {
                onGpu = false
                try {
                    Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), maxNumTokens = MAX_TOKENS_CPU, cacheDir = cacheDir))
                        .also { it.initialize() }
                } catch (e: Exception) {
                    _inferenceState.value = InferenceState.Error(e.message ?: "init failed")
                    if (!downloadManager.isModelReady()) {
                        downloadManager.markCorruptAndDelete(e.message ?: "init failed")
                    }
                    throw e
                }
            }
            engine = inst
            enginePath = modelPath
            engineOnGpu = onGpu
            _inferenceState.value = InferenceState.Ready
            return inst
        }
    }

    /**
     * Runs [turn] on the engine. Some GPUs load the model but fail once generation starts
     * (LiteRT-LM reports "Can not find OpenCL library"), so when a GPU turn ends with an
     * error or no output at all, the engine is rebuilt on the CPU for good and [turn] runs
     * once more. A turn that already produced output is never repeated.
     */
    private suspend fun runWithCpuFallback(hasOutput: () -> Boolean, turn: suspend (Engine) -> Unit) {
        val eng = withContext(Dispatchers.Default) { getEngine() }
        val onGpu = engineOnGpu
        val failure = try {
            turn(eng)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        if (!onGpu || hasOutput()) {
            failure?.let { throw it }
            return
        }
        Log.w(TAG, "GPU produced no output (${failure?.message ?: "empty reply"}); switching to CPU")
        switchToCpu()
        turn(withContext(Dispatchers.Default) { getEngine() })
    }

    private fun switchToCpu() {
        synchronized(this) {
            cpuOnly = true
            runtimePrefs.edit().putBoolean(KEY_CPU_ONLY, true).apply()
            engine?.close()
            engine = null
            enginePath = null
        }
    }

    /**
     * Runs one assistant turn on-device. The conversation is rebuilt from [request] each time
     * (history goes in as `initialMessages`), and tools use LiteRT-LM's native function
     * calling with automatic execution off: tool calls come back as ToolCall chunks so
     * Bori Code's agent loop runs and approves them like any other provider's.
     */
    override fun chat(request: ChatRequest): Flow<ChatChunk> = callbackFlow {
        val systemText = request.messages.firstOrNull { it.role == ChatMessage.Role.SYSTEM }?.textContent.orEmpty()
        val history = trimHistory(request.messages.filter { it.role != ChatMessage.Role.SYSTEM }, systemText.length)
        // Tool results only carry the call id; LiteRT-LM answers by function name
        val toolNames = history.flatMap { it.content.filterIsInstance<MessageContent.ToolUse>() }
            .associate { it.id to it.name }
        val converted = history.mapNotNull { toLiteRtMessage(it, toolNames) }
        val pending = converted.lastOrNull() ?: Message.user("")

        var emitted = false
        var finishReason = "stop"
        runWithCpuFallback(hasOutput = { emitted }) { eng ->
            val conversation = eng.createConversation(
                ConversationConfig(
                    systemInstruction = if (systemText.isNotEmpty()) Contents.of(systemText) else null,
                    initialMessages = converted.dropLast(1),
                    tools = request.tools.map { tool(DefinitionTool(it)) },
                    // temperature 0 asks for the same reply every time (the calculator tutorial)
                    samplerConfig = if (request.temperature <= 0f) GREEDY_SAMPLER else null,
                    automaticToolCalling = false,
                ),
            )

            var seenSoFar = ""
            val toolCalls = mutableListOf<ToolCall>()
            // close() the native conversation on normal completion AND on cancellation —
            // collect() throws CancellationException when the user stops generation, so the
            // close must live in a finally, not after the collect (awaitClose would never run).
            try {
                conversation.sendMessageAsync(pending).collect { message ->
                    val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (text.isNotEmpty()) {
                        val delta = extractDelta(text, seenSoFar)
                        seenSoFar = if (text.length > seenSoFar.length && text.startsWith(seenSoFar)) text else seenSoFar + text
                        if (delta.isNotEmpty()) {
                            emitted = true
                            trySend(ChatChunk.TextDelta(delta))
                        }
                    }
                    // Streamed messages may repeat earlier calls; keep each one once
                    message.toolCalls.forEach { call -> if (toolCalls.none { it.name == call.name && it.arguments == call.arguments }) toolCalls += call }
                }
                if (toolCalls.isEmpty() && !emitted) return@runWithCpuFallback
                emitted = true
                if (toolCalls.isNotEmpty()) finishReason = "tool_calls"
                toolCalls.forEach { call ->
                    val id = "local_${System.nanoTime()}"
                    trySend(ChatChunk.ToolCallStart(id = id, name = call.name))
                    trySend(ChatChunk.ToolCallDelta(JSONObject(call.arguments).toString()))
                    trySend(ChatChunk.ToolCallEnd(id = id))
                }
            } finally {
                conversation.close()
            }
        }
        trySend(ChatChunk.Done(finishReason))
        close()

        awaitClose { }
    }

    override fun complete(request: CompletionRequest): Flow<CompletionChunk> = callbackFlow {
        val prompt = buildString {
            if (request.suffix.isBlank()) {
                append("Complete the following code. Output ONLY the completion, no explanation:\n\n")
                append(request.prefix)
            } else {
                append("Complete the code between <prefix> and <suffix>. Output ONLY the inserted code.\n\n")
                append("<prefix>${request.prefix}</prefix><suffix>${request.suffix}</suffix>")
            }
        }
        var emitted = false
        runWithCpuFallback(hasOutput = { emitted }) { eng ->
            val conversation = eng.createConversation(ConversationConfig())
            var seenSoFar = ""
            try {
                conversation.sendMessageAsync(prompt).collect { message ->
                    val text = message.toString()
                    if (text.isNotEmpty()) {
                        val delta = extractDelta(text, seenSoFar)
                        seenSoFar = if (text.length > seenSoFar.length && text.startsWith(seenSoFar)) text else seenSoFar + text
                        emitted = true
                        trySend(CompletionChunk(delta))
                    }
                }
            } finally {
                conversation.close()
            }
        }
        close()
        awaitClose { }
    }

    private fun extractDelta(text: String, accumulated: String): String =
        if (text.length > accumulated.length && text.startsWith(accumulated))
            text.substring(accumulated.length)
        else
            text

    /** Converts one Bori Code message to LiteRT-LM's format, or null if it has nothing to send. */
    private fun toLiteRtMessage(msg: ChatMessage, toolNames: Map<String, String>): Message? {
        val toolResults = msg.content.filterIsInstance<MessageContent.ToolResult>()
        if (toolResults.isNotEmpty()) {
            return Message.tool(
                Contents.of(toolResults.map { Content.ToolResponse(toolNames[it.toolUseId] ?: it.toolUseId, it.content) }),
            )
        }
        val text = msg.textContent
        return when (msg.role) {
            ChatMessage.Role.USER -> text.takeIf { it.isNotBlank() }?.let { Message.user(it) }
            ChatMessage.Role.ASSISTANT -> {
                val calls = msg.content.filterIsInstance<MessageContent.ToolUse>().map { use ->
                    ToolCall(use.name, runCatching { jsonToMap(JSONObject(use.inputJson)) }.getOrDefault(emptyMap()))
                }
                if (text.isBlank() && calls.isEmpty()) null else Message.model(Contents.of(text), calls, emptyMap())
            }
            ChatMessage.Role.SYSTEM -> null
        }
    }

    /**
     * Drops the oldest messages until the conversation fits the context window, at about
     * four characters per token with room left for the reply. Never starts on a tool
     * result, whose call would then be missing.
     */
    private fun trimHistory(history: List<ChatMessage>, systemChars: Int): List<ChatMessage> {
        val budgetChars = (maxContextTokens() - REPLY_RESERVE_TOKENS) * 4 - systemChars
        var start = 0
        while (start < history.size - 1 &&
            (history.drop(start).sumOf { it.charCount() } > budgetChars ||
                history[start].content.any { it is MessageContent.ToolResult })
        ) start++
        return history.drop(start)
    }

    private fun ChatMessage.charCount(): Int = content.sumOf { c ->
        when (c) {
            is MessageContent.Text -> c.text.length
            is MessageContent.ToolUse -> c.inputJson.length + c.name.length
            is MessageContent.ToolResult -> c.content.length
            else -> 0
        }
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associateWith { key -> unwrap(obj.get(key)) }

    private fun unwrap(value: Any?): Any? = when (value) {
        is JSONObject -> jsonToMap(value)
        is org.json.JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    /** Exposes one of Bori Code's tool definitions to LiteRT-LM; never executed (manual tool calling). */
    private class DefinitionTool(private val definition: ToolDefinition) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = JSONObject()
            .put("name", definition.name)
            .put("description", definition.description)
            .put("parameters", JSONObject(definition.inputSchema))
            .toString()

        override fun execute(paramsJsonString: String): String =
            error("Bori Code runs tools itself; automatic tool calling is off")
    }

    private companion object {
        /** KV-cache size. Gemma 4 E2B/E4B support 32k; 16k keeps phone memory use reasonable. */
        const val MAX_TOKENS = 16_384
        /**
         * The CPU backend reserves its whole KV cache in RAM up front: at 16k, E2B took about
         * 4.4 GB and was killed on a 6 GB device. The CPU is also the path low-end phones
         * end up on, so it gets half the context.
         */
        const val MAX_TOKENS_CPU = 8_192
        const val REPLY_RESERVE_TOKENS = 2_048
        /** Always the most likely token, with a fixed seed: the same prompt gives the same reply. */
        val GREEDY_SAMPLER = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 42)
        const val TAG = "LocalGemma"
        const val RUNTIME_PREFS = "local_gemma_runtime"
        const val KEY_CPU_ONLY = "cpu_only"
    }
}
