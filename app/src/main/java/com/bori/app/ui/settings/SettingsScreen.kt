package com.bori.app.ui.settings

import com.bori.app.ui.common.ShellContentInsets
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import com.bori.app.ui.common.FoldTipSection
import com.bori.app.ui.common.isFoldable
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bori.core.project.ProjectRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.hilt.navigation.compose.hiltViewModel
import com.bori.app.data.prefs.UserPreferences
import com.bori.app.ui.common.CollapsibleSection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.VerticalDivider
import com.bori.app.data.prefs.ThemeMode
import com.bori.app.ui.adaptive.LocalWindowLayout
import com.bori.app.ui.common.LinkRow
import com.bori.app.ui.common.BoriTopBar
import com.bori.app.ui.common.ReadableColumn
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.core.security.AiProvider
import kotlinx.coroutines.launch

// Known models per provider, from the shared ModelCatalog (single source of truth).
// OpenRouter is forced empty so the picker shows a free-text field (it has hundreds
// of models that can't be enumerated).
private val chatModels: Map<AiProvider, List<String>> = AiProvider.entries.associateWith {
    if (it == AiProvider.OPENROUTER) emptyList() else com.bori.core.ai.model.ModelCatalog.chatModelIds(it)
}

private val completionModels: Map<AiProvider, List<String>> = AiProvider.entries.associateWith {
    if (it == AiProvider.OPENROUTER) emptyList() else com.bori.core.ai.model.ModelCatalog.completionModelIds(it)
}

/**
 * Catalog models first (with their notes), then any extra ones from the provider's live list.
 * OpenRouter stays a free-text field; a local server has no catalog, so it shows only its live list.
 */
private fun withLiveModels(
    catalog: Map<AiProvider, List<String>>,
    live: Map<AiProvider, List<String>>,
): Map<AiProvider, List<String>> = catalog.mapValues { (provider, models) ->
    if (provider == AiProvider.OPENROUTER) models else (models + live[provider].orEmpty()).distinct()
}

