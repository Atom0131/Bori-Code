package com.bori.app.ui.chat

import com.bori.theme.util.BoriSheet
import com.bori.theme.util.BoriAlertDialog
import com.bori.theme.util.BoriDropdownMenu
import com.bori.theme.components.crystalListItemColors
import com.bori.theme.components.crystalAssistChipColors
import com.bori.theme.components.crystalFilterChipColors
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import com.bori.app.ui.common.ProjectPickerSheet
import com.bori.app.ui.common.ProjectChip
import com.bori.app.ui.common.BoriTopBar
import com.bori.app.ui.theme.BoriTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.bori.app.ui.common.SectionHeader
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import com.bori.app.ui.workspace.LocalPaneVisible
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bori.app.data.db.ChatMessageEntity
import com.bori.app.ui.projects.NewProjectDialog
import com.bori.core.project.model.Project
import com.bori.core.ai.local.InferenceState
import com.bori.core.security.AiProvider
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch

// ---- Palette ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    showTutorialCard: Boolean = false,
    onStartTutorial: () -> Unit = {},
    onDismissTutorial: () -> Unit = {},
    /** Shows the Terminal (unfolded: beside the chat), for a terminal agent's approval. */
    onOpenTerminal: () -> Unit = {},
    /** Gives Chat the whole screen (unfolded); asked for when an agent is started from Chat. */
    onChatFullScreen: () -> Unit = {},
) {
    val terminalTargets by viewModel.terminalTargets.collectAsState()
    val startableAgents by viewModel.startableAgents.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshStartableAgents() }
    LaunchedEffect(Unit) { viewModel.chatFullScreen.collect { onChatFullScreen() } }
    val terminalTarget by viewModel.terminalTarget.collectAsState()
    val terminalMessages by viewModel.terminalMessages.collectAsState()
    val terminalScreen by viewModel.terminalScreen.collectAsState()
    val terminalLive by viewModel.terminalLive.collectAsState()
    val agentCommands by viewModel.agentCommands.collectAsState()
    val boriMessages by viewModel.messages.collectAsState()
    // Sending to a CLI in a terminal tab: its conversation instead of Bori's.
    val messages = if (terminalTarget != null) terminalMessages else boriMessages
    val isLoading by viewModel.isLoading.collectAsState()
    val providerLabel by viewModel.activeProviderLabel.collectAsState()
    val pendingApproval by viewModel.pendingApproval.collectAsState()
    val autoApprove by viewModel.autoApprove.collectAsState()
    val hasActiveProject by viewModel.hasActiveProject.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val pendingChatInput by viewModel.pendingChatInput.collectAsState()
    val atMentionSuggestions by viewModel.atMentionSuggestions.collectAsState()
    val isPlanMode by viewModel.isPlanMode.collectAsState()
    val pendingPlan by viewModel.pendingPlan.collectAsState()
    val isAskMode by viewModel.isAskMode.collectAsState()
    val pendingQuestions by viewModel.pendingQuestions.collectAsState()
    val pendingPlanModeRequest by viewModel.pendingPlanModeRequest.collectAsState()
    val pendingPlanExitRequest by viewModel.pendingPlanExitRequest.collectAsState()
    val aiName by viewModel.activeAiName.collectAsState()
    val pendingAttachments by viewModel.pendingAttachments.collectAsState()
    val pendingModeEntry by viewModel.pendingModeEntry.collectAsState()
    val otherAgentLock by viewModel.otherAgentLock.collectAsState()
    val pendingLockOverride by viewModel.pendingLockOverride.collectAsState()
    val showCompactBanner by viewModel.showCompactBanner.collectAsState()
    val isCompacting by viewModel.isCompacting.collectAsState()
    // Saveable, so a half-typed message survives Android recreating the screen.
    var input by rememberSaveable { mutableStateOf("") }
    // Hidden (another tab, or the other side after folding): give the keyboard back.
    val visible = LocalPaneVisible.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(visible) { if (!visible) focusManager.clearFocus() }
    val listState = rememberLazyListState()
    val userPrefs by viewModel.userPrefs.collectAsState()
    val gemmaInitState by viewModel.gemmaInitState.collectAsState()
    var showProjectPicker by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showCommandsSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val commandsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val availableChatProviders by viewModel.availableChatProviders.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(pendingChatInput) {
        pendingChatInput?.let { pending ->
            input = pending
            viewModel.consumePendingInput()
        }
    }
    val tutorialPrefill by viewModel.tutorialPrefill.collectAsState()
    LaunchedEffect(tutorialPrefill?.id) {
        tutorialPrefill?.let { prefill ->
            input = prefill.text
            viewModel.consumeTutorialPrefill(prefill.id)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    // Follow a reply as it streams in (long code blocks grow past the bottom of the screen)
    val lastLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(lastLength) {
        if (messages.isNotEmpty() && isLoading) {
            listState.scrollToItem(messages.size - 1)
            listState.scrollBy(100_000f)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }
    LaunchedEffect(Unit) {
        viewModel.tutorialRequested.collect { onStartTutorial() }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val mimeType = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) ?: "image/jpeg" }
            val b64 = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
            }
            val name = uri.lastPathSegment ?: "image"
            viewModel.addAttachment(Attachment.ImageAttachment(name, mimeType, b64))
        }
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val mimeType = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) ?: "" }
            if (mimeType == "application/pdf") {
                snackbarHostState.showSnackbar("PDF files are not supported. Convert to .txt, .md, or a code file.")
                return@launch
            }
            val name = uri.lastPathSegment ?: "document"
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8).take(50_000) }
            }
            viewModel.addAttachment(Attachment.DocumentAttachment(name, text))
        }
    }

    // The "/" list: the commands of the agent Chat sends to, while the first word is being typed.
    val slashWord = input.takeIf { it.startsWith("/") && ' ' !in it && '\n' !in it }
    val slashOpen = slashWord != null
    LaunchedEffect(slashOpen, terminalTarget?.tabId) { if (slashOpen) viewModel.refreshAgentCommands() }
    val slashSuggestions = remember(slashWord, agentCommands) {
        if (slashWord == null) emptyList()
        else com.bori.app.terminal.AgentCommands.filter(agentCommands, slashWord)
            .filterNot { it.name.equals(slashWord, ignoreCase = true) }
    }

    val bgColor = BoriTheme.colors.chatBackground
    // User-selectable bubble color (ARGB long persisted in prefs). Threaded into the user
    // message bubbles and the send button so the chat accent follows the user's choice.
    val bubbleColor = MaterialTheme.colorScheme.primary

    if (showCommandsSheet) {
        val slashCommandDescriptions = remember {
            listOf(
                "/plan" to "Enter Plan Mode — AI writes a plan before editing",
                "/ask" to "Enter Ask Mode — AI clarifies requirements before planning",
                "/clear" to "Clear all messages in this conversation",
                "/compact" to "Summarize old messages to free up context space",
                "/tutorial" to "Restart the guided tutorial from the beginning",
                "/explain" to "Explain the selected code in detail",
                "/fix" to "Identify and fix bugs in the code",
                "/test" to "Write JUnit4 unit tests",
                "/refactor" to "Refactor for readability and performance",
                "/docs" to "Write KDoc documentation",
            )
        }
        BoriSheet(
            onDismiss = { showCommandsSheet = false },
            sheetState = commandsSheetState,
        ) {
            Text(
                text = "Commands",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            HorizontalDivider()
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(slashCommandDescriptions.size) { index ->
                    val (cmd, desc) = slashCommandDescriptions[index]
                    ListItem(
                        colors = crystalListItemColors(),
                        headlineContent = {
                            Text(cmd, style = MaterialTheme.typography.bodyMedium)
                        },
                        supportingContent = {
                            Text(desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        leadingContent = {
                            Icon(Icons.Default.Code, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        },
                        modifier = Modifier.clickable {
                            input = "$cmd "
                            scope.launch { commandsSheetState.hide() }
                                .invokeOnCompletion { showCommandsSheet = false }
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, packageName, minSdk, template ->
                showNewProjectDialog = false
                showProjectPicker = false
                viewModel.createProject(name, packageName, minSdk, template) { project ->
                    viewModel.openProject(project)
                }
            },
        )
    }

    if (showProjectPicker) {
        ProjectPickerSheet(
            projects = projects,
            activeId = activeProject?.id,
            onOpen = { viewModel.openProject(it) },
            onNewProject = { showNewProjectDialog = true },
            onDismiss = { showProjectPicker = false },
        )
    }

    if (showModelMenu) {
        BoriSheet(
            onDismiss = { showModelMenu = false },
            sheetState = modelSheetState,
        ) {
            Text(
                "Choose agent",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            if (availableChatProviders.isEmpty()) {
                Text(
                    "No providers configured. Add an API key in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                // One provider open at a time, starting with the one in use
                var openProvider by rememberSaveable { mutableStateOf<AiProvider?>(userPrefs.chatProvider) }
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    // Grouped by where the agent runs: coding CLIs, cloud APIs, this phone or your PC.
                    var lastGroup: String? = null
                    availableChatProviders.entries.sortedBy { agentGroupOrder(it.key) }.forEach { (provider, models) ->
                        val group = agentGroup(provider)
                        if (group != lastGroup) {
                            lastGroup = group
                            item(key = "group_$group") {
                                Text(
                                    group,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                        }
                        val isOpen = openProvider == provider
                        item(key = provider.name + "_header") {
                            val current = if (userPrefs.chatProvider == provider) {
                                userPrefs.chatModel.ifEmpty { ChatViewModel.defaultChatModels[provider].orEmpty() }
                            } else null
                            SectionHeader(
                                title = provider.displayName,
                                summary = current?.let { "In use · ${it.substringAfterLast('/')}" }
                                    ?: "${models.size} model${if (models.size == 1) "" else "s"}",
                                icon = null,
                                trailing = if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                onClick = { openProvider = if (isOpen) null else provider },
                                horizontalPadding = 16.dp,
                            )
                        }
                        if (isOpen) items(models, key = { provider.name + "_" + it }) { model ->
                            val isSelected = userPrefs.chatProvider == provider &&
                                (userPrefs.chatModel == model ||
                                    (userPrefs.chatModel.isEmpty() &&
                                        model == ChatViewModel.defaultChatModels[provider]))
                            // Free-tier note from the catalog (e.g. Gemini 3.x "needs billing").
                            val meta = com.bori.core.ai.model.ModelCatalog.model(provider, model)
                            ListItem(
                                colors = crystalListItemColors(),
                                headlineContent = {
                                    Text(
                                        model.substringAfterLast('/').ifEmpty { model },
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                supportingContent = meta?.note?.let { note ->
                                    {
                                        Text(
                                            text = if (meta.freeTierQuota) note else "⚠ $note",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (meta.freeTierQuota)
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                leadingContent = if (isSelected) ({
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }) else null,
                                modifier = Modifier.clickable {
                                    viewModel.updateChatProvider(provider)
                                    viewModel.updateChatModel(model)
                                    scope.launch { modelSheetState.hide() }
                                        .invokeOnCompletion { showModelMenu = false }
                                },
                            )
                        }
                        item(key = provider.name + "_divider") { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
                    }
                    item(key = "more_agents") {
                        Text(
                            if (com.bori.app.flavor.HAS_LINUX) {
                                "More agents: install Claude Code or Antigravity in More → Linux environment, " +
                                    "add API keys in More → Settings."
                            } else "More models: add API keys in More → Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        BoriAlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear conversation?") },
            text = { Text("All messages in this conversation will be deleted. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        viewModel.clearConversation()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) { Text("Cancel") }
            },
        )
    }

    com.bori.app.ui.common.PollAgentLock(activeProject?.rootPath) { viewModel.refreshAgentLock() }
    pendingLockOverride?.let { pending ->
        com.bori.app.ui.common.AgentLockDialog(
            lock = pending.lock,
            action = "run",
            onConfirm = { viewModel.confirmLockOverride() },
            onCancel = { viewModel.cancelLockOverride() },
        )
    }

    if (pendingModeEntry != null) {
        ModeEntryConfirmationDialog(
            entry = pendingModeEntry!!,
            onForSession = { viewModel.confirmModeEntry(forSession = true) },
            onJustOnce   = { viewModel.confirmModeEntry(forSession = false) },
            onCancel     = { viewModel.cancelModeEntry() },
        )
    }

    Scaffold(
        containerColor = bgColor,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BoriTopBar(
                title = agentShortLabel(userPrefs.chatProvider),
                titleContent = {
                    // The agent you're talking to; tap to switch.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showModelMenu = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(agentShortLabel(userPrefs.chatProvider), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val model = providerLabel.substringAfter(" · ", "")
                            if (model.isNotEmpty()) {
                                Text(
                                    model.substringAfterLast('/'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch agent")
                    }
                },
                actions = {
                    ProjectChip(name = activeProject?.name, onClick = { showProjectPicker = true }, maxWidth = 140.dp)
                    Box {
                        androidx.compose.material3.IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BoriDropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Plan mode") },
                                leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null) },
                                trailingIcon = { if (isPlanMode) Icon(Icons.Default.Check, contentDescription = "On") },
                                onClick = { showOverflowMenu = false; viewModel.togglePlanMode() },
                            )
                            DropdownMenuItem(
                                text = { Text("Ask mode") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                                trailingIcon = { if (isAskMode) Icon(Icons.Default.Check, contentDescription = "On") },
                                onClick = { showOverflowMenu = false; viewModel.toggleAskMode() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    if (isCompacting) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Text("Compacting…")
                                        }
                                    } else {
                                        Text("Compact conversation")
                                    }
                                },
                                enabled = !isCompacting,
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.compactConversation()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear conversation") },
                                onClick = {
                                    showOverflowMenu = false
                                    showClearConfirmDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        // On an unfolded screen the conversation stays at a readable width, centered.
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        // The room left above the keyboard decides the layout, whatever the keyboard's height, the fold
        // state or the split: below TIGHT_HEIGHT the extras fold away so the composer always fits.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .imePadding()
        ) {
        val tight = maxHeight < TIGHT_HEIGHT
        Column(modifier = Modifier.fillMaxSize()) {
            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (isPlanMode && pendingPlan == null) PlanModeBanner(aiName)
            if (isAskMode && pendingQuestions == null && pendingPlan == null) AskModeBanner(aiName)
            if (!hasActiveProject) NoProjectBanner()
            com.bori.app.ui.common.AgentLockBanner(otherAgentLock, compact = tight)
            if (!tight) com.bori.app.ui.common.FoldTipBanner()
            AnimatedVisibility(visible = showCompactBanner && !isCompacting && !tight) {
                CompactConversationBanner(
                    onCompact = { viewModel.compactConversation() },
                    onDismiss = { viewModel.dismissCompactBanner() },
                )
            }
            if (userPrefs.chatProvider == AiProvider.LOCAL_GEMMA) {
                when (val s = gemmaInitState) {
                    is InferenceState.Loading -> ChatPanel(
                        modifier = Modifier.fillMaxWidth(),
                        fill = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        tier = PanelTier.TILE,
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "Initializing Gemma model…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    is InferenceState.Error -> ChatPanel(
                        modifier = Modifier.fillMaxWidth(),
                        fill = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        tier = PanelTier.TILE,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Gemma failed to load",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(onClick = { viewModel.deleteAndRedownload() }) {
                                Text("Delete & Re-download")
                            }
                        }
                    }
                    else -> {}
                }
            }
            if (terminalTarget != null && !terminalTarget!!.readable) {
                TerminalScreenMirror(terminalTarget!!, terminalScreen, Modifier.weight(1f).fillMaxWidth())
            } else if (messages.isEmpty() && terminalTarget != null) {
                TerminalTargetEmpty(terminalTarget!!, Modifier.weight(1f).fillMaxWidth())
            } else if (messages.isEmpty()) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        EmptyStateContent(
                            modifier = Modifier.fillMaxWidth(),
                            onPromptClick = { text -> input = text },
                            showTutorialCard = showTutorialCard,
                            onStartTutorial = onStartTutorial,
                            onDismissTutorial = onDismissTutorial,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(messages.size) { index ->
                        val message = messages[index]
                        val prevRole = messages.getOrNull(index - 1)?.role
                        val nextRole = messages.getOrNull(index + 1)?.role
                        MessageBubble(
                            message = message,
                            bubbleColor = bubbleColor,
                            isFirstInGroup = prevRole != message.role,
                            isLastInGroup = nextRole != message.role,
                        )
                    }
                }
            }
            // Measured before the messages (they take what's left), so the composer always gets its full
            // height. The cards above it shrink and scroll when there's little room.
            Column(modifier = Modifier.fillMaxWidth().background(bgColor)) {
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (pendingPlanModeRequest != null) {
                    PlanModeSuggestionCard(
                        request       = pendingPlanModeRequest!!,
                        onForThisPlan = { viewModel.acceptPlanModeSuggestion(forSession = false) },
                        onForSession  = { viewModel.acceptPlanModeSuggestion(forSession = true) },
                        onDecline     = { viewModel.declinePlanModeSuggestion() },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (pendingPlanExitRequest != null) {
                    PlanExitConfirmationCard(
                        request   = pendingPlanExitRequest!!,
                        onApprove = { viewModel.approvePlanExit() },
                        onDecline = { viewModel.declinePlanExit() },
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (pendingQuestions != null) {
                    MultiQuestionCard(
                        pending  = pendingQuestions!!,
                        onSubmit = { answers -> viewModel.submitAnswers(answers) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (pendingPlan != null) {
                    PlanReviewCard(
                        pending   = pendingPlan!!,
                        onApprove = { viewModel.approvePlan() },
                        onReject  = { viewModel.rejectPlan() },
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (pendingApproval != null) {
                    ApprovalCard(
                        pending = pendingApproval!!,
                        onAllow = { viewModel.approveToolCall() },
                        onDeny = { viewModel.denyToolCall() },
                        // A coding CLI's question is answered once; Allow All would be its bypass mode.
                        onAllowAll = if (pendingApproval!!.fromCli) null
                            else ({ viewModel.setAutoApprove(true); viewModel.approveToolCall() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (terminalTarget != null) {
                    terminalLive.menu?.let { menu ->
                        CliMenuCard(
                            agent = terminalTarget!!.agent,
                            menu = menu,
                            onPick = { viewModel.answerTerminal(it) },
                            onAnswerText = { n, t -> viewModel.answerTerminalText(n, t) },
                            onBack = { viewModel.backTerminal() },
                            onCancel = { viewModel.interruptTerminal() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    } ?: if (terminalLive.waiting) {
                        CliAskFallbackCard(
                            agent = terminalTarget!!.agent,
                            screen = terminalLive.screenTail,
                            onYes = { viewModel.approveTerminal() },
                            onNo = { viewModel.interruptTerminal() },
                            onShowTerminal = { viewModel.showTargetInTerminal(); onOpenTerminal() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    } else if (terminalLive.error != null && terminalLive.status == null) {
                        CliErrorCard(
                            agent = terminalTarget!!.agent,
                            error = terminalLive.error!!,
                            onRetry = { viewModel.retryTerminal() },
                            onShowTerminal = { viewModel.showTargetInTerminal(); onOpenTerminal() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    } else terminalLive.status?.let { status ->
                        CliStatusRow(
                            status = status,
                            onStop = { viewModel.interruptTerminal() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                if (slashSuggestions.isNotEmpty()) {
                    SlashCommandList(
                        commands = slashSuggestions,
                        agent = terminalTarget?.agent ?: "Bori agent",
                        onPick = { input = it.name + " " },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (atMentionSuggestions.isNotEmpty()) {
                    ChatPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .heightIn(max = 200.dp),
                        fill = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tier = PanelTier.CARD,
                    ) { Column {
                        LazyColumn {
                            items(atMentionSuggestions) { path ->
                                val name = path.substringAfterLast('/')
                                ListItem(
                                    colors = crystalListItemColors(),
                                    headlineContent = {
                                        Text(name, style = MaterialTheme.typography.bodySmall)
                                    },
                                    supportingContent = {
                                        Text(
                                            path.substringAfter("/${com.bori.core.project.ProjectRepository.PROJECTS_DIR}/")
                                                .substringBeforeLast('/'),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        val atIdx = input.lastIndexOf('@')
                                        if (atIdx >= 0) {
                                            input = input.substring(0, atIdx + 1) + name + " "
                                        }
                                        viewModel.setAtMentionQuery("")
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }}
                }
                if (pendingAttachments.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        items(pendingAttachments.size) { index ->
                            val att = pendingAttachments[index]
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = {
                                    Text(
                                        att.chipLabel(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    Icon(att.chipIcon(), contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                trailingIcon = {
                                    androidx.compose.material3.IconButton(
                                        onClick = { viewModel.removeAttachment(index) },
                                        modifier = Modifier.size(18.dp),
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp))
                                    }
                                },
                            )
                        }
                    }
                }
                if (terminalTargets.isNotEmpty() || startableAgents.isNotEmpty()) {
                    TerminalTargetBar(
                        targets = terminalTargets,
                        chosen = terminalTarget,
                        live = terminalLive,
                        startable = startableAgents,
                        onChoose = { viewModel.chooseTerminalTarget(it) },
                        onOpenTerminal = { viewModel.showTargetInTerminal(); onOpenTerminal() },
                        onStartMenu = { viewModel.refreshStartableAgents() },
                        onStart = { agent ->
                            // What's typed so far goes as the first message once the agent is ready.
                            viewModel.startTerminalAgent(agent, input)
                            input = ""
                        },
                        compact = tight,
                    )
                }
                }
                val chipLabel = terminalTarget?.agent ?: agentShortLabel(userPrefs.chatProvider)
                PillInputBar(
                    input = input,
                    // Bori's agent working doesn't stop a message to the terminal.
                    isLoading = isLoading && terminalTarget == null,
                    pendingApproval = pendingApproval.takeIf { terminalTarget == null },
                    showAttachMenu = showAttachMenu,
                    providerLabel = chipLabel,
                    modeLabel = when {
                        terminalTarget != null -> null
                        isPlanMode -> "Plan"
                        isAskMode -> "Ask"
                        else -> null
                    },
                    onModeChipClick = { if (isPlanMode) viewModel.togglePlanMode() else viewModel.toggleAskMode() },
                    sendColor = bubbleColor,
                    onInputChange = { newValue ->
                        input = newValue
                        val atIdx = newValue.lastIndexOf('@')
                        if (atIdx >= 0 && (atIdx == newValue.length - 1 ||
                                newValue.substring(atIdx + 1).none { it.isWhitespace() })) {
                            val query = newValue.substring(atIdx + 1)
                                .takeWhile { !it.isWhitespace() }
                            viewModel.setAtMentionQuery(query)
                        } else {
                            viewModel.setAtMentionQuery("")
                        }
                    },
                    onSend = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            viewModel.sendMessage(text)
                            input = ""
                        }
                    },
                    onStop = { viewModel.stopGeneration() },
                    onCaretClick = { showAttachMenu = true },
                    onAttachMenuDismiss = { showAttachMenu = false },
                    onPickImage = { imagePicker.launch("image/*") },
                    onPickDocument = { documentPicker.launch("*/*") },
                    onAttachProject = { viewModel.attachProjectContext() },
                    onShowCommands = { showCommandsSheet = true },
                    // In terminal mode the agent is the one in that tab: the chip shows it in the terminal.
                    onProviderChipClick = {
                        if (terminalTarget != null) { viewModel.showTargetInTerminal(); onOpenTerminal() } else showModelMenu = true
                    },
                    compact = tight,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = if (tight) 4.dp else 6.dp),
                )
            }
        }
        }
        }
    }
}

/** Below this much room (keyboard up in landscape, a tall keyboard, a small split) Chat goes compact. */
private val TIGHT_HEIGHT = 380.dp

@Composable
private fun EmptyStateContent(
    onPromptClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    showTutorialCard: Boolean = false,
    onStartTutorial: () -> Unit = {},
    onDismissTutorial: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Icon(
            imageVector = Icons.Default.Code,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "What are we building?",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.size(16.dp))
            listOf("Fix a bug", "Explain code", "Add a feature").forEach { label ->
                SuggestionChip(colors = crystalAssistChipColors(), 
                    onClick = { onPromptClick(label) },
                    label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                )
            }
            Spacer(Modifier.size(16.dp))
        }
        if (showTutorialCard) {
            Spacer(Modifier.height(12.dp))
            TutorialPromptCard(
                onStart = onStartTutorial,
                onDismiss = onDismissTutorial,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TutorialPromptCard(
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChatPanel(
        modifier = modifier.fillMaxWidth(),
        fill = noticeColors(NoticeTone.INFO).fill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.CARD,
    ) { Column {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column {
                    Text(
                        text = "Build your first app",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Watch local AI write a Calculator app on-device. Runs on the downloaded Gemma model. No internet or cloud key needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Text("Start Tutorial")
                }
                OutlinedButton(onClick = onDismiss) {
                    Text("Maybe later")
                }
            }
        }
    }}
}


@Composable
private fun PillInputBar(
    input: String,
    isLoading: Boolean,
    pendingApproval: PendingToolCall?,
    showAttachMenu: Boolean,
    providerLabel: String,
    modeLabel: String?,
    onModeChipClick: () -> Unit,
    sendColor: Color,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCaretClick: () -> Unit,
    onAttachMenuDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    onAttachProject: () -> Unit,
    onShowCommands: () -> Unit,
    onProviderChipClick: () -> Unit,
    /** Little room above the keyboard: the field and Send share one row; the chips wait for more room. */
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val canSend = input.isNotBlank() && pendingApproval == null && !isLoading
    val border = MaterialTheme.colorScheme.outlineVariant
    val attachButton: @Composable () -> Unit = {
        Box {
            androidx.compose.material3.IconButton(
                onClick = onCaretClick,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Attach",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BoriDropdownMenu(
                expanded = showAttachMenu,
                onDismissRequest = onAttachMenuDismiss,
            ) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Commands", style = MaterialTheme.typography.bodyMedium)
                            Text("/plan, /ask, /tutorial, /explain, /fix, /test, /refactor, /docs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { onAttachMenuDismiss(); onShowCommands() },
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Image", style = MaterialTheme.typography.bodyMedium)
                            Text("JPEG, PNG, WebP, GIF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { onAttachMenuDismiss(); onPickImage() },
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Document", style = MaterialTheme.typography.bodyMedium)
                            Text(".txt, .md, .kt, .java, .xml, .json, .kts, .py, .js, .ts, .html, .css", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { onAttachMenuDismiss(); onPickDocument() },
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Project Context", style = MaterialTheme.typography.bodyMedium)
                            Text("Active project file tree", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { onAttachMenuDismiss(); onAttachProject() },
                )
            }
        }
    }
    val textField: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = fieldModifier,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = if (compact) 3 else 6,
            decorationBox = { innerTextField ->
                if (input.isEmpty()) {
                    Text(
                        if (isLoading) "Thinking…" else "Ask me anything…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            },
        )
    }
    val sendButton: @Composable () -> Unit = {
        Surface(
            shape = CircleShape,
            color = when {
                isLoading -> MaterialTheme.colorScheme.error
                // sendColor = the user's chosen chat bubble color (threaded from prefs).
                canSend   -> sendColor
                else      -> MaterialTheme.colorScheme.surfaceContainerHighest
            },
            // 48 dp, Android's minimum touch size: at 38 dp with a 16 dp arrow it was hard to hit
            // with the keyboard up on the unfolded Fold in landscape. requiredSize keeps the chips
            // in this row from squeezing it smaller.
            modifier = Modifier
                .requiredSize(48.dp)
                .clickable(enabled = isLoading || canSend) {
                    if (isLoading) onStop() else onSend()
                },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (isLoading) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(if (isLoading) 20.dp else 22.dp),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(if (isLoading) 20.dp else 22.dp),
                    )
                }
            }
        }
    }
    // One tree for both layouts: the text field keeps its place (and its focus) when the keyboard's
    // arrival makes Chat compact. Building a second field there dropped focus, Compose hid the
    // keyboard, the room came back, and the keyboard never stayed up.
    ChatPanel(
        modifier = modifier.fillMaxWidth(),
        fill = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.CARD,
    ) {
        Column {
            // ---- Text area (compact: attach, field and Send share this row) ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 4.dp else 16.dp,
                        top = if (compact) 4.dp else 14.dp,
                        end = if (compact) 6.dp else 16.dp,
                        bottom = if (compact) 4.dp else 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compact) attachButton()
                Box(Modifier.weight(1f).padding(horizontal = if (compact) 6.dp else 0.dp, vertical = if (compact) 8.dp else 0.dp)) {
                    textField(Modifier.fillMaxWidth())
                }
                if (compact) sendButton()
            }
            // ---- Action row (chips wait for more room) ----
            if (!compact) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                attachButton()
                // Provider chip
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, border),
                    modifier = Modifier.clickable(onClick = onProviderChipClick),
                ) {
                    Text(
                        providerLabel,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Plan or Ask mode, when on; tap to go back to normal agent mode.
                if (modeLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.clickable(onClick = onModeChipClick),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                modeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Leave $modeLabel mode",
                                modifier = Modifier.padding(start = 4.dp).size(14.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                sendButton()
            }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessageEntity,
    bubbleColor: Color,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
) {
    when {
        message.role == "tool_result" -> ToolResultBubble(content = message.content)
        message.role == "thinking"    -> ThinkingBubble(content = message.content)
        message.role == "user"        -> UserBubble(content = message.content, bubbleColor = bubbleColor, isFirstInGroup = isFirstInGroup, isLastInGroup = isLastInGroup)
        else                          -> AssistantBubble(content = message.content, isFirstInGroup = isFirstInGroup, isLastInGroup = isLastInGroup)
    }
}

@Composable
private fun UserBubble(
    content: String,
    bubbleColor: Color,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
) {
    val clipboard = LocalClipboardManager.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 56.dp, end = 12.dp,
                top = if (isFirstInGroup) 8.dp else 2.dp,
                bottom = 2.dp,
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        ChatPanel(
            modifier = Modifier,
            fill = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tier = PanelTier.CARD,
        ) {
            Column {
                Text(
                    text = content,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Box {
                    androidx.compose.material3.IconButton(
                        onClick = { clipboard.setText(AnnotatedString(content)) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp),
                            // White (semi-transparent) for contrast: plain gray was invisible
                            // on the colored user bubble.
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(content: String, isFirstInGroup: Boolean = true, isLastInGroup: Boolean = true) {
    val clipboard = LocalClipboardManager.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 12.dp, end = 56.dp,
                top = if (isFirstInGroup) 8.dp else 2.dp,
                bottom = 2.dp,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        ChatPanel(
            modifier = Modifier.fillMaxWidth(),
            fill = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tier = PanelTier.CARD,
        ) {
            Column {
                MarkdownText(
                    markdown = content,
                    modifier = Modifier.padding(
                        start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp,
                    ),
                    style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    linkColor = MaterialTheme.colorScheme.primary,
                )
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { clipboard.setText(AnnotatedString(content)) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp),
                            // Theme-aware tint so the copy icon stays readable on the neutral
                            // assistant bubble in both light and dark mode.
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolResultBubble(content: String) {
    var expanded by remember { mutableStateOf(false) }
    // Rows start with "[tool] what it ran on": show that as the label ("Read · MainActivity.kt").
    val label = remember(content) {
        Regex("^⚠? ?\\[([^\\]]+)\\] ?(.*)").find(content.lineSequence().first())
            ?.let { m -> listOf(m.groupValues[1], m.groupValues[2]).filter { it.isNotBlank() }.joinToString(" · ") }
            ?: "Tool result"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        ChatPanel(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .fillMaxWidth(0.9f),
            fill = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tier = PanelTier.TILE,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoProjectBanner() {
    val notice       = noticeColors(NoticeTone.WARNING)
    val bannerBg     = notice.fill
    val bannerText   = notice.text
    ChatPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        fill = bannerBg,
        contentColor = bannerText,
        tier = PanelTier.TILE,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = notice.tint)
            Text(
                text = "No project open. Pick one with the project chip at the top, or in Projects.",
                style = MaterialTheme.typography.bodySmall,
                color = bannerText,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalCard(
    pending: PendingToolCall,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onAllowAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cardBg     = MaterialTheme.colorScheme.surfaceContainer
    val deleteBg   = MaterialTheme.colorScheme.errorContainer
    val deleteText = MaterialTheme.colorScheme.onErrorContainer
    val addText    = BoriTheme.colors.diffAdded
    val addBg      = addText.copy(alpha = 0.15f)
    ChatPanel(
        modifier = modifier,
        fill = cardBg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.CARD,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (pending.fromCli) "Allow once: ${pending.name}" else "Allow tool: ${pending.name}?",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (pending.name == "apply_edit") {
                val parsed = runCatching { org.json.JSONObject(pending.inputJson) }.getOrNull()
                val filePath = parsed?.optString("file_path", "").orEmpty()
                val oldText  = parsed?.optString("old_text", "").orEmpty()
                val newText  = parsed?.optString("new_text", "").orEmpty()
                if (parsed != null && filePath.isNotEmpty()) {
                    Text(
                        text = filePath,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                    ) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Surface(
                                color = deleteBg,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = "− $oldText",
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = deleteText,
                                    softWrap = false,
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Surface(
                                color = addBg,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = "+ $newText",
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = addText,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                    ) {
                        Text(
                            text = pending.inputJson,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                            softWrap = false,
                        )
                    }
                }
            } else {
                Text(
                    text = pending.inputJson,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onAllowAll != null) FilledTonalButton(onClick = onAllowAll) { Text("Allow All") }
                Button(
                    onClick = onAllow,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("Allow") }
                OutlinedButton(onClick = onDeny) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun AskModeBanner(aiName: String) {
    val notice = noticeColors(NoticeTone.ASK)
    val bg     = notice.fill
    val border = notice.border
    val tint   = notice.tint
    ChatPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        fill = bg,
        contentColor = tint,
        tier = PanelTier.TILE,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Column {
                Text(
                    "Ask mode — $aiName will ask clarifying questions before writing a plan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
                Text(
                    "Note: this mode uses more tokens than a normal message.",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/**
 * Inline clarifying-questions card for the `ask_questions` tool — renders 1–4 questions at once
 * (header chip + FilterChip options via FlowRow + a free-text "Other" field each) with a single
 * Submit. Replaces the old one-question-per-card AskModeQuestionCard; collected answers are
 * returned in order via [onSubmit]. Submit stays disabled until every question has an answer.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MultiQuestionCard(
    pending: PendingQuestions,
    onSubmit: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardBg  = MaterialTheme.colorScheme.surfaceContainer
    val inputBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val tint    = MaterialTheme.colorScheme.tertiary

    // Per-question state: selected option(s) and free-text "Other".
    val selected = remember(pending) { pending.items.map { mutableStateListOf<String>() } }
    val otherText = remember(pending) { mutableStateListOf(*Array(pending.items.size) { "" }) }

    fun answerFor(i: Int): String {
        val parts = selected[i].toList() + listOfNotNull(otherText[i].trim().ifBlank { null })
        return parts.joinToString(", ")
    }
    val allAnswered = pending.items.indices.all { answerFor(it).isNotBlank() }

    ChatPanel(
        modifier = modifier,
        fill = cardBg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.CARD,
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Info, contentDescription = null,
                    tint = tint, modifier = Modifier.size(18.dp))
                Text(
                    if (pending.items.size == 1) "Question" else "${pending.items.size} questions",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            pending.items.forEachIndexed { i, item ->
                Spacer(Modifier.height(if (i == 0) 10.dp else 16.dp))
                if (item.header.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = tint.copy(alpha = 0.12f),
                    ) {
                        Text(
                            item.header,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(item.question, style = MaterialTheme.typography.bodyMedium)
                if (item.options.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.options.forEach { option ->
                            val isSel = option in selected[i]
                            FilterChip(colors = crystalFilterChipColors(), 
                                selected = isSel,
                                onClick = {
                                    if (item.allowMultiple) {
                                        if (isSel) selected[i].remove(option) else selected[i].add(option)
                                    } else {
                                        selected[i].clear()
                                        if (!isSel) selected[i].add(option)
                                    }
                                },
                                label = { Text(option, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = inputBg,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BasicTextField(
                        value = otherText[i],
                        onValueChange = { otherText[i] = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                            .heightIn(min = 36.dp, max = 100.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (otherText[i].isEmpty()) {
                                Text(
                                    if (item.options.isNotEmpty()) "Other / add detail…" else "Type your answer…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                            innerTextField()
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (allAnswered) onSubmit(pending.items.indices.map { answerFor(it) }) },
                enabled = allAnswered,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text(if (pending.items.size == 1) "Submit Answer" else "Submit Answers") }
        }
    }
}

/**
 * Confirmation card for the `exit_plan_mode` tool — the graceful escape from Plan Mode. The AI
 * requests it (with an optional [PendingPlanModeRequest.reason]); "Build it" resumes the loop and
 * starts editing, "Stay in plan" keeps Plan Mode active.
 */
@Composable
private fun PlanExitConfirmationCard(
    request: PendingPlanModeRequest,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice = noticeColors(NoticeTone.PLAN)
    val bg     = notice.fill
    val border = notice.border
    val tint   = notice.tint
    ChatPanel(
        modifier = modifier,
        fill = bg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.TILE,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Text(
                    "Exit Plan Mode and start building?",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
            if (request.reason.isNotEmpty()) {
                Text(request.reason, style = MaterialTheme.typography.bodySmall, color = tint)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("Build it", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, tint),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("Stay in plan", style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
        }
    }
}

@Composable
private fun PlanModeSuggestionCard(
    request: PendingPlanModeRequest,
    onForThisPlan: () -> Unit,
    onForSession: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice = noticeColors(NoticeTone.PLAN)
    val bg     = notice.fill
    val border = notice.border
    val tint   = notice.tint
    ChatPanel(
        modifier = modifier,
        fill = bg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.TILE,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Text(
                    "Switch to Plan Mode?",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
            if (request.reason.isNotEmpty()) {
                Text(
                    request.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
            }
            Text(
                "Note: Plan Mode uses more tokens than a normal message.",
                style = MaterialTheme.typography.labelSmall,
                color = tint.copy(alpha = 0.75f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onForThisPlan,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, tint),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("Allow for this plan", style = MaterialTheme.typography.labelSmall, color = tint)
                }
                OutlinedButton(
                    onClick = onForSession,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, tint),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("Allow for session", style = MaterialTheme.typography.labelSmall, color = tint)
                }
                androidx.compose.material3.IconButton(onClick = onDecline, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Decline", tint = tint, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CompactConversationBanner(onCompact: () -> Unit, onDismiss: () -> Unit) {
    val notice = noticeColors(NoticeTone.WARNING)
    val bg     = notice.fill
    val border = notice.border
    val tint   = notice.tint
    ChatPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        fill = bg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.TILE,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Conversation is getting long — compact to free up context.",
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCompact) {
                Text("Compact", style = MaterialTheme.typography.labelMedium, color = tint)
            }
            androidx.compose.material3.IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = tint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ModeEntryConfirmationDialog(
    entry: PendingModeEntry,
    onForSession: () -> Unit,
    onJustOnce: () -> Unit,
    onCancel: () -> Unit,
) {
    val modeName = if (entry.mode == ModeType.PLAN) "Plan" else "Ask"
    val modeDesc = if (entry.mode == ModeType.PLAN)
        "Plan mode has the AI explore your project and write a detailed plan before making any changes. You approve the plan before any code is written."
    else
        "Ask mode has the AI ask clarifying questions before proceeding. This takes extra turns and tokens to gather requirements."
    BoriAlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Enter $modeName Mode?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(modeDesc, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Note: this mode uses more tokens than a normal message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onJustOnce) { Text("Just once") }
                Button(onClick = onForSession) { Text("For this session") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
private fun PlanModeBanner(aiName: String) {
    val notice = noticeColors(NoticeTone.PLAN)
    val bg     = notice.fill
    val border = notice.border
    val tint   = notice.tint
    ChatPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        fill = bg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.TILE,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Column {
                Text(
                    "Plan mode — $aiName will explore and write a plan, then wait for your approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
                Text(
                    "Note: this mode uses more tokens than a normal message.",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint.copy(alpha = 0.75f),
                )
            }
        }
    }
}

private fun Attachment.chipLabel(): String = when (this) {
    is Attachment.ImageAttachment -> fileName.substringAfterLast('/').substringAfterLast('%').ifEmpty { "image" }
    is Attachment.DocumentAttachment -> fileName.substringAfterLast('/').substringAfterLast('%').ifEmpty { "document" }
    is Attachment.ProjectContextAttachment -> "Project: $projectName"
}

private fun Attachment.chipIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    is Attachment.ImageAttachment -> Icons.Default.Image
    is Attachment.DocumentAttachment -> Icons.Default.Description
    is Attachment.ProjectContextAttachment -> Icons.Default.FolderOpen
}

@Composable
private fun PlanReviewCard(
    pending: PendingPlan,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardBg = MaterialTheme.colorScheme.surfaceContainer
    var expanded by remember { mutableStateOf(false) }
    ChatPanel(
        modifier = modifier,
        fill = cardBg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.CARD,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text("Plan ready", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (pending.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    pending.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                ) {
                    Box(modifier = Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
                        MarkdownText(
                            markdown = pending.plan,
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            linkColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("Approve & Execute") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
            }
        }
    }
}

/** The short name the chat shows for an agent (top bar, input chip). */
private fun agentShortLabel(provider: AiProvider): String = when (provider) {
    AiProvider.LOCAL_GEMMA -> "Gemma"
    AiProvider.ANTHROPIC   -> "Claude"
    AiProvider.OPENAI      -> "OpenAI"
    AiProvider.DEEPSEEK    -> "DeepSeek"
    AiProvider.GEMINI      -> "Gemini"
    AiProvider.KIMI, AiProvider.KIMI_CODE -> "Kimi"
    AiProvider.LOCAL_SERVER -> "PC"
    AiProvider.OPENROUTER  -> "OpenRouter"
    AiProvider.CLAUDE_CODE -> "Claude Code"
    AiProvider.ANTIGRAVITY -> "Antigravity"
    else                   -> "AI"
}

private fun agentGroup(provider: AiProvider): String = when {
    provider.isCli -> "Coding CLIs (Linux)"
    provider.isLocal || provider == AiProvider.LOCAL_SERVER -> "On this phone or your PC"
    else -> "Cloud models"
}

private fun agentGroupOrder(provider: AiProvider): Int = when {
    provider.isCli -> 0
    provider.isLocal || provider == AiProvider.LOCAL_SERVER -> 2
    else -> 1
}

/** Where Chat sends: Bori's own agent, or a coding CLI running in a terminal tab. */
@Composable
private fun TerminalTargetBar(
    targets: List<com.bori.app.terminal.TerminalBridge.Target>,
    chosen: com.bori.app.terminal.TerminalBridge.Target?,
    live: com.bori.app.terminal.TerminalBridge.Live,
    startable: List<com.bori.app.terminal.TerminalBridge.Startable>,
    onChoose: (Int?) -> Unit,
    onOpenTerminal: () -> Unit,
    onStartMenu: () -> Unit,
    onStart: (com.bori.app.terminal.TerminalBridge.Startable) -> Unit,
    /** Little room (keyboard up in landscape): the status goes into the chips' row, one line in all. */
    compact: Boolean = false,
) {
    var startMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact && chosen != null) {
                TargetStatusLine(chosen, live, onOpenTerminal, compact = true)
            }
            Text("Send to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterChip(colors = crystalFilterChipColors(), selected = chosen == null, onClick = { onChoose(null) }, label = { Text("Bori agent") })
            val sameAgent = targets.groupingBy { it.agent }.eachCount()
            targets.forEach { t ->
                FilterChip(colors = crystalFilterChipColors(), 
                    selected = chosen?.tabId == t.tabId,
                    onClick = { onChoose(t.tabId) },
                    label = { Text("Terminal · " + t.agent + if ((sameAgent[t.agent] ?: 0) > 1) " (${t.title})" else "") },
                )
            }
            if (startable.isNotEmpty()) {
                Box {
                    SuggestionChip(colors = crystalAssistChipColors(), 
                        onClick = { onStartMenu(); startMenu = true },
                        label = { Text("Start…") },
                        icon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                    BoriDropdownMenu(expanded = startMenu, onDismissRequest = { startMenu = false }) {
                        val distros = startable.map { it.distroId }.distinct().size
                        startable.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.cli.title + if (distros > 1) " · ${a.distroId}" else "") },
                                onClick = { startMenu = false; onStart(a) },
                            )
                        }
                    }
                }
            }
        }
        if (chosen != null && !compact) TargetStatusLine(chosen, live, onOpenTerminal, compact = false)
    }
}

/** What the chosen terminal's agent is doing, and a button to show it: its own line, or (compact) inline. */
@Composable
private fun TargetStatusLine(
    chosen: com.bori.app.terminal.TerminalBridge.Target,
    live: com.bori.app.terminal.TerminalBridge.Live,
    onOpenTerminal: () -> Unit,
    compact: Boolean,
) {
    val waiting = live.menu != null || live.waiting
    Row(verticalAlignment = Alignment.CenterVertically, modifier = if (compact) Modifier else Modifier.fillMaxWidth()) {
        // The CLI's mode, as its footer shows it ("⏸ plan mode on"): Shift+Tab in the terminal changes it.
        live.mode?.let { mode ->
            Text(
                mode,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(
            when {
                // The same words the terminal shows ("Flabbergasting…"), not a fixed "working".
                waiting -> "${chosen.agent} is asking you. Answer below or in the terminal."
                live.status != null -> "${chosen.agent}: ${live.status.verb}"
                live.error != null -> "${chosen.agent} hit an API error. Retry below, or send a message."
                // The hooks can leave WORKING behind (an Esc interrupt fires none): the screen decides.
                chosen.state == com.bori.app.service.StatusNotifier.AgentState.WORKING && !live.atPrompt -> "${chosen.agent} is working…"
                chosen.state != null && live.atPrompt -> "${chosen.agent} is ready. Your turn."
                chosen.state == com.bori.app.service.StatusNotifier.AgentState.DONE -> "${chosen.agent} is done. Your turn."
                else -> "Typed into ${chosen.title}, as if you typed it in the terminal"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (waiting || live.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (compact) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = if (compact) Modifier.widthIn(max = 320.dp) else Modifier.weight(1f),
        )
        TextButton(onClick = onOpenTerminal) { Text(if (waiting) "Open terminal" else "Show terminal") }
    }
}

/** The "/" commands for the agent Chat sends to, like a CLI's own "/" menu: name, what it does, and whose it is. */
@Composable
private fun SlashCommandList(
    commands: List<com.bori.app.terminal.SlashCommand>,
    agent: String,
    onPick: (com.bori.app.terminal.SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChatPanel(
        modifier = modifier,
        fill = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.CARD,
    ) { Column {
        Text(
            "$agent commands",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        )
        LazyColumn(Modifier.heightIn(max = 260.dp)) {
            items(commands.size) { i ->
                val c = commands[i]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(c) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        c.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.widthIn(min = 96.dp, max = 200.dp),
                    )
                    Text(
                        c.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    when (c.source) {
                        com.bori.app.terminal.SlashCommand.Source.USER -> SourceTag("yours")
                        com.bori.app.terminal.SlashCommand.Source.PROJECT -> SourceTag("project")
                        com.bori.app.terminal.SlashCommand.Source.BUILT_IN -> Unit
                    }
                }
            }
        }
    }}
}

@Composable
private fun SourceTag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * A CLI whose transcript Chat can't read: its terminal screen, live. Messages sent from Chat are typed
 * there, and its replies show here as it draws them.
 */
@Composable
private fun TerminalScreenMirror(
    target: com.bori.app.terminal.TerminalBridge.Target,
    screen: String?,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(screen) { scroll.scrollTo(scroll.maxValue) }
    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            "Live view of ${target.title}. What you send is typed there; ${target.agent} answers on this screen.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .verticalScroll(scroll)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    screen?.takeIf { it.isNotBlank() } ?: "Waiting for ${target.agent} to draw its screen…",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** A terminal agent with nothing to show yet. */
@Composable
private fun TerminalTargetEmpty(target: com.bori.app.terminal.TerminalBridge.Target, modifier: Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Messages you send here are typed into ${target.title}, where ${target.agent} is running. " +
                "Its replies show here as it writes them, and the terminal shows the same session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** A coding CLI's thinking, mirrored from its transcript: folded to "Thought" like the CLI shows it. */
@Composable
private fun ThinkingBubble(content: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 56.dp, top = 2.dp, bottom = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("✻", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            Text(
                if (expanded) "Thought" else "Thought · " + content.lineSequence().first().take(80),
                style = MaterialTheme.typography.labelMedium,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide thinking" else "Show thinking",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                content,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, top = 4.dp),
            )
        }
    }
}

/**
 * The CLI's live status line, as the terminal shows it: its spinner, its word for what it's doing
 * ("Flabbergasting…"), the time and tokens, and the tip under it. Stop is the terminal's Esc.
 */
@Composable
private fun CliStatusRow(
    status: com.bori.app.terminal.CliStatus.Status,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(120); frame++ }
    }
    val glyphs = "·✢✳✶✻✽✻✶✳✢"
    ChatPanel(
        modifier = modifier,
        fill = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tier = PanelTier.TILE,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                glyphs[frame % glyphs.length].toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(min = 18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        status.verb,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    status.detail?.let {
                        Text(
                            "  $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                status.tip?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Stop")
            }
        }
    }
}

/**
 * The CLI's last turn ended on an API error (overloaded, rate limit, no network): what it said, and
 * Retry, which types "continue" so it picks the turn up again.
 */
@Composable
private fun CliErrorCard(
    agent: String,
    error: String,
    onRetry: () -> Unit,
    onShowTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice = noticeColors(NoticeTone.WARNING)
    ChatPanel(modifier = modifier, fill = notice.fill, contentColor = notice.text, tier = PanelTier.CARD) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$agent stopped on an API error", style = MaterialTheme.typography.labelMedium, color = notice.tint)
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = notice.text,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onRetry) { Text("Retry") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onShowTerminal) { Text("Show terminal") }
            }
        }
    }
}

/**
 * The CLI asks (its approval hook said so) but its menu couldn't be read off the screen: show the
 * screen's last lines and answer the usual way, Yes presses 1 and No presses Esc.
 */
@Composable
private fun CliAskFallbackCard(
    agent: String,
    screen: List<String>,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onShowTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice = noticeColors(NoticeTone.ASK)
    ChatPanel(modifier = modifier, fill = notice.fill, contentColor = notice.text, tier = PanelTier.CARD) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$agent is asking for your approval", style = MaterialTheme.typography.labelMedium, color = notice.tint)
            if (screen.isNotEmpty()) {
                Text(
                    screen.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = notice.text,
                    modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onYes, modifier = Modifier.weight(1f)) { Text("Yes") }
                OutlinedButton(onClick = onNo, modifier = Modifier.weight(1f)) { Text("No") }
            }
            TextButton(onClick = onShowTerminal, modifier = Modifier.align(Alignment.End)) { Text("Show terminal") }
        }
    }
}

/**
 * A menu the CLI shows in the terminal (an approval, a question, a plan to accept), answered from Chat:
 * each button presses its number in the terminal, Cancel presses Esc. The terminal shows the same menu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CliMenuCard(
    agent: String,
    menu: com.bori.app.terminal.CliStatus.Menu,
    onPick: (Int) -> Unit,
    onAnswerText: (Int, String) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice = noticeColors(NoticeTone.ASK)
    // The answer typed in: "Type something." takes it in the menu itself; "No, and tell Claude…" after.
    val textOption = menu.options.firstOrNull { it.inline } ?: menu.options.firstOrNull { it.asksText }
    var typed by remember(menu.question) { mutableStateOf("") }
    ChatPanel(modifier = modifier, fill = notice.fill, contentColor = notice.text, tier = PanelTier.CARD) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("$agent asks", style = MaterialTheme.typography.labelMedium, color = notice.tint)
            menu.subject?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = notice.text,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(menu.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = notice.text)
            menu.options.forEach { o ->
                val label = "${o.number}. ${o.label}"
                if (o.number == 1) {
                    Button(onClick = { onPick(o.number) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    }
                } else {
                    OutlinedButton(onClick = { onPick(o.number) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    }
                }
                o.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = notice.text.copy(alpha = 0.75f),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            if (textOption != null) {
                val send = { if (typed.isNotBlank()) { onAnswerText(textOption.number, typed); typed = "" } }
                androidx.compose.material3.OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = {
                        Text(if (textOption.inline) "Type your own answer" else "Tell $agent what to do instead")
                    },
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = send, enabled = typed.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send answer")
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Or type it in the box at the bottom and send.",
                    style = MaterialTheme.typography.bodySmall,
                    color = notice.text.copy(alpha = 0.75f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (menu.steps) TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(if (menu.canCancel) "Cancel (Esc)" else "Esc") }
            }
        }
    }
}
