package com.bori.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.db.ChatMessageDao
import com.bori.app.data.db.ChatMessageEntity
import com.bori.app.data.db.ChatSessionDao
import com.bori.app.data.db.ChatSessionEntity
import com.bori.app.data.prefs.UserPreferences
import com.bori.app.data.prefs.UserPreferencesRepository
import com.bori.app.data.project.ProjectStateHolder
import com.bori.app.service.ChatForegroundService
import com.bori.core.project.ProjectRepository
import com.bori.core.project.agent.AgentFiles
import com.bori.core.project.agent.AgentLock
import com.bori.core.project.model.Project
import com.bori.core.project.model.ProjectTemplate
import com.bori.app.tools.ToolDefinitions
import com.bori.app.tools.ToolExecutor
import com.bori.core.ai.context.ContextManager
import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatMessage
import com.bori.core.ai.model.ChatRequest
import com.bori.core.ai.model.MessageContent
import com.bori.core.ai.local.DownloadState
import com.bori.core.ai.local.InferenceState
import com.bori.core.ai.local.LocalGemmaClient
import com.bori.core.ai.local.ModelDownloadManager
import com.bori.core.ai.router.ModelRouter
import com.bori.core.editor.EditorController
import com.bori.core.editor.SymbolEntry
import com.bori.core.project.RepoMapper
import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.debounce
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Prompt used to compress older conversation turns into a compact, structured handoff note. */
private const val COMPACTION_PROMPT =
    "Summarize the conversation below into a concise handoff note that lets the assistant continue " +
    "seamlessly. Use these sections with short bullet points, omitting any that don't apply:\n" +
    "## Goal — what the user is ultimately trying to build or fix\n" +
    "## Key decisions — choices made and why\n" +
    "## Files created or modified — paths and what changed\n" +
    "## Current state — what works, what's in progress\n" +
    "## Open problems / next steps — what's left to do\n" +
    "Be specific and preserve identifiers, file paths, and versions. Do not invent details.\n\n" +
    "CONVERSATION:"

/** A tool call the AI has requested, waiting for the user to approve or deny. */
data class PendingToolCall(
    val id: String,
    val name: String,
    val inputJson: String,
    /** Asked by a coding CLI, which runs the tool itself; answered once, so no "Allow All". */
    val fromCli: Boolean = false,
)

/** Plan produced by write_plan in plan mode, awaiting user approval. */
data class PendingPlan(val summary: String, val plan: String)

/** One clarifying question in an [PendingQuestions] batch. */
data class QuestionItem(
    val question: String,
    val header: String = "",
    val options: List<String> = emptyList(),
    val allowMultiple: Boolean = false,
)

/** A batch of 1–4 clarifying questions the AI asked, waiting for the user's answers. */
data class PendingQuestions(val toolUseId: String, val items: List<QuestionItem>)

/** A suggestion from the AI to enter Plan Mode, awaiting user confirmation. */
data class PendingPlanModeRequest(val toolUseId: String, val reason: String)

/** Which special mode the user is being asked to enter. */
enum class ModeType { PLAN, ASK }

/** Queued mode-entry request — shown as a confirmation dialog before activating the mode. */
data class PendingModeEntry(val mode: ModeType, val pendingText: String = "")
data class PendingLockOverride(val lock: AgentLock, val text: String)