/** Where to get each key, shown under the key field. */
private val keyHints: Map<AiProvider, String> = mapOf(
    AiProvider.ANTHROPIC to "API key from platform.claude.com. Anthropic doesn't allow Claude Pro/Max " +
        "sign-in in third-party apps, so a Claude subscription can't be used here.",
    AiProvider.OPENAI to "API key from platform.openai.com.",
    AiProvider.DEEPSEEK to "API key from platform.deepseek.com.",
    AiProvider.OPENROUTER to "API key from openrouter.ai/keys.",
    AiProvider.GEMINI to "API key from Google AI Studio (aistudio.google.com).",
    AiProvider.KIMI to "Pay-as-you-go key from platform.kimi.ai.",
    AiProvider.KIMI_CODE to "Use the key from your Kimi Code membership page. Billed to your subscription.",
    AiProvider.LOCAL_SERVER to "Address of Ollama or LM Studio on your PC, e.g. http://100.64.0.1:11434 " +
        "(Ollama) or :1234 (LM Studio). Over Tailscale, use the PC's 100.x address. Then tap Refresh models below.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    backupViewModel: BackupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val prefs by viewModel.userPrefs.collectAsState()
    val isGemmaReady by viewModel.isGemmaReady.collectAsState()
    val deletedProjects by viewModel.deletedProjects.collectAsState()
    var confirmPermanentDelete by remember { mutableStateOf<ProjectRepository.TrashedProject?>(null) }
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var pendingExportPassword by remember { mutableStateOf("") }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let {
            backupViewModel.exportBackup(it, pendingExportPassword.toCharArray())
            pendingExportPassword = ""
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { pendingImportUri = it; showImportPasswordDialog = true } }

    LaunchedEffect(backupState) {
        when (val s = backupState) {
            is BackupViewModel.BackupState.Success -> {
                coroutineScope.launch { snackbarHostState.showSnackbar(s.message) }
                backupViewModel.resetState()
                viewModel.refreshApiKeys()
            }
            is BackupViewModel.BackupState.Error -> {
                coroutineScope.launch { snackbarHostState.showSnackbar(s.message) }
                backupViewModel.resetState()
            }
            else -> {}
        }
    }

    // Categories: a list that opens one at a time on a phone, side by side when unfolded.
    var category by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    val twoPane = LocalWindowLayout.current.isExpanded
    val shown = category ?: if (twoPane) SettingsCategory.AGENTS else null
    BackHandler(enabled = !twoPane && category != null) { category = null }

    Scaffold(

        contentWindowInsets = ShellContentInsets,
        topBar = {
            BoriTopBar(
                title = if (!twoPane && shown != null) shown.title else "Settings",
                onBack = if (!twoPane && category != null) ({ category = null }) else onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (twoPane || shown == null) {
            Column(
                modifier = (if (twoPane) Modifier.width(320.dp) else Modifier.weight(1f))
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            ) {
                ReadableColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SettingsCategory.entries.forEach { c ->
                        val selected = twoPane && c == shown
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                                .padding(horizontal = if (twoPane) 8.dp else 0.dp),
                        ) {
                            LinkRow(c.title, c.summary, c.icon) { category = c }
                        }
                    }
                }
            }
            if (twoPane) VerticalDivider()
        }
        if (shown != null) {
        ReadableColumn(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (shown == SettingsCategory.AGENTS) {
            // ── AI Configuration ─────────────────────────────────────────────
            val displayProvider = uiState.selectedDisplayProvider
            CollapsibleSection(
                title = "AI models & keys",
                summary = "Chat: ${prefs.chatProvider.displayName} · ${prefs.chatModel.ifBlank { "default" }}",
                icon = Icons.Default.SmartToy,
            ) {
            AiConfigSection(
                selectedDisplayProvider = displayProvider,
                savedProviders = uiState.savedProviders,
                keyInput = uiState.keyInputs[displayProvider].orEmpty(),
                isKeySaved = uiState.savedProviders.contains(displayProvider),
                activeChatProvider = prefs.chatProvider,
                activeChatModel = prefs.chatModel,
                activeCompletionProvider = prefs.completionProvider,
                activeCompletionModel = prefs.completionModel,
                isGemmaReady = prefs.experimentalFeaturesEnabled && isGemmaReady,
                cachedModels = prefs.cachedModels,
                refreshingModels = uiState.refreshingModels,
                modelFetchErrors = uiState.modelFetchErrors,
                onRefreshModels = { viewModel.refreshModels(it) },
                onDisplayProviderChanged = { viewModel.setDisplayProvider(it) },
                onKeyChanged = { viewModel.updateKeyInput(displayProvider, it) },
                onSaveKey = { viewModel.saveKey(displayProvider) },
                onDeleteKey = { viewModel.deleteKey(displayProvider) },
                onChatProviderSelected = { viewModel.setChatProvider(it) },
                onChatModelSelected = { viewModel.setChatModel(it) },
                onCompletionProviderSelected = { viewModel.setCompletionProvider(it) },
                onCompletionModelSelected = { viewModel.setCompletionModel(it) },
            )
            }

            // ── Usage ────────────────────────────────────────────────────────
            val usage by viewModel.usage.collectAsState()
            // Refresh token totals + live balances once when the Settings screen opens.
            LaunchedEffect(Unit) { viewModel.refreshUsage() }
            CollapsibleSection(
                title = "Usage",
                summary = "Tokens and balances per provider",
                icon = Icons.Default.BarChart,
            ) {
            UsageSection(
                usage = usage,
                onRefresh = { viewModel.refreshUsage() },
                onReset = { viewModel.resetUsage(it) },
            )
            }

            }

            if (shown == SettingsCategory.APPEARANCE) {
            // ── Theme ────────────────────────────────────────────────────────
            CollapsibleSection(
                title = "Theme",
                summary = prefs.themeMode.label,
                icon = Icons.Default.DarkMode,
                initiallyExpanded = true,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = prefs.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }

            // ── Chat appearance ──────────────────────────────────────────────
            CollapsibleSection(
                title = "Chat appearance",
                summary = "Bubble and send-button colour",
                icon = Icons.Default.Palette,
            ) {
            ChatAppearanceSection(
                selectedColor = prefs.chatBubbleColor,
                onSelectColor = { viewModel.setChatBubbleColor(it) },
            )
            }

            // ── Folding (foldables only) ─────────────────────────────────────
            if (LocalContext.current.isFoldable()) {
                CollapsibleSection(
                    title = "Folding",
                    summary = "Keep Bori Code open on the cover screen",
                    icon = Icons.Default.PhoneAndroid,
                ) {
                    FoldTipSection()
                }
            }

            }

            if (shown == SettingsCategory.AGENTS) {
            // ── Web Search (Tavily) ──────────────────────────────────────────
            val uriHandler = LocalUriHandler.current
            CollapsibleSection(
                title = "Web search",
                summary = if (uiState.tavilyKeySaved) "Tavily key saved" else "Not set up",
                icon = Icons.Default.Search,
            ) {
            Text(
                "Add a Tavily API key to let the AI search the web and research while it codes. " +
                    "Without a key, web_fetch and download_image still work, but web_search does not.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "How to get a free key:\n" +
                    "1. Sign up at app.tavily.com (free — 1,000 searches/month, no card).\n" +
                    "2. On the dashboard, copy your API key (starts with \"tvly-\").\n" +
                    "3. Paste it below and tap Save key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            TextButton(
                onClick = { uriHandler.openUri("https://app.tavily.com/home") },
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text("Get a free Tavily API key →", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = uiState.tavilyKeyInput,
                onValueChange = { viewModel.updateTavilyKeyInput(it) },
                label = {
                    Text(if (uiState.tavilyKeySaved) "Tavily API key (saved — enter to replace)" else "Tavily API key")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = { viewModel.saveTavilyKey() },
                    enabled = uiState.tavilyKeyInput.isNotBlank(),
                ) { Text("Save key") }
                if (uiState.tavilyKeySaved) {
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    OutlinedButton(onClick = { viewModel.deleteTavilyKey() }) { Text("Remove") }
                }
            }
            }

            }

            if (shown == SettingsCategory.BUILD) {
            // ── Build Settings ───────────────────────────────────────────────
            CollapsibleSection(
                title = "Build",
                summary = "${prefs.compilerPerformanceProfile.name} profile · ${prefs.kotlinTimeoutMinutes} min timeout",
                icon = Icons.Default.Build,
            ) {
            // Compiler Performance Profile
            Text(
                "Performance Profile",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "LOW: Memory saver. Compiler cold-starts every build.\n" +
                "MEDIUM: Balanced. Warm compiler stays in memory (~200MB RAM) for fast incremental builds.\n" +
                "HIGH: Performance boost. Warm compiler + ADPF CPU boosting for maximum speed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            var profileExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = profileExpanded,
                onExpandedChange = { profileExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = prefs.compilerPerformanceProfile.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Compiler performance profile") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = profileExpanded,
                    onDismissRequest = { profileExpanded = false },
                ) {
                    CompilerPerformanceProfile.entries.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name) },
                            onClick = {
                                viewModel.setCompilerPerformanceProfile(profile)
                                profileExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.padding(vertical = 8.dp))

            // Kotlin Timeout
            Text(
                "Increase the Kotlin compiler timeout for complex projects (e.g. GBA/NES emulators with large instruction decoders). Longer timeouts use more battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            val timeoutOptions = listOf(2, 5, 10)
            var timeoutExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = timeoutExpanded,
                onExpandedChange = { timeoutExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = "${prefs.kotlinTimeoutMinutes} min",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Kotlin compiler timeout") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeoutExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = timeoutExpanded,
                    onDismissRequest = { timeoutExpanded = false },
                ) {
                    timeoutOptions.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text("$minutes min${if (minutes == 2) " (default)" else ""}") },
                            onClick = {
                                viewModel.setKotlinTimeoutMinutes(minutes)
                                viewModel.setAiWorkTurns(when (minutes) {
                                    2  -> 50
                                    5  -> 100
                                    10 -> 200
                                    else -> 50
                                })
                                timeoutExpanded = false
                            },
                        )
                    }
                }
            }
            }

            // ── Release Signing ──────────────────────────────────────────────
            val keystoreForm = uiState.keystoreForm
            CollapsibleSection(
                title = "Release signing",
                summary = if (keystoreForm.enabled) "Custom keystore" else "Default debug key",
                icon = Icons.Default.Key,
            ) {
            SigningSection(
                form = keystoreForm,
                onEnabledChanged = { viewModel.setKeystoreEnabled(it) },
                onPathChanged = { viewModel.updateKeystorePath(it) },
                onAliasChanged = { viewModel.updateKeystoreAlias(it) },
                onKeystorePasswordChanged = { viewModel.updateKeystorePassword(it) },
                onKeyPasswordChanged = { viewModel.updateKeyPassword(it) },
                onSave = { viewModel.saveKeystoreConfig() },
                onVerify = { viewModel.verifyKeystore() },
            )
            }

            }

            if (shown == SettingsCategory.DATA) {
            // ── Backup & Restore ─────────────────────────────────────────────
            CollapsibleSection(
                title = "Backup & restore",
                summary = "Encrypted export and import",
                icon = Icons.Default.Backup,
            ) {
            Text(
                "Exports API keys, settings, chat history, and all project source files into an encrypted backup. " +
                    "The backup is AES-256 encrypted with your password. If you lose the password, the backup cannot be recovered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showExportPasswordDialog = true },
                    enabled = backupState !is BackupViewModel.BackupState.InProgress,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Export Backup")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    enabled = backupState !is BackupViewModel.BackupState.InProgress,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Import Backup")
                }
            }
            if (backupState is BackupViewModel.BackupState.InProgress) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            }

            if (showExportPasswordDialog) {
                PasswordSetDialog(
                    onDismiss = { showExportPasswordDialog = false },
                    onConfirm = { password ->
                        pendingExportPassword = password
                        showExportPasswordDialog = false
                        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportLauncher.launch("bori_code_backup_$date.mbackup")
                    },
                )
            }

            if (showImportPasswordDialog) {
                PasswordEnterDialog(
                    onDismiss = {
                        showImportPasswordDialog = false
                        pendingImportUri = null
                    },
                    onConfirm = { password ->
                        pendingImportUri?.let { backupViewModel.importBackup(it, password.toCharArray()) }
                        showImportPasswordDialog = false
                        pendingImportUri = null
                    },
                )
            }

            // ── Deleted Projects ─────────────────────────────────────────────
            CollapsibleSection(
                title = "Deleted projects",
                summary = if (deletedProjects.isEmpty()) "Empty" else "${deletedProjects.size} in trash",
                icon = Icons.Default.RestoreFromTrash,
            ) {

            if (deletedProjects.isEmpty()) {
                Text(
                    "No deleted projects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                deletedProjects.forEach { trashed ->
                    val dateStr = remember(trashed.deletedAt) {
                        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(trashed.deletedAt))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(trashed.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Deleted $dateStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.restoreProject(trashed) }) {
                            Icon(Icons.Default.RestoreFromTrash, contentDescription = "Restore")
                        }
                        IconButton(onClick = { confirmPermanentDelete = trashed }) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = "Delete permanently",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            }

            confirmPermanentDelete?.let { trashed ->
                AlertDialog(
                    onDismissRequest = { confirmPermanentDelete = null },
                    title = { Text("Permanently delete?") },
                    text = { Text("\"${trashed.name}\" will be deleted forever and cannot be recovered.") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.permanentlyDeleteFromTrash(trashed)
                            confirmPermanentDelete = null
                        }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmPermanentDelete = null }) { Text("Cancel") }
                    },
                )
            }

            }

            if (shown == SettingsCategory.AGENTS) {
            // ── Local AI (experimental) ──────────────────────────────────────
            val localModelViewModel: LocalModelViewModel = hiltViewModel()
            val localModel by localModelViewModel.selectedModel.collectAsState()
            CollapsibleSection(
                title = "Local AI (offline)",
                summary = if (prefs.experimentalFeaturesEnabled) "On · ${localModel.label}" else "Off",
                icon = Icons.Default.Memory,
            ) {
            Text(
                "Experimental: may be unstable or change in future updates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Local AI model (Gemma)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Run Gemma on-device — works offline, no API key needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = prefs.experimentalFeaturesEnabled,
                    onCheckedChange = { viewModel.setExperimentalFeaturesEnabled(it) },
                )
            }
            if (prefs.experimentalFeaturesEnabled) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                LocalModelSection(viewModel = localModelViewModel)
            }
            TextButton(onClick = {
                viewModel.restartTutorial()
                coroutineScope.launch { snackbarHostState.showSnackbar("The tutorial card is back in Chat") }
            }) { Text("Restart the calculator tutorial") }
            }
            }
        }
        }
        }
    }
}

