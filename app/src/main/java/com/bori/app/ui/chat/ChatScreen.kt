package com.bori.app.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.BorderStroke
import com.bori.app.ui.theme.isAppInDarkTheme
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
private val BgCream           = Color(0xFFF9F8F3)
private val UserBubbleColor   = Color(0xFF1A56DB)
private val AssistantBubbleBg = Color(0xFFF2F2F7)
private val SubtleGray        = Color(0xFF64748B)
private val BorderGray        = Color(0xFFE2E8F0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    showTutorialCard: Boolean = false,
    onStartTutorial: () -> Unit = {},
    onDismissTutorial: () -> Unit = {},
) {
    val messages by viewModel.messages.collectAsState()
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

    val slashSuggestions = remember(input) {
        val word = input.substringBefore(' ')
        if (!word.startsWith("/") || word.length < 1) emptyList()
        else viewModel.slashCommandNames.filter { it.startsWith(word, ignoreCase = true) }
    }

    val darkTheme = isAppInDarkTheme()
    val bgColor = BoriTheme.colors.chatBackground
    // User-selectable bubble color (ARGB long persisted in prefs). Threaded into the user
    // message bubbles and the send button so the chat accent follows the user's choice.
    val bubbleColor = Color(userPrefs.chatBubbleColor)

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
        ModalBottomSheet(
            onDismissRequest = { showCommandsSheet = false },
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
        ModalBottomSheet(
            onDismissRequest = { showModelMenu = false },
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
        AlertDialog(
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
                containerColor = bgColor,
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
                        DropdownMenu(
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
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .imePadding()
        ) {
            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (isPlanMode && pendingPlan == null) PlanModeBanner(aiName)
            if (isAskMode && pendingQuestions == null && pendingPlan == null) AskModeBanner(aiName)
            if (!hasActiveProject) NoProjectBanner()
            com.bori.app.ui.common.FoldTipBanner()
            AnimatedVisibility(visible = showCompactBanner && !isCompacting) {
                CompactConversationBanner(
                    onCompact = { viewModel.compactConversation() },
                    onDismiss = { viewModel.dismissCompactBanner() },
                )
            }
            if (userPrefs.chatProvider == AiProvider.LOCAL_GEMMA) {
                when (val s = gemmaInitState) {
                    is InferenceState.Loading -> Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
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
                    is InferenceState.Error -> Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
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
            if (messages.isEmpty()) {
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
            Column(modifier = Modifier.fillMaxWidth().background(bgColor)) {
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
                if (slashSuggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        slashSuggestions.forEach { cmd ->
                            SuggestionChip(
                                onClick = { input = "$cmd " },
                                label = { Text(cmd, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
                if (atMentionSuggestions.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .heightIn(max = 200.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    ) {
                        LazyColumn {
                            items(atMentionSuggestions) { path ->
                                val name = path.substringAfterLast('/')
                                ListItem(
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
                    }
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
                val chipLabel = agentShortLabel(userPrefs.chatProvider)
                PillInputBar(
                    input = input,
                    isLoading = isLoading,
                    pendingApproval = pendingApproval,
                    showAttachMenu = showAttachMenu,
                    providerLabel = chipLabel,
                    modeLabel = when {
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
                    onProviderChipClick = { showModelMenu = true },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        }
    }
}

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
                SuggestionChip(
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
    val darkTheme = isAppInDarkTheme()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (darkTheme) Color(0xFF1E2A3A) else Color(0xFFEFF6FF),
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (darkTheme) Color(0xFF2D4A6A) else Color(0xFFBFDBFE)),
    ) {
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
    }
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
    modifier: Modifier = Modifier,
) {
    val canSend = input.isNotBlank() && pendingApproval == null && !isLoading
    val darkTheme = isAppInDarkTheme()
    val pillBg = if (darkTheme) Color(0xFF1E1D1C) else Color.White
    val border = if (darkTheme) Color(0xFF3A3938) else BorderGray
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = pillBg,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // ---- Text area ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 10.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    decorationBox = { innerTextField ->
                        if (input.isEmpty()) {
                            Text(
                                if (isLoading) "Thinking…" else "Ask me anything…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFF94A3B8),
                            )
                        }
                        innerTextField()
                    },
                )
            }
            // ---- Action row ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // + attach button
                Box {
                    androidx.compose.material3.IconButton(
                        onClick = onCaretClick,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Attach",
                            modifier = Modifier.size(20.dp),
                            tint = SubtleGray,
                        )
                    }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = onAttachMenuDismiss,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Commands", style = MaterialTheme.typography.bodyMedium)
                                    Text("/plan, /ask, /tutorial, /explain, /fix, /test, /refactor, /docs", style = MaterialTheme.typography.labelSmall, color = SubtleGray)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { onAttachMenuDismiss(); onShowCommands() },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Image", style = MaterialTheme.typography.bodyMedium)
                                    Text("JPEG, PNG, WebP, GIF", style = MaterialTheme.typography.labelSmall, color = SubtleGray)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { onAttachMenuDismiss(); onPickImage() },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Document", style = MaterialTheme.typography.bodyMedium)
                                    Text(".txt, .md, .kt, .java, .xml, .json, .kts, .py, .js, .ts, .html, .css", style = MaterialTheme.typography.labelSmall, color = SubtleGray)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { onAttachMenuDismiss(); onPickDocument() },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Project Context", style = MaterialTheme.typography.bodyMedium)
                                    Text("Active project file tree", style = MaterialTheme.typography.labelSmall, color = SubtleGray)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { onAttachMenuDismiss(); onAttachProject() },
                        )
                    }
                }
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
                        color = SubtleGray,
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
                // Send / Stop button
                Surface(
                    shape = CircleShape,
                    color = when {
                        isLoading -> Color(0xFFEF4444)
                        // sendColor = the user's chosen chat bubble color (threaded from prefs).
                        canSend   -> sendColor
                        else      -> if (darkTheme) Color(0xFF2C2B2A) else Color(0xFFF1F5F9)
                    },
                    modifier = Modifier
                        .size(38.dp)
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
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) Color.White else Color(0xFFADB5BD),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
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
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = if (isFirstInGroup) 18.dp else 14.dp,
                bottomStart = 18.dp,
                bottomEnd = if (isLastInGroup) 4.dp else 14.dp,
            ),
        ) {
            Column {
                Text(
                    text = content,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
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
                            tint = Color.White.copy(alpha = 0.7f),
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
    val bubbleBg = if (isAppInDarkTheme()) Color(0xFF2C2B2A) else AssistantBubbleBg
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
        Surface(
            color = bubbleBg,
            shape = RoundedCornerShape(
                topStart = if (isFirstInGroup) 4.dp else 14.dp,
                topEnd = 18.dp,
                bottomStart = if (isLastInGroup) 18.dp else 14.dp,
                bottomEnd = 18.dp,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                MarkdownText(
                    markdown = content,
                    modifier = Modifier.padding(
                        start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp,
                    ),
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
    val toolBg = if (isAppInDarkTheme()) Color(0xFF2C2B2A) else Color(0xFFEEECE6)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            color = toolBg,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .clickable { expanded = !expanded }
                .fillMaxWidth(0.9f),
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
                        tint = SubtleGray,
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = SubtleGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = SubtleGray,
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
    val darkTheme    = isAppInDarkTheme()
    val bannerBg     = if (darkTheme) Color(0xFF2A2518) else Color(0xFFFFF8E1)
    val bannerBorder = if (darkTheme) Color(0xFF5A4B00) else Color(0xFFFFE082)
    val bannerText   = if (darkTheme) Color(0xFFFFE082) else Color(0xFF5D4037)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = bannerBg,
        border = BorderStroke(1.dp, bannerBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFF8F00))
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
    val darkTheme  = isAppInDarkTheme()
    val cardBg     = if (darkTheme) Color(0xFF1E1D1C) else Color.White
    val cardBorder = if (darkTheme) Color(0xFF3A3938) else BorderGray
    val deleteBg   = if (darkTheme) Color(0xFF3B1A1A) else Color(0xFFFFEBEE)
    val deleteText = if (darkTheme) Color(0xFFEF9A9A) else Color(0xFFC62828)
    val addBg      = if (darkTheme) Color(0xFF1A2F1A) else Color(0xFFE8F5E9)
    val addText    = if (darkTheme) Color(0xFF81C784) else Color(0xFF2E7D32)
    Surface(
        modifier = modifier,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
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
                        color = SubtleGray,
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
                    colors = ButtonDefaults.buttonColors(containerColor = UserBubbleColor),
                ) { Text("Allow") }
                OutlinedButton(onClick = onDeny) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun AskModeBanner(aiName: String) {
    val darkTheme = isAppInDarkTheme()
    val bg     = if (darkTheme) Color(0xFF1F1535) else Color(0xFFF5F3FF)
    val border = if (darkTheme) Color(0xFF4C3070) else Color(0xFFDDD6FE)
    val tint   = if (darkTheme) Color(0xFFD8B4FE) else Color(0xFF7C3AED)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
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
    val darkTheme = isAppInDarkTheme()
    val cardBg  = if (darkTheme) Color(0xFF1E1D1C) else Color.White
    val inputBg = if (darkTheme) Color(0xFF2C2B2A) else Color(0xFFF8F8F8)
    val tint    = if (darkTheme) Color(0xFFD8B4FE) else Color(0xFF7C3AED)

    // Per-question state: selected option(s) and free-text "Other".
    val selected = remember(pending) { pending.items.map { mutableStateListOf<String>() } }
    val otherText = remember(pending) { mutableStateListOf(*Array(pending.items.size) { "" }) }

    fun answerFor(i: Int): String {
        val parts = selected[i].toList() + listOfNotNull(otherText[i].trim().ifBlank { null })
        return parts.joinToString(", ")
    }
    val allAnswered = pending.items.indices.all { answerFor(it).isNotBlank() }

    Surface(
        modifier = modifier,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        color = cardBg,
        border = BorderStroke(1.dp, BorderGray),
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
                            FilterChip(
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
                    border = BorderStroke(1.dp, BorderGray),
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
                colors = ButtonDefaults.buttonColors(containerColor = UserBubbleColor),
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
    val darkTheme = isAppInDarkTheme()
    val bg     = if (darkTheme) Color(0xFF1A2035) else Color(0xFFEEF2FF)
    val border = if (darkTheme) Color(0xFF3B4D8A) else Color(0xFFC7D2FE)
    val tint   = if (darkTheme) Color(0xFFA5B4FC) else Color(0xFF3730A3)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
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
                    colors = ButtonDefaults.buttonColors(containerColor = UserBubbleColor),
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
    val darkTheme = isAppInDarkTheme()
    val bg     = if (darkTheme) Color(0xFF1A2035) else Color(0xFFEEF2FF)
    val border = if (darkTheme) Color(0xFF3B4D8A) else Color(0xFFC7D2FE)
    val tint   = if (darkTheme) Color(0xFFA5B4FC) else Color(0xFF3730A3)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
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
    val darkTheme = isAppInDarkTheme()
    val bg     = if (darkTheme) Color(0xFF2A2000) else Color(0xFFFFFBEB)
    val border = if (darkTheme) Color(0xFF705E00) else Color(0xFFFDE68A)
    val tint   = if (darkTheme) Color(0xFFFCD34D) else Color(0xFF92400E)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
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
    AlertDialog(
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
    val darkTheme = isAppInDarkTheme()
    val bg     = if (darkTheme) Color(0xFF1A2035) else Color(0xFFEEF2FF)
    val border = if (darkTheme) Color(0xFF3B4D8A) else Color(0xFFC7D2FE)
    val tint   = if (darkTheme) Color(0xFFA5B4FC) else Color(0xFF3730A3)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
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
    val darkTheme = isAppInDarkTheme()
    val cardBg = if (darkTheme) Color(0xFF1E1D1C) else Color.White
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        color = cardBg,
        border = BorderStroke(1.dp, BorderGray),
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
                    color = if (darkTheme) Color(0xFF2C2B2A) else Color(0xFFF8F8F8),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                ) {
                    Box(modifier = Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
                        MarkdownText(markdown = pending.plan)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = UserBubbleColor),
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