/** An item attached to the next message — image, document, or project context. */
sealed class Attachment {
    data class ImageAttachment(val fileName: String, val mediaType: String, val base64: String) : Attachment()
    data class DocumentAttachment(val fileName: String, val content: String) : Attachment()
    data class ProjectContextAttachment(val projectName: String, val treeText: String) : Attachment()
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
    private val router: ModelRouter,
    private val contextManager: ContextManager,
    private val editorController: EditorController,
    private val prefsRepository: UserPreferencesRepository,
    private val toolExecutor: ToolExecutor,
    private val projectStateHolder: ProjectStateHolder,
    private val projectRepository: ProjectRepository,
    private val keyVault: KeyVault,
    private val downloadManager: ModelDownloadManager,
    private val localGemmaClient: LocalGemmaClient,
    // App-tracked per-provider token usage. Recorded from ChatChunk.Usage in the agent loop and
    // surfaced in the Settings usage view.
    private val usageRepository: com.bori.app.data.usage.UsageRepository,
    private val tutorialSession: com.bori.app.ui.tutorial.TutorialSession,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val availableChatProviders: StateFlow<Map<AiProvider, List<String>>> =
        downloadManager.downloadState.combine(prefsRepository.preferences) { state, prefs ->
            val result = linkedMapOf<AiProvider, List<String>>()
            chatModelOptions.forEach { (provider, models) ->
                val available = if (provider.isLocal) {
                    prefs.experimentalFeaturesEnabled && state == DownloadState.Ready
                } else if (provider.isCli) {
                    router.isCliAvailable(provider)
                } else {
                    keyVault.hasKey(provider)
                }
                // On-device: only the model chosen and downloaded in Settings → Local AI can run.
                // Others: catalog models first (they carry notes), then extras from "Refresh models".
                if (available) result[provider] = if (provider.isLocal) listOf(downloadManager.selected.value.id)
                    else (models + prefs.cachedModels[provider].orEmpty()).distinct()
            }
            result
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val hasActiveProject: StateFlow<Boolean> = projectStateHolder.activeProject
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val activeProject: StateFlow<Project?> = projectStateHolder.activeProject
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val projects: StateFlow<List<Project>> = projectRepository.projects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) { projectRepository.syncFromFilesystem() }
    }

    fun openProject(project: Project) {
        projectStateHolder.setActiveProject(project)
    }

    fun createProject(
        name: String,
        packageName: String,
        minSdk: Int,
        template: ProjectTemplate,
        onCreated: (Project) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val project = runCatching {
                projectRepository.createProject(
                    Project(
                        name = name,
                        packageName = packageName,
                        minSdk = minSdk,
                        template = template,
                        rootPath = "",
                    )
                )
            }.getOrElse { return@launch }
            withContext(Dispatchers.Main) { onCreated(project) }
        }
    }

    private val _currentFileSymbols = MutableStateFlow<List<SymbolEntry>>(emptyList())

    fun updateSymbols(symbols: List<SymbolEntry>) {
        _currentFileSymbols.value = symbols
    }

    // Active session — created lazily on first message
    private var activeSessionId: Long = -1

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private fun setLoading(loading: Boolean) {
        _isLoading.value = loading
        if (!loading) releaseAgentLock()
        if (loading) {
            val intent = Intent(context, ChatForegroundService::class.java)
            context.startForegroundService(intent)
        } else {
            val intent = Intent(context, ChatForegroundService::class.java).apply {
                action = ChatForegroundService.ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    /** Non-null when the AI has called a tool and is waiting for user approval. */
    private val _pendingApproval = MutableStateFlow<PendingToolCall?>(null)
    val pendingApproval: StateFlow<PendingToolCall?> = _pendingApproval.asStateFlow()

    /** When true, tool calls are executed immediately without showing the approval card. Resets with the ViewModel. */
    private val _autoApprove = MutableStateFlow(false)
    val autoApprove: StateFlow<Boolean> = _autoApprove.asStateFlow()

    fun setAutoApprove(enabled: Boolean) { _autoApprove.value = enabled }

    /** Set when the agent loop stops due to hitting the auto-turn limit. The next sendMessage() will re-enable auto-approve automatically. */
    private val _stoppedForTurnLimit = MutableStateFlow(false)

    private val _isPlanMode = MutableStateFlow(false)
    val isPlanMode: StateFlow<Boolean> = _isPlanMode.asStateFlow()

    private val _pendingPlan = MutableStateFlow<PendingPlan?>(null)
    val pendingPlan: StateFlow<PendingPlan?> = _pendingPlan.asStateFlow()

    private val _planModeSessionPersistent = MutableStateFlow(false)

    /** When true, the next sendMessage() re-enters Plan Mode. Set after a per-build exit_plan_mode
     *  approval in a session-persistent plan session, so every new build re-plans and asks to exit again. */
    private val _rearmPlanModeNextSend = MutableStateFlow(false)

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private val _pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<Attachment>> = _pendingAttachments.asStateFlow()

    fun addAttachment(a: Attachment) {
        _pendingAttachments.value = _pendingAttachments.value + a
    }

    fun removeAttachment(index: Int) {
        val current = _pendingAttachments.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _pendingAttachments.value = current
        }
    }

    fun attachProjectContext() {
        val project = activeProject.value ?: run {
            viewModelScope.launch { _snackbarEvent.emit("No active project — open a project in the Editor tab first.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val projectRoot = File(project.rootPath)
            val tree = buildString {
                projectRoot.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file -> appendLine(file.relativeTo(projectRoot).path) }
            }
            addAttachment(Attachment.ProjectContextAttachment(project.name, tree))
        }
    }

    private val _pendingPlanModeRequest = MutableStateFlow<PendingPlanModeRequest?>(null)
    val pendingPlanModeRequest: StateFlow<PendingPlanModeRequest?> = _pendingPlanModeRequest.asStateFlow()

    /** Non-null when the AI has asked to leave Plan Mode and is awaiting the user's confirmation. */
    private val _pendingPlanExitRequest = MutableStateFlow<PendingPlanModeRequest?>(null)
    val pendingPlanExitRequest: StateFlow<PendingPlanModeRequest?> = _pendingPlanExitRequest.asStateFlow()

    private val _pendingModeEntry = MutableStateFlow<PendingModeEntry?>(null)
    val pendingModeEntry: StateFlow<PendingModeEntry?> = _pendingModeEntry.asStateFlow()

    private val _showCompactBanner = MutableStateFlow(false)
    val showCompactBanner: StateFlow<Boolean> = _showCompactBanner.asStateFlow()

    private val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    fun togglePlanMode() {
        if (!_isPlanMode.value) {
            requestModeEntry(ModeType.PLAN)
        } else {
            _isPlanMode.value = false
            _planModeSessionPersistent.value = false
            _rearmPlanModeNextSend.value = false
        }
    }

    private fun detectModeFromText(text: String): ModeType? {
        if (_isAskMode.value || _isPlanMode.value) return null
        val lower = text.lowercase()
        // Ask-phrases ("ask me questions" etc.) are intentionally NOT detected here anymore —
        // the AI handles them inline via the `ask_questions` tool, with no mode toggle. Only
        // plan-intent still offers a mode-entry dialog.
        val planSignals = listOf(
            "let's plan", "make a plan", "plan this out", "plan it out", "plan first",
            "design a ", "design the ", "before we code", "before coding", "blueprint"
        )
        return if (planSignals.any { lower.contains(it) }) ModeType.PLAN else null
    }

    fun acceptPlanModeSuggestion(forSession: Boolean) {
        val request = _pendingPlanModeRequest.value ?: return
        _pendingPlanModeRequest.value = null
        _isPlanMode.value = true
        _planModeSessionPersistent.value = forSession
        currentAgentJob = viewModelScope.launch {
            answerToolCall(
                request.toolUseId,
                if (forSession) "User approved Plan Mode for this session."
                else "User approved Plan Mode for this plan.",
            )
        }
    }

    fun declinePlanModeSuggestion() {
        val request = _pendingPlanModeRequest.value ?: return
        _pendingPlanModeRequest.value = null
        currentAgentJob = viewModelScope.launch {
            answerToolCall(request.toolUseId, "User declined Plan Mode. Continue in normal mode.")
        }
    }

    fun approvePlan() {
        val plan = _pendingPlan.value ?: return
        _pendingPlan.value = null
        _isAskMode.value = false
        if (!_planModeSessionPersistent.value) {
            _isPlanMode.value = false
        }
        sendMessage("Plan approved. Please implement it:\n\n${plan.plan}")
    }

    fun rejectPlan() {
        _pendingPlan.value = null
        // _isPlanMode stays true so user can refine and re-send
    }

    /** Another agent (Claude Code, Antigravity) holding this project's .bori/agent.lock; drives the banners. */
    private val _otherAgentLock = MutableStateFlow<AgentLock?>(null)
    val otherAgentLock: StateFlow<AgentLock?> = _otherAgentLock.asStateFlow()

    /** A message held back because another agent holds the lock; the user picks Run anyway or Cancel. */
    private val _pendingLockOverride = MutableStateFlow<PendingLockOverride?>(null)
    val pendingLockOverride: StateFlow<PendingLockOverride?> = _pendingLockOverride.asStateFlow()

    /** The project whose lock the built-in agent holds during a turn, so it's released on the same one. */
    private var heldLockRoot: File? = null
    private var currentTask = ""

    private fun activeRoot(): File? = activeProject.value?.rootPath?.takeIf { it.isNotBlank() }?.let { File(it) }

    /** Re-reads the lock; the chat and editor screens call this every few seconds while shown. */
    fun refreshAgentLock() {
        val root = activeRoot()
        viewModelScope.launch(Dispatchers.IO) {
            _otherAgentLock.value = root?.let { AgentFiles.otherAgentLock(it) }
        }
    }

    fun confirmLockOverride() {
        val pending = _pendingLockOverride.value ?: return
        _pendingLockOverride.value = null
        sendMessage(pending.text, skipModeDetection = true, ignoreAgentLock = true)
    }

    fun cancelLockOverride() {
        _pendingLockOverride.value = null
    }

    private suspend fun acquireAgentLock() {
        if (heldLockRoot != null) return
        val root = activeRoot() ?: return
        withContext(Dispatchers.IO) { AgentFiles.acquire(root, AgentFiles.BUILT_IN_AGENT, currentTask) }
        heldLockRoot = root
    }

    private fun releaseAgentLock() {
        val root = heldLockRoot ?: return
        heldLockRoot = null
        viewModelScope.launch(Dispatchers.IO) { AgentFiles.release(root, AgentFiles.BUILT_IN_AGENT) }
    }

    private fun requestModeEntry(mode: ModeType, pendingText: String = "") {
        _pendingModeEntry.value = PendingModeEntry(mode, pendingText)
    }

    fun confirmModeEntry(forSession: Boolean) {
        val entry = _pendingModeEntry.value ?: return
        _pendingModeEntry.value = null
        when (entry.mode) {
            ModeType.PLAN -> { _isPlanMode.value = true; _planModeSessionPersistent.value = forSession }
            ModeType.ASK  -> { _isAskMode.value = true }
        }
        if (entry.pendingText.isNotBlank()) {
            sendMessage(entry.pendingText, skipModeDetection = true)
        }
    }

    fun cancelModeEntry() {
        val entry = _pendingModeEntry.value ?: return
        _pendingModeEntry.value = null
        if (entry.pendingText.isNotBlank()) {
            sendMessage(entry.pendingText, skipModeDetection = true)
        }
    }

    fun clearConversation() {
        if (activeSessionId == -1L) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                messageDao.deleteMessagesForSession(activeSessionId)
                sessionDao.updateSession(
                    ChatSessionEntity(id = activeSessionId, title = "New Chat", updatedAt = System.currentTimeMillis()),
                )
            }
            agentHistory.clear()
            resetToolBatch()
            _messages.value = emptyList()
            _showCompactBanner.value = false
            _snackbarEvent.emit("Conversation cleared")
        }
    }

    fun dismissCompactBanner() { _showCompactBanner.value = false }

    /**
     * Rebuilds [agentHistory] from the persisted DB messages for the active session. Mirrors the
     * seeding rules used on first send: only plain user/assistant text rows, excluding tool-call
     * placeholders and blanks, so the API never receives orphaned tool blocks.
     */
    private suspend fun seedAgentHistoryFromDb() {
        if (activeSessionId == -1L) return
        val recentMessages = messageDao.getRecentMessages(activeSessionId, limit = 40)
            .reversed()
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { !it.content.startsWith("🔧 Calling") }
            .filter { it.content.isNotBlank() }
        agentHistory.addAll(recentMessages.map {
            ChatMessage(
                if (it.role == "user") ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT,
                it.content,
            )
        })
    }

    /**
     * Picks the index at which to split [history] for compaction. Targets ~60% but advances the
     * boundary forward until [history]`[cut]` is a USER text turn that is NOT a ToolResult and is
     * NOT preceded by a ToolUse — so the kept tail never starts with an orphaned tool result.
     */
    private fun computeCompactionCut(history: List<ChatMessage>): Int {
        val start = (history.size * 6 / 10).coerceAtLeast(2).coerceAtMost(history.size - 1)
        var cut = start
        while (cut < history.size) {
            val first = history[cut]
            val startsWithToolResult = first.content.any { it is MessageContent.ToolResult }
            val prevHasToolUse = cut > 0 && history[cut - 1].content.any { it is MessageContent.ToolUse }
            if (first.role == ChatMessage.Role.USER && !startsWithToolResult && !prevHasToolUse) break
            cut++
        }
        return cut.coerceIn(1, history.size - 1)
    }

    fun compactConversation() {
        if (_isCompacting.value) return
        if (activeSessionId == -1L) {
            viewModelScope.launch { _snackbarEvent.emit("No conversation to compact yet") }
            return
        }
        viewModelScope.launch {
            _isCompacting.value = true
            try {
                // /compact may run on a conversation that was loaded from the DB but never sent
                // a message this session, so agentHistory can be empty even when the chat is long.
                if (agentHistory.isEmpty()) seedAgentHistoryFromDb()
                if (agentHistory.size < 3) {
                    _snackbarEvent.emit("Not enough history to compact")
                    return@launch
                }

                // Tool-atomic cut: land the boundary on a clean USER text turn so a ToolUse is
                // never separated from its ToolResult (which would break the next API call).
                val cutIndex = computeCompactionCut(agentHistory)
                val toCompact = agentHistory.subList(0, cutIndex).toList()
                val remaining = agentHistory.subList(cutIndex, agentHistory.size).toList()

                val transcript = toCompact.joinToString("\n\n") { msg ->
                    "${msg.role.name.lowercase()}: ${msg.textContent}"
                }

                val prefs = prefsRepository.preferences.first()
                val summaryRequest = com.bori.core.ai.model.ChatRequest(
                    messages = listOf(
                        ChatMessage(ChatMessage.Role.USER, "$COMPACTION_PROMPT\n\n$transcript"),
                    ),
                    model = prefs.chatModel,
                    preferredProvider = prefs.chatProvider,
                    maxTokens = 1024,
                    tools = emptyList(),
                )
                val summaryText = StringBuilder()
                router.chat(summaryRequest).collect { chunk ->
                    if (chunk is com.bori.core.ai.model.ChatChunk.TextDelta) summaryText.append(chunk.text)
                }
                if (summaryText.isBlank()) {
                    _snackbarEvent.emit("Compaction failed: the model returned an empty summary")
                    return@launch
                }

                agentHistory.clear()
                agentHistory.add(ChatMessage(ChatMessage.Role.SYSTEM, "[Conversation summary] $summaryText"))
                agentHistory.addAll(remaining)

                withContext(Dispatchers.IO) {
                    messageDao.deleteMessagesForSession(activeSessionId)
                    messageDao.insertMessage(
                        ChatMessageEntity(
                            sessionId = activeSessionId,
                            role = "assistant",
                            content = "**[Conversation compacted]**\n\n$summaryText",
                            createdAt = System.currentTimeMillis() - 1,
                        ),
                    )
                    remaining.forEach { msg ->
                        val text = msg.textContent
                        if (text.isNotBlank()) {
                            messageDao.insertMessage(
                                ChatMessageEntity(
                                    sessionId = activeSessionId,
                                    role = if (msg.role == ChatMessage.Role.USER) "user" else "assistant",
                                    content = text,
                                ),
                            )
                        }
                    }
                }
                refreshMessages()
                _snackbarEvent.emit("Conversation compacted")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _snackbarEvent.emit("Compaction failed: ${e.message}")
            } finally {
                _isCompacting.value = false
                _showCompactBanner.value = false
            }
        }
    }

    /** Short display name of the active AI — "Claude", "DeepSeek", "gpt-4o-mini", etc. */
    val activeAiName: StateFlow<String> = prefsRepository.preferences
        .map { prefs ->
            when (prefs.chatProvider) {
                com.bori.core.security.AiProvider.ANTHROPIC   -> "Claude"
                com.bori.core.security.AiProvider.DEEPSEEK    -> "DeepSeek"
                com.bori.core.security.AiProvider.OPENAI      -> prefs.chatModel.ifEmpty { "OpenAI" }
                com.bori.core.security.AiProvider.OPENROUTER  ->
                    prefs.chatModel.substringAfterLast('/').ifEmpty { prefs.chatModel }.ifEmpty { "OpenRouter" }
                com.bori.core.security.AiProvider.GEMINI      -> "Gemini"
                com.bori.core.security.AiProvider.KIMI,
                com.bori.core.security.AiProvider.KIMI_CODE   -> "Kimi"
                com.bori.core.security.AiProvider.LOCAL_SERVER ->
                    prefs.chatModel.substringBefore(':').ifEmpty { "Local server" }
                com.bori.core.security.AiProvider.LOCAL_GEMMA -> downloadManager.selected.value.label
                com.bori.core.security.AiProvider.CLAUDE_CODE -> "Claude Code"
                com.bori.core.security.AiProvider.ANTIGRAVITY -> "Antigravity"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Claude")

    private val _isAskMode = MutableStateFlow(false)
    val isAskMode: StateFlow<Boolean> = _isAskMode.asStateFlow()

    /** Non-null when the AI's `ask_questions` tool fired and the inline question card is awaiting answers. */
    private val _pendingQuestions = MutableStateFlow<PendingQuestions?>(null)
    val pendingQuestions: StateFlow<PendingQuestions?> = _pendingQuestions.asStateFlow()

    fun toggleAskMode() {
        if (!_isAskMode.value) {
            requestModeEntry(ModeType.ASK)
        } else {
            _isAskMode.value = false
        }
    }

    /**
     * Submit the user's answers to a [PendingQuestions] batch. [answers] is parallel to
     * `pending.items`. Answers the one ask_questions ToolUse with a single ToolResult so the
     * provider API never sees an orphaned tool call.
     */
    fun submitAnswers(answers: List<String>) {
        val pending = _pendingQuestions.value ?: return
        _pendingQuestions.value = null
        val display = pending.items.mapIndexed { i, item ->
            "**${item.question}**\n${answers.getOrNull(i)?.trim().orEmpty()}"
        }.joinToString("\n\n")
        val toolResultContent = pending.items.mapIndexed { i, item ->
            "Q: ${item.question}\nA: ${answers.getOrNull(i)?.trim().orEmpty()}"
        }.joinToString("\n\n")
        currentAgentJob = viewModelScope.launch {
            messageDao.insertMessage(
                ChatMessageEntity(sessionId = activeSessionId, role = "user", content = display),
            )
            refreshMessages()
            answerToolCall(pending.toolUseId, toolResultContent)
        }
    }

    /** User approved the AI's request to leave Plan Mode — exit and resume with full tools. */
    fun approvePlanExit() {
        val request = _pendingPlanExitRequest.value ?: return
        _pendingPlanExitRequest.value = null
        // Exit for THIS build. If plan mode was kept for the session, re-arm it so the next
        // message re-plans and the AI issues a fresh exit_plan_mode call (a new card per build).
        _isPlanMode.value = false
        if (_planModeSessionPersistent.value) {
            _rearmPlanModeNextSend.value = true
        }
        currentAgentJob = viewModelScope.launch {
            answerToolCall(
                request.toolUseId,
                "User approved exiting Plan Mode. Proceed with the implementation now using the full tool set.",
            )
        }
    }

    /** User declined the AI's request to leave Plan Mode — stay in Plan Mode and resume. */
    fun declinePlanExit() {
        val request = _pendingPlanExitRequest.value ?: return
        _pendingPlanExitRequest.value = null
        currentAgentJob = viewModelScope.launch {
            answerToolCall(
                request.toolUseId,
                "User chose to stay in Plan Mode. Keep refining the plan; do not edit files.",
            )
        }
    }

    val userPrefs: StateFlow<UserPreferences> = prefsRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    val gemmaInitState: StateFlow<InferenceState> = localGemmaClient.inferenceState

    fun updateChatProvider(provider: AiProvider) {
        if (provider == AiProvider.LOCAL_GEMMA) localGemmaClient.preWarm()
        viewModelScope.launch {
            prefsRepository.updateChatProvider(provider)
            prefsRepository.updateCompletionProvider(provider)
            prefsRepository.updateCompletionModel(defaultCompletionModels[provider] ?: "")
        }
    }

    fun retryGemmaInit() {
        localGemmaClient.invalidateCache()
        localGemmaClient.preWarm()
    }

    fun deleteAndRedownload() {
        localGemmaClient.invalidateCache()
        downloadManager.deleteModel()
        downloadManager.startDownload()
    }

    fun updateChatModel(model: String) {
        viewModelScope.launch { prefsRepository.updateChatModel(model) }
    }

    /** Displayed in the ChatScreen top bar so the user always knows which AI is responding. */
    val activeProviderLabel: StateFlow<String> = prefsRepository.preferences
        .map { prefs ->
            val effective = router.activeProvider(prefs.chatProvider)
            formatProviderLabel(prefs, effective, downloadManager.selected.value.label)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // ── Slash commands ────────────────────────────────────────────────────────

    private val slashCommands = mapOf(
        "/explain"  to "Explain the following in detail — what it does, why it's structured this way, and any non-obvious aspects:\n\n",
        "/fix"      to "Identify and fix the bug or issue. Show the corrected code and explain what was wrong:\n\n",
        "/test"     to "Write JUnit4 unit tests for the following. Cover the main cases and edge cases:\n\n",
        "/refactor" to "Refactor the following to improve readability and performance while preserving existing behavior:\n\n",
        "/docs"     to "Write KDoc documentation for the following. Be accurate and concise:\n\n",
    )

    val slashCommandNames: List<String> = slashCommands.keys.toList() + "/plan" + "/ask" + "/tutorial" + "/clear" + "/compact"

    private val _tutorialRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tutorialRequested: SharedFlow<Unit> = _tutorialRequested.asSharedFlow()

    private fun expandSlashCommand(text: String): String {
        val command = text.substringBefore(' ').lowercase()
        val instruction = slashCommands[command] ?: return text
        val rest = text.substringAfter(' ', "").trim()
        return instruction + rest
    }

    // ── @file mentions ────────────────────────────────────────────────────────

    private val _atMentionQuery = MutableStateFlow("")

    val atMentionSuggestions: StateFlow<List<String>> = _atMentionQuery
        .debounce(150L)
        .map { query ->
            if (query.isEmpty()) return@map emptyList()
            val root = activeProject.value?.rootPath?.let { File(it) } ?: return@map emptyList()
            withContext(Dispatchers.IO) {
                root.walkTopDown()
                    .filter { it.isFile && it.name.lowercase().contains(query.lowercase()) }
                    .map { it.absolutePath }
                    .take(8)
                    .toList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setAtMentionQuery(query: String) { _atMentionQuery.value = query }

    private suspend fun resolveAtMentions(text: String): Pair<String, List<Pair<String, String>>> {
        val mentionPattern = Regex("@([\\w.]+)")
        val root = activeProject.value?.rootPath?.let { File(it) } ?: return text to emptyList()
        val extraFiles = mutableListOf<Pair<String, String>>()
        for (match in mentionPattern.findAll(text)) {
            val name = match.groupValues[1]
            val file = withContext(Dispatchers.IO) {
                root.walkTopDown()
                    .filter { it.isFile && it.name.equals(name, ignoreCase = true) }
                    .firstOrNull()
            } ?: continue
            val content = withContext(Dispatchers.IO) {
                runCatching { file.readText() }.getOrNull()
            } ?: continue
            extraFiles.add(file.absolutePath to content)
        }
        return text to extraFiles
    }

    // ── Pending chat input (from editor "Ask Claude") ─────────────────────────

    private val _pendingChatInput = MutableStateFlow<String?>(null)
    val pendingChatInput: StateFlow<String?> = _pendingChatInput.asStateFlow()

    fun setPendingInput(text: String) { _pendingChatInput.value = text }
    fun consumePendingInput() { _pendingChatInput.value = null }

    /** The tutorial's next request; ChatScreen puts it in the chat box (see [TutorialSession.Prefill]). */
    val tutorialPrefill = tutorialSession.chatPrefill
    fun consumeTutorialPrefill(id: Long) = tutorialSession.consumePrefill(id)

    // In-memory conversation history (rich content — not persisted to DB per turn).
    // The DB stores plain text for display; this list is the ground truth for the
    // agent loop that needs ToolUse / ToolResult content blocks.
    private val agentHistory = mutableListOf<ChatMessage>()

    /** The currently running agent coroutine, if any. Cancelled by [stopGeneration]. */
    private var currentAgentJob: kotlinx.coroutines.Job? = null

    /**
     * Every tool call from the current assistant turn, in the order they're handled. Results
     * are gathered in [toolResults] and sent back together in one user message once the whole
     * batch is answered, because providers reject a turn whose tool calls lack results.
     */
    private var toolBatch: List<MessageContent.ToolUse> = emptyList()
    private val toolQueue = ArrayDeque<MessageContent.ToolUse>()
    private val toolResults = mutableListOf<MessageContent.ToolResult>()
    /** Chat row of the assistant turn whose tool calls are being handled. */
    private var toolBatchAssistantId = -1L

    /** Cancels the active agent loop and resets loading state. */
    fun stopGeneration() {
        cliDecision?.complete(false)
        currentAgentJob?.cancel()
        currentAgentJob = null
        closeToolBatch("Cancelled by user.")
        setLoading(false)
        _pendingApproval.value = null
        _pendingPlan.value = null
        _pendingQuestions.value = null
        _pendingPlanModeRequest.value = null
        _pendingPlanExitRequest.value = null
        _pendingModeEntry.value = null
    }

    fun sendMessage(userText: String, skipModeDetection: Boolean = false, ignoreAgentLock: Boolean = false) {
        // Re-enter Plan Mode if a session-persistent plan was temporarily exited for the last build.
        if (_rearmPlanModeNextSend.value) {
            _rearmPlanModeNextSend.value = false
            _isPlanMode.value = true
        }
        if (_stoppedForTurnLimit.value) {
            // Resuming after the auto-turn limit was hit — re-enable auto-approve seamlessly
            _autoApprove.value = true
            _stoppedForTurnLimit.value = false
        } else {
            _autoApprove.value = false  // reset per-send: each new message requires fresh approval
        }
        _pendingQuestions.value = null  // clear any pending questions on new send
        _pendingPlanModeRequest.value = null
        _pendingPlanExitRequest.value = null
        _pendingApproval.value = null
        // Typing instead of answering a card skips the calls still waiting on it
        closeToolBatch("Not run: the user sent a new message instead.")
        // /plan command: show mode-entry dialog (with remaining text as pending)
        var text = userText
        if (userText.trimStart().startsWith("/plan", ignoreCase = true)) {
            val remaining = userText.trimStart().drop("/plan".length).trim()
            if (!_isPlanMode.value) {
                requestModeEntry(ModeType.PLAN, remaining)
                return
            }
            text = remaining
            if (text.isEmpty()) return
        }
        if (text.trimStart().startsWith("/ask", ignoreCase = true)) {
            val remaining = text.trimStart().drop("/ask".length).trim()
            if (!_isAskMode.value) {
                requestModeEntry(ModeType.ASK, remaining)
                return
            }
            text = remaining
            if (text.isEmpty()) return
        }
        if (text.trimStart().startsWith("/tutorial", ignoreCase = true)) {
            _tutorialRequested.tryEmit(Unit)
            return
        }
        if (text.trimStart().startsWith("/clear", ignoreCase = true)) {
            clearConversation()
            return
        }
        if (text.trimStart().startsWith("/compact", ignoreCase = true)) {
            compactConversation()
            return
        }
        if (tutorialSession.isAwaitingSend) {
            runTutorialRound(text)
            return
        }
        // Plan/Ask detection is Bori Code's own agent; a coding CLI has its own modes.
        if (!skipModeDetection && !userPrefs.value.chatProvider.isCli) {
            val detected = detectModeFromText(text)
            if (detected != null) {
                requestModeEntry(detected, text)
                return
            }
        }
        currentAgentJob = viewModelScope.launch {
            // Another agent is working on this project: ask before the built-in agent edits too.
            if (!ignoreAgentLock && !userPrefs.value.chatProvider.isCli) {
                val lock = activeRoot()?.let { withContext(Dispatchers.IO) { AgentFiles.otherAgentLock(it) } }
                if (lock != null) {
                    _otherAgentLock.value = lock
                    _pendingLockOverride.value = PendingLockOverride(lock, text)
                    return@launch
                }
            }
            currentTask = text.take(80)
            if (activeSessionId == -1L) {
                activeSessionId = sessionDao.insertSession(ChatSessionEntity(title = text.take(40)))
            }

            // Persist original text for display (slash command syntax kept intact)
            messageDao.insertMessage(
                ChatMessageEntity(sessionId = activeSessionId, role = "user", content = text),
            )
            refreshMessages()

            // Expand slash command and resolve @file mentions for the AI
            val expanded = if (text.startsWith("/")) expandSlashCommand(text) else text
            val (processedText, extraFiles) = resolveAtMentions(expanded)

            // Collect and clear any pending attachments, then build rich content blocks
            val attachments = _pendingAttachments.value
            _pendingAttachments.value = emptyList()
            val contentBlocks = mutableListOf<MessageContent>()
            val hasImageAttachment = attachments.any { it is Attachment.ImageAttachment }
            attachments.forEach { att ->
                when (att) {
                    is Attachment.ImageAttachment -> contentBlocks += MessageContent.Image(att.base64, att.mediaType)
                    is Attachment.DocumentAttachment -> contentBlocks += MessageContent.Text("**Attached: ${att.fileName}**\n\n${att.content}")
                    is Attachment.ProjectContextAttachment -> contentBlocks += MessageContent.Text("**Attached project context: ${att.projectName}**\n\n${att.treeText}")
                }
            }
            contentBlocks += MessageContent.Text(processedText)

            // Seed agent history from DB if this is the first message this session.
            // Only plain user/assistant text messages are restored — tool_result rows and
            // tool-call placeholder assistant messages ("🔧 Calling…") are excluded so that
            // the Anthropic API never receives orphaned ToolUse/ToolResult blocks whose
            // counterparts are missing from the serialised history.
            if (agentHistory.isEmpty()) {
                seedAgentHistoryFromDb()
                // Replace the last seeded entry (the user row just inserted above) with the
                // processed text and any rich attachment content blocks.
                if ((contentBlocks.size > 1 || processedText != text) && agentHistory.isNotEmpty()) {
                    agentHistory[agentHistory.lastIndex] = ChatMessage(ChatMessage.Role.USER, contentBlocks)
                }
            } else {
                agentHistory.add(ChatMessage(ChatMessage.Role.USER, contentBlocks))
            }

            val prefs = prefsRepository.preferences.first()

            // Warn when an image was attached but the active provider can't see it —
            // DeepSeek and the local Gemma model both silently drop image content.
            if (hasImageAttachment) {
                val visionless = when (prefs.chatProvider) {
                    com.bori.core.security.AiProvider.DEEPSEEK    -> "DeepSeek"
                    com.bori.core.security.AiProvider.LOCAL_GEMMA -> "the local model"
                    else -> null
                }
                if (visionless != null) {
                    _snackbarEvent.emit("$visionless can't see images — the attachment will be ignored.")
                }
            }

            if (prefs.chatProvider.isCli) {
                runCliTurn(prefs, ChatMessage(ChatMessage.Role.USER, contentBlocks))
                return@launch
            }
            val editorState = editorController.currentState.value
            runAgentLoop(prefs, editorState?.content, editorState?.filePath, extraFiles = extraFiles)
        }
    }

    /** The coding CLI's question being shown on the approval card; completed by Allow/Deny. */
    private var cliDecision: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /**
     * One turn with a coding CLI (Claude Code, Antigravity). The CLI is the agent and runs its
     * own tools, so there's no Bori Code tool loop: its text streams into assistant bubbles, each
     * tool it ran becomes a tool row, and its permission questions use the approval card.
     */
    private suspend fun runCliTurn(prefs: UserPreferences, message: ChatMessage) {
        setLoading(true)
        var assistantId = messageDao.insertMessage(
            ChatMessageEntity(sessionId = activeSessionId, role = "assistant", content = ""),
        )
        refreshMessages()
        val segment = StringBuilder()
        val fullText = StringBuilder()
        // Text after a tool row goes in a new bubble below it, so the chat reads in order.
        suspend fun appendText(text: String) {
            if (assistantId == -1L) {
                if (text.isBlank()) return
                assistantId = messageDao.insertMessage(
                    ChatMessageEntity(sessionId = activeSessionId, role = "assistant", content = ""),
                )
            }
            segment.append(text)
            fullText.append(text)
            messageDao.updateContent(assistantId, segment.toString().trimStart())
            refreshMessages()
        }
        suspend fun closeBubble() {
            if (assistantId != -1L && segment.isBlank()) messageDao.deleteMessage(assistantId)
            assistantId = -1L
            segment.clear()
        }
        val request = ChatRequest(
            messages = listOf(message),
            model = prefs.chatModel,
            preferredProvider = prefs.chatProvider,
            conversationId = activeSessionId.toString(),
            workingDir = activeProject.value?.rootPath,
        )
        try {
            router.chat(request).collect { chunk ->
                when (chunk) {
                    is ChatChunk.TextDelta -> appendText(chunk.text)
                    is ChatChunk.AgentActivity -> {
                        closeBubble()
                        val body = buildString {
                            append("[${chunk.title}] ${chunk.detail}")
                            if (chunk.output.isNotBlank()) append("\n\n${chunk.output}")
                        }
                        messageDao.insertMessage(
                            ChatMessageEntity(
                                sessionId = activeSessionId,
                                role = "tool_result",
                                content = if (chunk.isError) "⚠ $body" else body,
                            ),
                        )
                        refreshMessages()
                    }
                    is ChatChunk.PermissionRequest -> {
                        cliDecision = chunk.decision
                        _pendingApproval.value = PendingToolCall(chunk.id, chunk.tool, chunk.detail, fromCli = true)
                    }
                    is ChatChunk.Usage -> usageRepository.record(chunk.provider, chunk.promptTokens, chunk.completionTokens)
                    is ChatChunk.Error -> appendText(if (fullText.isEmpty()) "Error: ${chunk.message}" else "\n\n[Error: ${chunk.message}]")
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            appendText("\n\n[${prefs.chatProvider.displayName} stopped: ${e.message}]")
        } finally {
            cliDecision = null
            _pendingApproval.value = null
            closeBubble()
            refreshMessages()
            setLoading(false)
            // The CLI may have changed any file: refresh the explorer, open tabs and git status.
            projectStateHolder.notifyFilesChanged()
        }
        if (fullText.isNotEmpty()) {
            agentHistory.add(ChatMessage(ChatMessage.Role.ASSISTANT, fullText.toString()))
        }
        sessionDao.updateSession(ChatSessionEntity(id = activeSessionId, updatedAt = System.currentTimeMillis()))
    }

    /** Called by the UI when the user taps "Allow" on the approval card. */
    fun approveToolCall() {
        val pending = _pendingApproval.value ?: return
        _pendingApproval.value = null
        if (pending.fromCli) {
            cliDecision?.complete(true)
            return
        }
        currentAgentJob = viewModelScope.launch { executeAndContinue(pending) }
    }

    private suspend fun executeAndContinue(pending: PendingToolCall, autoTurnCount: Int = 0) {
        setLoading(true)
        val result = toolExecutor.execute(pending.name, pending.inputJson)

        val displayText = if (result.isError) "⚠ ${result.output}" else result.output
        messageDao.insertMessage(
            ChatMessageEntity(
                sessionId = activeSessionId,
                role = "tool_result",
                content = "[${pending.name}]\n$displayText",
            ),
        )
        refreshMessages()

        answerToolCall(pending.id, result.output, result.isError, autoTurnCount + 1)
    }

    /** Called by the UI when the user taps "Deny" on the approval card. */
    fun denyToolCall() {
        val pending = _pendingApproval.value ?: return
        _pendingApproval.value = null
        if (pending.fromCli) {
            cliDecision?.complete(false)
            return
        }
        currentAgentJob = viewModelScope.launch {
            messageDao.insertMessage(
                ChatMessageEntity(
                    sessionId = activeSessionId,
                    role = "tool_result",
                    content = "[${pending.name}] denied by user",
                ),
            )
            refreshMessages()

            answerToolCall(pending.id, "User denied this tool call.")
        }
    }

    // -------------------------------------------------------------------------
    // Agent loop
    // -------------------------------------------------------------------------

    /**
     * Builds a short symbol outline of the open file for the system prompt.
     */
    private fun buildSymbolSummary(symbols: List<SymbolEntry>): String? {
        if (symbols.isEmpty()) return null
        return symbols.joinToString("\n") { "  ${it.kind.label.padEnd(10)} ${it.name}  (line ${it.line})" }
    }

    /**
     * Parse the input of an `ask_questions` (or legacy single-question `ask_user`) tool call into
     * a list of [QuestionItem]. Tolerant of malformed JSON — returns an empty list on failure.
     */
    private fun parseQuestionItems(toolName: String, inputJson: String): List<QuestionItem> = runCatching {
        val args = org.json.JSONObject(inputJson)
        if (toolName == "ask_user") {
            val q = args.optString("question", "").trim()
            return@runCatching if (q.isEmpty()) emptyList()
                else listOf(QuestionItem(question = q, header = args.optString("context", "").trim()))
        }
        val arr = args.optJSONArray("questions") ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val q = obj.optString("question", "").trim()
            if (q.isEmpty()) return@mapNotNull null
            val optsArr = obj.optJSONArray("options")
            val options = if (optsArr == null) emptyList()
                else (0 until optsArr.length()).mapNotNull { j -> optsArr.optString(j, "").trim().ifEmpty { null } }
            QuestionItem(
                question = q,
                header = obj.optString("header", "").trim(),
                options = options,
                allowMultiple = obj.optBoolean("allow_multiple", false),
            )
        }.take(4)
    }.getOrDefault(emptyList())

    private suspend fun runAgentLoop(
        prefs: UserPreferences,
        currentFileContent: String?,
        currentFilePath: String?,
        autoTurnCount: Int = 0,
        extraFiles: List<Pair<String, String>> = emptyList(),
    ) {
        setLoading(true)
        acquireAgentLock()
        val repoMap = projectStateHolder.activeProject.value?.rootPath?.let { path ->
            withContext(Dispatchers.IO) { RepoMapper.generate(path) }.ifBlank { null }
        }
        val (sharedPlan, lastHandoff) = activeRoot()
            ?.let { root -> withContext(Dispatchers.IO) { AgentFiles.readPlan(root) to AgentFiles.readHandoff(root) } }
            ?: (null to null)
        val symbolSummary = buildSymbolSummary(_currentFileSymbols.value)
        val projectLanguage = projectStateHolder.activeProject.value?.template?.name
            ?.let { if (it.endsWith("_KOTLIN")) "Kotlin" else "Java" }

        val isPlan = _isPlanMode.value
        val isAsk  = _isAskMode.value
        val systemPrompt = contextManager.buildSystemPrompt(
            fileContent = currentFileContent,
            filePath = currentFilePath,
            repoMap = repoMap,
            symbolSummary = symbolSummary,
            mentionedFiles = extraFiles,
            planMode = isPlan,
            askMode  = isAsk,
            provider = prefs.chatProvider,
            model    = prefs.chatModel,
            projectLanguage = projectLanguage,
            sharedPlan = sharedPlan,
            lastHandoff = lastHandoff,
        )
        val request = contextManager.buildChatRequest(
            userMessage = "",
            history = agentHistory,
            model = prefs.chatModel,
        ).copy(
            preferredProvider = prefs.chatProvider,
            model = prefs.chatModel,
            tools = when {
                activeProject.value == null -> emptyList()
                isAsk  -> ToolDefinitions.askModeTools
                isPlan -> ToolDefinitions.planModeTools
                else   -> ToolDefinitions.all
            },
            messages = listOf(ChatMessage(ChatMessage.Role.SYSTEM, systemPrompt)) + agentHistory,
        )

        val assistantId = messageDao.insertMessage(
            ChatMessageEntity(sessionId = activeSessionId, role = "assistant", content = ""),
        )
        refreshMessages()

        val textAccumulated = StringBuilder()
        val reasoningAccumulated = StringBuilder()
        // Accumulate the current tool call being streamed
        var currentToolId: String? = null
        var currentToolName: String? = null
        var currentToolSignature: String? = null
        val toolInputAccumulator = StringBuilder()

        // Content blocks for the assistant message (may include text + tool_use)
        val assistantContent = mutableListOf<MessageContent>()

        try {
            router.chat(request).collect { chunk ->
                when (chunk) {
                    is ChatChunk.ReasoningDelta -> {
                        reasoningAccumulated.append(chunk.text)
                        // Not shown in chat — accumulated silently for API echo-back
                    }
                    is ChatChunk.TextDelta -> {
                        textAccumulated.append(chunk.text)
                        messageDao.updateContent(assistantId, textAccumulated.toString())
                        refreshMessages()
                    }
                    is ChatChunk.ToolCallStart -> {
                        currentToolId = chunk.id
                        currentToolName = chunk.name
                        currentToolSignature = chunk.signature
                        toolInputAccumulator.clear()
                    }
                    is ChatChunk.ToolCallDelta -> {
                        toolInputAccumulator.append(chunk.inputJsonDelta)
                    }
                    is ChatChunk.ToolCallEnd -> {
                        val id = currentToolId ?: return@collect
                        val name = currentToolName ?: return@collect
                        val inputJson = toolInputAccumulator.toString().ifEmpty { "{}" }

                        assistantContent.add(MessageContent.ToolUse(id, name, inputJson, currentToolSignature))

                        // Show a placeholder in the chat
                        val displayLabel = "🔧 Calling $name…"
                        messageDao.updateContent(assistantId, displayLabel)
                        refreshMessages()

                        currentToolId = null
                        currentToolName = null
                    }
                    is ChatChunk.Usage -> {
                        // Accrue this turn's token counts for the Settings usage view. New ChatChunk
                        // subtype, so this branch is required for the when to stay exhaustive.
                        usageRepository.record(chunk.provider, chunk.promptTokens, chunk.completionTokens)
                    }
                    is ChatChunk.Done -> { /* handled after collect */ }
                    // Only coding CLIs send these, and their turns run in runCliTurn.
                    is ChatChunk.AgentActivity, is ChatChunk.PermissionRequest -> Unit
                    is ChatChunk.Error -> {
                        if (textAccumulated.isNotEmpty()) {
                            textAccumulated.append("\n\n[Error: ${chunk.message}]")
                        } else {
                            textAccumulated.append("Error: ${chunk.message}")
                        }
                        messageDao.updateContent(assistantId, textAccumulated.toString())
                        refreshMessages()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (textAccumulated.isNotEmpty()) {
                textAccumulated.append("\n\n[Connection interrupted: ${e.message}]")
            } else {
                textAccumulated.append("Error: ${e.message}")
            }
            messageDao.updateContent(assistantId, textAccumulated.toString())
            refreshMessages()
            setLoading(false)
            return
        }

        // Build the assistant ChatMessage to append to history
        if (textAccumulated.isNotEmpty()) {
            assistantContent.add(0, MessageContent.Text(textAccumulated.toString()))
        }
        // Prepend reasoning block so DeepSeek's thinking-mode echo-back requirement is satisfied
        if (reasoningAccumulated.isNotEmpty()) {
            assistantContent.add(0, MessageContent.Reasoning(reasoningAccumulated.toString()))
        }
        if (assistantContent.isNotEmpty()) {
            agentHistory.add(ChatMessage(ChatMessage.Role.ASSISTANT, assistantContent))
        }

        val toolUses = assistantContent.filterIsInstance<MessageContent.ToolUse>()
        if (toolUses.isEmpty()) {
        // Pure text final response — turn is complete
        setLoading(false)
        sessionDao.updateSession(
            ChatSessionEntity(id = activeSessionId, updatedAt = System.currentTimeMillis()),
        )
        val totalChars = agentHistory.sumOf { it.textContent.length }
        val budgetChars = 16_000 * 4
        when {
            totalChars >= budgetChars -> compactConversation()  // at limit — auto-compact
            totalChars >= budgetChars * 75 / 100 -> _showCompactBanner.value = true  // near limit — show banner
        }
        } else {
            // Handle every tool call of this turn in order. Calls that pause the turn or
            // end it (questions, plan/compact) go last so ordinary calls still get results.
            resetToolBatch()
            toolBatch = toolUses.sortedBy { it.name in TURN_ENDING_TOOLS }
            toolQueue.addAll(toolBatch)
            toolBatchAssistantId = assistantId
            processNextToolCall(autoTurnCount)
        }
    }

    /** Records the result for [toolUseId], then handles the next queued call or returns to the model. */
    private suspend fun answerToolCall(
        toolUseId: String,
        content: String,
        isError: Boolean = false,
        autoTurnCount: Int = 0,
    ) {
        toolResults += MessageContent.ToolResult(toolUseId = toolUseId, content = content, isError = isError)
        processNextToolCall(autoTurnCount)
    }

    /** Handles the next queued tool call; once all are answered, sends the results back to the model. */
    private suspend fun processNextToolCall(autoTurnCount: Int) {
        val next = toolQueue.removeFirstOrNull()
        if (next != null) {
            handleToolUse(next, autoTurnCount)
            return
        }
        if (!flushToolResults()) return
        val prefs = prefsRepository.preferences.first()
        val editorState = editorController.currentState.value
        runAgentLoop(prefs, editorState?.content, editorState?.filePath, autoTurnCount)
    }

    /** Appends the batch's results to [agentHistory] as one user message. False if there was no batch. */
    private fun flushToolResults(): Boolean {
        if (toolBatch.isEmpty()) return false
        if (toolResults.isNotEmpty()) {
            agentHistory.add(ChatMessage(ChatMessage.Role.USER, toolResults.toList()))
        }
        resetToolBatch()
        return true
    }

    /** Answers every unanswered call in the batch with [reason] and closes it without calling the model. */
    private fun closeToolBatch(reason: String) {
        if (toolBatch.isEmpty()) return
        val answered = toolResults.mapTo(HashSet()) { it.toolUseId }
        toolBatch.filter { it.id !in answered }.forEach {
            toolResults += MessageContent.ToolResult(toolUseId = it.id, content = reason, isError = false)
        }
        flushToolResults()
    }

    private fun resetToolBatch() {
        toolBatch = emptyList()
        toolQueue.clear()
        toolResults.clear()
        toolBatchAssistantId = -1L
    }

    /** Runs, or surfaces for approval, one tool call from the current batch. */
    private suspend fun handleToolUse(toolUse: MessageContent.ToolUse, autoTurnCount: Int) {
        val assistantId = toolBatchAssistantId
        when (toolUse.name) {
            "suggest_plan_mode" -> {
                val args   = org.json.JSONObject(toolUse.inputJson)
                val reason = args.optString("reason", "").trim()
                messageDao.updateContent(assistantId, "📋 Plan Mode suggested")
                refreshMessages()
                // accept/declinePlanModeSuggestion answers this call and resumes the batch
                _pendingPlanModeRequest.value = PendingPlanModeRequest(toolUseId = toolUse.id, reason = reason)
                setLoading(false)
            }
            "compact_conversation" -> {
                // Close the tool pairs BEFORE compacting so they stay atomic (they will be summarized away).
                toolResults += MessageContent.ToolResult(toolUseId = toolUse.id, content = "Conversation compacted.", isError = false)
                closeToolBatch("Not run: the conversation was compacted first. Call it again if still needed.")
                messageDao.updateContent(assistantId, "🗜 Compacting conversation…")
                refreshMessages()
                setLoading(false)
                compactConversation()
            }
            "ask_questions", "ask_user" -> {
                val items = parseQuestionItems(toolUse.name, toolUse.inputJson)
                if (items.isEmpty()) {
                    // Malformed call — return an error tool result so the loop can recover.
                    answerToolCall(
                        toolUse.id,
                        "No questions were provided. Provide a non-empty 'questions' array.",
                        isError = true,
                        autoTurnCount = autoTurnCount,
                    )
                    return
                }
                val heading = if (items.size == 1) "❓ ${items.first().question}"
                              else "❓ ${items.size} questions"
                messageDao.updateContent(assistantId, heading)
                refreshMessages()
                // submitAnswers answers this call and resumes the batch
                _pendingQuestions.value = PendingQuestions(toolUseId = toolUse.id, items = items)
                setLoading(false)
            }
            "exit_plan_mode" -> {
                val args   = org.json.JSONObject(toolUse.inputJson)
                val reason = args.optString("reason", "").trim()
                messageDao.updateContent(assistantId, "📋 Ready to exit Plan Mode")
                refreshMessages()
                // approve/declinePlanExit answers this call and resumes the batch
                _pendingPlanExitRequest.value = PendingPlanModeRequest(toolUseId = toolUse.id, reason = reason)
                setLoading(false)
            }
            "write_plan" -> {
                // Execute inline — no approval card needed for a plan submission. The turn ends
                // here with the plan card, so close the batch so the API never sees an orphaned ToolUse.
                val result = toolExecutor.execute(toolUse.name, toolUse.inputJson)
                toolResults += MessageContent.ToolResult(toolUseId = toolUse.id, content = result.output, isError = result.isError)
                closeToolBatch("Not run: the plan was submitted for review first.")
                if (!result.isError && result.output.startsWith("PLAN_READY\n")) {
                    val body     = result.output.removePrefix("PLAN_READY\n")
                    val delimIdx = body.indexOf("\n---\n")
                    val summary  = if (delimIdx >= 0) body.substring(0, delimIdx) else ""
                    val plan     = if (delimIdx >= 0) body.substring(delimIdx + 5) else body
                    _pendingPlan.value = PendingPlan(summary = summary, plan = plan)
                } else {
                    messageDao.insertMessage(
                        ChatMessageEntity(sessionId = activeSessionId, role = "assistant",
                            content = "Plan generation failed: ${result.output}"),
                    )
                    refreshMessages()
                }
                setLoading(false)
            }
            else -> {
                val pending = PendingToolCall(id = toolUse.id, name = toolUse.name, inputJson = toolUse.inputJson)
                if (_autoApprove.value) {
                    val prefs = prefsRepository.preferences.first()
                    if (autoTurnCount >= prefs.aiWorkTurns) {
                        closeToolBatch("Not run: reached the automatic tool-call limit.")
                        val stopMsg = "Reached the limit of ${prefs.aiWorkTurns} automatic tool calls. " +
                            "Send any message to resume with auto-approve."
                        messageDao.insertMessage(
                            ChatMessageEntity(sessionId = activeSessionId, role = "assistant", content = stopMsg),
                        )
                        refreshMessages()
                        _stoppedForTurnLimit.value = true
                        setLoading(false)
                        return
                    }
                    executeAndContinue(pending, autoTurnCount)
                } else {
                    // If we're waiting for approval, we can stop the foreground service
                    // to avoid being annoying, as the network connection is closed.
                    setLoading(false)
                    _pendingApproval.value = pending
                }
            }
        }
    }

    /**
     * A tutorial round: the user's request goes to on-device Gemma without tools (their 26 KB of
     * definitions wouldn't fit Gemma's context) and Gemma's code streams in as the reply.
     */
    private fun runTutorialRound(text: String) {
        currentAgentJob = viewModelScope.launch {
            if (activeSessionId == -1L) {
                activeSessionId = sessionDao.insertSession(ChatSessionEntity(title = text.take(40)))
            }
            messageDao.insertMessage(ChatMessageEntity(sessionId = activeSessionId, role = "user", content = text))
            val assistantId = messageDao.insertMessage(
                ChatMessageEntity(sessionId = activeSessionId, role = "assistant", content = "Starting Gemma on this phone…"),
            )
            refreshMessages()
            setLoading(true)
            try {
                tutorialSession.runRound(text) { reply ->
                    messageDao.updateContent(assistantId, reply)
                    refreshMessages()
                }
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun refreshMessages() {
        _messages.value = messageDao.getRecentMessages(activeSessionId, limit = 100).reversed()
    }

    companion object {
        /** Tools that pause the turn for the user or end it; handled after the rest of a batch. */
        private val TURN_ENDING_TOOLS = setOf(
            "suggest_plan_mode", "compact_conversation", "ask_questions", "ask_user", "exit_plan_mode", "write_plan",
        )

        // All model IDs/defaults come from the shared catalog (single source of truth).
        val defaultChatModels: Map<AiProvider, String> = com.bori.core.ai.model.ModelCatalog.defaultChatModels
        val defaultCompletionModels: Map<AiProvider, String> = com.bori.core.ai.model.ModelCatalog.defaultCompletionModels
        val chatModelOptions: Map<AiProvider, List<String>> =
            AiProvider.entries.associateWith { com.bori.core.ai.model.ModelCatalog.chatModelIds(it) }

        fun formatProviderLabel(prefs: UserPreferences, effectiveProvider: AiProvider, localModelLabel: String): String {
            return if (effectiveProvider == AiProvider.LOCAL_GEMMA) {
                "Local · $localModelLabel"
            } else {
                val model = prefs.chatModel.ifEmpty {
                    defaultChatModels[prefs.chatProvider] ?: ""
                }
                "${prefs.chatProvider.displayName} · $model"
            }
        }
    }
}