/** The groups Settings opens into. */
private enum class SettingsCategory(val title: String, val summary: String, val icon: ImageVector) {
    AGENTS("Agents & models", "API keys, chat models, usage, web search, on-device AI", Icons.Default.SmartToy),
    APPEARANCE("Appearance", "Theme and chat colours", Icons.Default.Palette),
    BUILD("Build & signing", "Compiler profile, timeouts, release keystore", Icons.Default.Build),
    DATA("Backup & data", "Export, import, deleted projects", Icons.Default.Backup),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiConfigSection(
    selectedDisplayProvider: AiProvider,
    savedProviders: Set<AiProvider>,
    keyInput: String,
    isKeySaved: Boolean,
    activeChatProvider: AiProvider,
    activeChatModel: String,
    activeCompletionProvider: AiProvider,
    activeCompletionModel: String,
    isGemmaReady: Boolean,
    cachedModels: Map<AiProvider, List<String>>,
    refreshingModels: AiProvider?,
    modelFetchErrors: Map<AiProvider, String>,
    onRefreshModels: (AiProvider) -> Unit,
    onDisplayProviderChanged: (AiProvider) -> Unit,
    onKeyChanged: (String) -> Unit,
    onSaveKey: () -> Unit,
    onDeleteKey: () -> Unit,
    onChatProviderSelected: (AiProvider) -> Unit,
    onChatModelSelected: (String) -> Unit,
    onCompletionProviderSelected: (AiProvider) -> Unit,
    onCompletionModelSelected: (String) -> Unit,
) {
    Column {
        var providerExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = providerExpanded,
            onExpandedChange = { providerExpanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selectedDisplayProvider.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false },
            ) {
                val providers = buildList {
                    addAll(AiProvider.entries.filterNot { it.isLocal || it.isCli })
                    if (isGemmaReady) add(AiProvider.LOCAL_GEMMA)
                }
                providers.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        onClick = {
                            onDisplayProviderChanged(provider)
                            providerExpanded = false
                        },
                    )
                }
            }
        }

        if (!selectedDisplayProvider.isLocal) {
            Spacer(Modifier.height(8.dp))
            // The local server's "key" is its URL: nothing secret, so show it and use a URL keyboard
            val isServerUrl = selectedDisplayProvider == AiProvider.LOCAL_SERVER
            OutlinedTextField(
                value = keyInput,
                onValueChange = onKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isServerUrl) "Server URL" else "API Key") },
                placeholder = {
                    Text(
                        when {
                            isKeySaved && isServerUrl -> "URL saved — enter new to replace"
                            isKeySaved -> "Key saved — enter new to replace"
                            isServerUrl -> "http://100.x.y.z:11434"
                            else -> "Paste key here"
                        },
                    )
                },
                visualTransformation = if (isServerUrl) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = if (isServerUrl) KeyboardType.Uri else KeyboardType.Password),
                singleLine = true,
            )
            keyHints[selectedDisplayProvider]?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Button(
                    onClick = onSaveKey,
                    enabled = keyInput.isNotBlank(),
                ) {
                    Text("Save Key")
                }
                if (isKeySaved) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onDeleteKey) {
                        Text("Remove Key")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text(
            "Active Configuration",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            "Only providers with a saved key are available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        ProviderModelPicker(
            label = "Chat",
            selectedProvider = activeChatProvider,
            selectedModel = activeChatModel,
            availableProviders = savedProviders,
            modelOptions = withLiveModels(chatModels, cachedModels),
            isRefreshing = refreshingModels == activeChatProvider,
            fetchError = modelFetchErrors[activeChatProvider],
            onRefreshModels = { onRefreshModels(activeChatProvider) },
            onProviderSelected = onChatProviderSelected,
            onModelSelected = onChatModelSelected,
        )

        Spacer(Modifier.height(8.dp))

        ProviderModelPicker(
            label = "Completion",
            selectedProvider = activeCompletionProvider,
            selectedModel = activeCompletionModel,
            availableProviders = savedProviders,
            modelOptions = withLiveModels(completionModels, cachedModels),
            isRefreshing = refreshingModels == activeCompletionProvider,
            fetchError = modelFetchErrors[activeCompletionProvider],
            onRefreshModels = { onRefreshModels(activeCompletionProvider) },
            isGemmaReady = isGemmaReady,
            onProviderSelected = onCompletionProviderSelected,
            onModelSelected = onCompletionModelSelected,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelPicker(
    label: String,
    selectedProvider: AiProvider,
    selectedModel: String,
    availableProviders: Set<AiProvider>,
    modelOptions: Map<AiProvider, List<String>>,
    isRefreshing: Boolean,
    fetchError: String?,
    onRefreshModels: () -> Unit,
    onProviderSelected: (AiProvider) -> Unit,
    onModelSelected: (String) -> Unit,
    isGemmaReady: Boolean = false,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)

        // Provider dropdown
        var providerExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = providerExpanded,
            onExpandedChange = { providerExpanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            OutlinedTextField(
                value = selectedProvider.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false },
            ) {
                val providers = buildList {
                    addAll(AiProvider.entries.filterNot { it.isLocal || it.isCli })
                    if (isGemmaReady) add(AiProvider.LOCAL_GEMMA)
                }
                providers.forEach { provider ->
                    val isAvailable = provider.isLocal || provider in availableProviders
                    DropdownMenuItem(
                        text = {
                            Text(
                                provider.displayName,
                                color = if (!isAvailable)
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            onProviderSelected(provider)
                            onModelSelected(modelOptions[provider]?.firstOrNull() ?: "")
                            providerExpanded = false
                        },
                    )
                }
            }
        }

        // Model picker — dropdown for known providers, text field for OpenRouter
        val models = modelOptions[selectedProvider] ?: emptyList()
        if (models.isEmpty()) {
            // OpenRouter (or unknown): free-text
            OutlinedTextField(
                value = selectedModel,
                onValueChange = onModelSelected,
                label = { Text("Model (e.g. moonshotai/kimi-k3)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = true,
            )
        } else {
            var modelExpanded by remember { mutableStateOf(false) }
            val displayModel = selectedModel.ifEmpty { "(default)" }
            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = displayModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false },
                ) {
                    // "(default)" option — clears the stored model so router uses its built-in default
                    DropdownMenuItem(
                        text = { Text("(default)") },
                        onClick = {
                            onModelSelected("")
                            modelExpanded = false
                        },
                    )
                    models.forEach { model ->
                        // Surface the catalog's free-tier note so users see "needs billing /
                        // may 429" before picking a model that would fail (e.g. Gemini 3.x).
                        val meta = com.bori.core.ai.model.ModelCatalog.model(selectedProvider, model)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(model)
                                    meta?.note?.let { note ->
                                        Text(
                                            text = if (meta.freeTierQuota) note else "⚠ $note",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (meta.freeTierQuota)
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onModelSelected(model)
                                modelExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // Live list: adds every model the key can use to the menu above
        if (!selectedProvider.isLocal && selectedProvider != AiProvider.OPENROUTER) {
            TextButton(
                onClick = onRefreshModels,
                enabled = selectedProvider in availableProviders && !isRefreshing,
            ) {
                Text(if (isRefreshing) "Fetching models…" else "Refresh models")
            }
            if (fetchError != null) {
                Text(
                    text = fetchError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SigningSection(
    form: KeystoreFormState,
    onEnabledChanged: (Boolean) -> Unit,
    onPathChanged: (String) -> Unit,
    onAliasChanged: (String) -> Unit,
    onKeystorePasswordChanged: (String) -> Unit,
    onKeyPasswordChanged: (String) -> Unit,
    onSave: () -> Unit,
    onVerify: () -> Unit,
) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val path = uri.path ?: return@rememberLauncherForActivityResult
            // Resolve /document/primary:... → /sdcard/...
            val resolved = if (path.startsWith("/document/primary:")) {
                "/sdcard/" + path.removePrefix("/document/primary:")
            } else {
                // Try content resolver to get a real path via file descriptor
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    "/proc/self/fd/${it.fd}"
                } ?: path
            }
            onPathChanged(resolved)
        }
    }

    Text(
        "By default every build is signed with an auto-generated debug key. " +
            "Enable this to sign with your own release keystore (.bks, .jks, .p12).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Use custom keystore", modifier = Modifier.weight(1f))
        Switch(checked = form.enabled, onCheckedChange = onEnabledChanged)
    }

    if (form.enabled) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = form.path,
                onValueChange = onPathChanged,
                label = { Text("Keystore path") },
                placeholder = { Text("/sdcard/my.jks") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            IconButton(onClick = {
                filePicker.launch(arrayOf("*/*"))
            }) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Browse")
            }
        }

        OutlinedTextField(
            value = form.alias,
            onValueChange = onAliasChanged,
            label = { Text("Key alias") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            singleLine = true,
        )

        OutlinedTextField(
            value = form.keystorePassword,
            onValueChange = onKeystorePasswordChanged,
            label = { Text("Keystore password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            singleLine = true,
        )

        OutlinedTextField(
            value = form.keyPassword,
            onValueChange = onKeyPasswordChanged,
            label = { Text("Key password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            singleLine = true,
        )

        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onSave, enabled = form.path.isNotBlank() && form.alias.isNotBlank()) {
                Text("Save")
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onVerify, enabled = form.path.isNotBlank() && form.alias.isNotBlank()) {
                Text("Verify")
            }
        }

        form.verifyResult?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.startsWith("OK")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ProviderKeySection(
    provider: AiProvider,
    currentKey: String,
    isSaved: Boolean,
    onKeyChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = provider.displayName,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        OutlinedTextField(
            value = currentKey,
            onValueChange = onKeyChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            placeholder = { Text(if (isSaved) "Key saved — enter new to replace" else "Paste key here") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        Button(
            onClick = onSave,
            enabled = currentKey.isNotBlank(),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("Save Key")
        }
        if (isSaved) {
            Button(
                onClick = onDelete,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Remove Key")
            }
        }
    }
}

@Composable
private fun PasswordSetDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val mismatch = password.isNotEmpty() && confirm.isNotEmpty() && password != confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Backup Password") },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm password") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (mismatch) {
                    Text(
                        "Passwords do not match",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty() && password == confirm,
            ) { Text("Set Password") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PasswordEnterDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Backup Password") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty(),
            ) { Text("Decrypt") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Preset chat-bubble colors. All chosen to keep white message text readable. */
private val chatBubbleSwatches: List<Long> = listOf(
    0xFF1A56DB, 0xFF15803D, 0xFF7C3AED, 0xFF0F766E,
    0xFFC2410C, 0xFFBE185D, 0xFF475569, 0xFFB91C1C,
)

/**
 * Color picker for the user's chat bubbles: a FlowRow of [chatBubbleSwatches]; the active swatch
 * is ringed and shows a Check. Inserted after the Usage section.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatAppearanceSection(
    selectedColor: Long,
    onSelectColor: (Long) -> Unit,
) {
    Text(
        "Pick the color for your chat bubbles and send button.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        chatBubbleSwatches.forEach { argb ->
            val selected = argb == selectedColor
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .clickable { onSelectColor(argb) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Per-provider usage list: app-tracked prompt/completion/total tokens, plus a balance line for
 * providers that expose one (Loaded/Loading/Error) or a "no quota API" note otherwise. Each row has
 * a Reset; the header has a Refresh action.
 */
@Composable
private fun UsageSection(
    usage: List<ProviderUsageUi>,
    onRefresh: () -> Unit,
    onReset: (AiProvider) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Tokens are counted by this app since install. A live balance is shown for providers that offer one (OpenRouter, DeepSeek).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh usage")
        }
    }
    if (usage.isEmpty()) {
        Text(
            "No AI providers configured yet — add an API key above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    usage.forEach { row ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.provider.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onReset(row.provider) }) { Text("Reset") }
            }
            Text(
                "Tokens: ${"%,d".format(row.tokens.promptTokens)} in · " +
                    "${"%,d".format(row.tokens.completionTokens)} out · " +
                    "${"%,d".format(row.tokens.totalTokens)} total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val b = row.balance) {
                is BalanceUiState.None -> Text(
                    "No quota API — app-tracked tokens only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is BalanceUiState.Loading -> Text(
                    "Loading balance…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is BalanceUiState.Loaded -> {
                    val bal = b.balance
                    val text = when {
                        bal.used != null && bal.limit != null ->
                            "${bal.label}: ${bal.currency}${"%.2f".format(bal.used)} of ${bal.currency}${"%.2f".format(bal.limit)} used"
                        bal.limit != null ->
                            "${bal.label}: ${bal.currency}${"%.2f".format(bal.limit)}"
                        else -> bal.label
                    }
                    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                is BalanceUiState.Error -> Text(
                    "Balance unavailable: ${b.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

