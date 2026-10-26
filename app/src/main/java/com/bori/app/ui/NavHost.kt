package com.bori.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bori.app.ui.onboarding.OnboardingScreen
import com.bori.app.ui.onboarding.OnboardingViewModel
import com.bori.app.ui.tutorial.TutorialOverlay
import com.bori.app.ui.tutorial.TutorialViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.bori.app.ui.adaptive.AppChrome
import com.bori.app.ui.adaptive.LocalAppChrome
import com.bori.app.ui.adaptive.LocalWindowLayout
import com.bori.app.ui.adaptive.rememberWindowLayout
import com.bori.app.ui.more.MoreScreen
import com.bori.app.ui.workspace.Maximized
import com.bori.app.ui.workspace.WorkspacePane
import com.bori.app.ui.workspace.WorkspaceSide
import com.bori.app.ui.workspace.WorkspaceScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import com.bori.app.ui.theme.CodeTypography
import com.bori.app.ui.settings.AboutScreen
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bori.app.ui.chat.ChatScreen
import com.bori.app.ui.editor.EditorScreen
import com.bori.app.ui.git.GitScreen
import com.bori.app.ui.logs.LogsScreen
import com.bori.app.ui.projects.ProjectsScreen
import com.bori.app.ui.settings.SettingsScreen
import com.bori.app.ui.terminal.TerminalScreen
import com.bori.app.flavor.linuxRoute
import com.bori.core.ai.suggestion.InlineSuggestionEngine
import com.bori.core.editor.EditorController

@Composable
private fun PermissionGate(
    icon: ImageVector,
    title: String,
    description: String,
    buttonLabel: String,
    onGrant: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onGrant) {
                Text(buttonLabel)
            }
        }
    }
}

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val navTabs = listOf(
    NavTab("chat",     "Chat",     Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    NavTab("editor",   "Code",     Icons.Filled.Code,              Icons.Outlined.Code),
    NavTab("terminal", "Terminal", Icons.Filled.Terminal,          Icons.Outlined.Terminal),
    NavTab("projects", "Projects", Icons.Filled.Folder,            Icons.Outlined.Folder),
    NavTab("more",     "More",     Icons.Filled.MoreHoriz,         Icons.Outlined.MoreHoriz),
)

/** The tab a pushed screen belongs to, so its tab stays highlighted. */
/** Tabs that are the two sides of the unfolded workspace. */
private val WORKSPACE_ROUTES = setOf("chat", "editor", "terminal")

private fun tabOf(route: String?): String? = when (route) {
    "git" -> "editor"
    "settings", "linux", "logs", "about" -> "more"
    else -> route
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoriNavHost(
    editorController: EditorController,
    suggestionEngine: InlineSuggestionEngine,
) {
    val context = LocalContext.current

    var hasInstall by remember {
        mutableStateOf(context.packageManager.canRequestPackageInstalls())
    }

    // Re-check permission whenever the user returns from Settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasInstall = context.packageManager.canRequestPackageInstalls()
    }

    if (!hasInstall) {
        PermissionGate(
            icon = Icons.Filled.GetApp,
            title = "Install Permission Required",
            description = "Bori Code needs permission to install APKs so you can run the apps you build directly on this device.",
            buttonLabel = "Grant Permission",
            onGrant = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
        return
    }

    val onboardingVm: OnboardingViewModel = hiltViewModel()
    val hasSeenOnboarding by onboardingVm.hasSeenOnboarding.collectAsStateWithLifecycle()

    if (hasSeenOnboarding == null) return // DataStore loading — avoid flicker
    if (hasSeenOnboarding == false) {
        OnboardingScreen(onFinish = { onboardingVm.markComplete() })
        return
    }

    val tutorialVm: TutorialViewModel = hiltViewModel()
    val isTutorialActive by tutorialVm.isActive.collectAsStateWithLifecycle()
    val hasDismissedTutorial by tutorialVm.hasDismissedTutorial.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = tabOf(currentRoute)

    // Chat, Code, Terminal and Projects share their ViewModels through the activity, so a chat
    // keeps streaming across tab switches and folding, and the editor's "Ask" reaches the chat.
    val activityOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val shared: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(LocalViewModelStoreOwner provides activityOwner) { content() }
    }

    val windowLayout = rememberWindowLayout()
    val appChrome = remember { AppChrome() }
    val fullScreen by appChrome.fullScreen
    val terminalKeys by appChrome.terminalKeys

    // The unfolded workspace: which pane sits next to Chat, and whether one side fills the screen.
    var workspacePane by rememberSaveable { mutableStateOf(WorkspacePane.TERMINAL) }
    var workspaceMax by rememberSaveable { mutableStateOf(Maximized.NONE) }
    var workspaceFraction by rememberSaveable { mutableStateOf<Float?>(null) }
    // The side last touched: folding shows it on the cover screen.
    var lastSide by rememberSaveable { mutableStateOf(WorkspaceSide.CHAT) }
    val workspace = windowLayout.isExpanded
    val onWorkspaceRoute = currentRoute == null || currentRoute in WORKSPACE_ROUTES

    fun goToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** A tab tap: unfolded, Chat, Code and Terminal are the two sides of the workspace. */
    fun onTab(route: String) {
        if (workspace && route in WORKSPACE_ROUTES) {
            when (route) {
                "chat" -> {
                    if (workspaceMax == Maximized.PANE) workspaceMax = Maximized.NONE
                    lastSide = WorkspaceSide.CHAT
                }
                else -> {
                    workspacePane = if (route == "editor") WorkspacePane.EDITOR else WorkspacePane.TERMINAL
                    if (workspaceMax == Maximized.CHAT) workspaceMax = Maximized.NONE
                    lastSide = WorkspaceSide.PANE
                }
            }
            goToTab("chat")
        } else {
            goToTab(route)
        }
    }

    // Continuity across the fold. Unfolding on Code or Terminal opens the workspace with it beside
    // the chat. Folding opens the side you were last using (or the one filling the screen), so the
    // cover screen picks up exactly where the inner screen left off.
    var wasWorkspace by remember { mutableStateOf(workspace) }
    LaunchedEffect(workspace, currentRoute) {
        val justFolded = wasWorkspace && !workspace
        wasWorkspace = workspace
        if (workspace) {
            if (currentRoute == "editor" || currentRoute == "terminal") {
                workspacePane = if (currentRoute == "editor") WorkspacePane.EDITOR else WorkspacePane.TERMINAL
                workspaceMax = Maximized.NONE
                lastSide = WorkspaceSide.PANE
                goToTab("chat")
            }
        } else if (justFolded && currentRoute == "chat") {
            val side = when (workspaceMax) {
                Maximized.PANE -> WorkspaceSide.PANE
                Maximized.CHAT -> WorkspaceSide.CHAT
                Maximized.NONE -> lastSide
            }
            workspaceMax = Maximized.NONE
            if (side == WorkspaceSide.PANE) goToTab(if (workspacePane == WorkspacePane.EDITOR) "editor" else "terminal")
        } else when (currentRoute) {
            // On a phone the tab is the side in use; remember it for the next unfold.
            "chat" -> lastSide = WorkspaceSide.CHAT
            "editor", "terminal" -> {
                workspacePane = if (currentRoute == "editor") WorkspacePane.EDITOR else WorkspacePane.TERMINAL
                lastSide = WorkspaceSide.PANE
            }
        }
    }

    fun isSelected(route: String): Boolean =
        if (workspace && currentRoute == "chat") when (route) {
            "chat" -> workspaceMax != Maximized.PANE
            "editor" -> workspacePane == WorkspacePane.EDITOR && workspaceMax != Maximized.CHAT
            "terminal" -> workspacePane == WorkspacePane.TERMINAL && workspaceMax != Maximized.CHAT
            else -> false
        } else currentTab == route

    val tutorial: @Composable () -> Unit = {
        if (isTutorialActive) {
            TutorialOverlay(
                viewModel = tutorialVm,
                onGoToChat = {
                    if (workspaceMax == Maximized.PANE) workspaceMax = Maximized.NONE
                    if (currentRoute != "chat") goToTab("chat")
                },
            )
        }
    }

    // The editor's file tree and panels read as code.
    val editorScreen: @Composable () -> Unit = {
        CodeTypography {
            EditorScreen(
                editorController = editorController,
                suggestionEngine = suggestionEngine,
                onViewLogs = { navController.navigate("logs") },
                onOpenTerminal = { onTab("terminal") },
                onOpenGit = { navController.navigate("git") },
                onNavigateToChat = { onTab("chat") },
            )
        }
    }
    val terminalScreen: @Composable () -> Unit = {
        TerminalScreen(onBack = null, onOpenLinuxSetup = { navController.navigate("linux") })
    }

    // Chat, Code and Terminal live outside the NavHost, in one workspace that's never rebuilt;
    // their routes are empty and only say which side shows. Other screens draw over it.
    val navHost: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            // On a phone or the cover screen, the tab picks the one side that fills the screen.
            val phonePane = when (currentRoute) {
                "editor" -> WorkspacePane.EDITOR
                "terminal" -> WorkspacePane.TERMINAL
                else -> workspacePane
            }
            val phoneMax = if (currentRoute == "editor" || currentRoute == "terminal") Maximized.PANE else Maximized.CHAT
            shared {
                WorkspaceScreen(
                    pane = if (workspace) workspacePane else phonePane,
                    onPaneChange = { workspacePane = it },
                    // A full-screen terminal takes the whole workspace.
                    maximized = when {
                        fullScreen -> Maximized.PANE
                        workspace -> workspaceMax
                        else -> phoneMax
                    },
                    onMaximizedChange = { workspaceMax = it },
                    showHeader = workspace && !fullScreen,
                    fraction = workspaceFraction,
                    onFractionChange = { workspaceFraction = it },
                    visible = onWorkspaceRoute,
                    onSideTouched = { if (workspace) lastSide = it },
                    chat = {
                        ChatScreen(
                            showTutorialCard = hasDismissedTutorial == false,
                            onStartTutorial = { tutorialVm.startTutorial() },
                            onDismissTutorial = { tutorialVm.dismiss() },
                        )
                    },
                    editor = { editorScreen() },
                    terminal = { terminalScreen() },
                )
            }
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.fillMaxSize(),
        ) {
            composable("chat") {}
            composable("editor") {}
            composable("terminal") {}
            composable("projects") {
                shared {
                    ProjectsScreen(
                        onOpenProject = { onTab("editor") },
                        onViewLogs = { navController.navigate("logs") },
                    )
                }
            }
            composable("more") {
                MoreScreen(
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenLinux = { navController.navigate("linux") },
                    onOpenLogs = { navController.navigate("logs") },
                    onOpenAbout = { navController.navigate("about") },
                )
            }
            composable("git") {
                GitScreen(onBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            linuxRoute(onBack = { navController.popBackStack() }, onOpenTerminal = { onTab("terminal") })
            composable("logs") {
                LogsScreen(onBack = { navController.popBackStack() })
            }
            composable("about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
        }
    }

    CompositionLocalProvider(LocalWindowLayout provides windowLayout, LocalAppChrome provides appChrome) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
        ) { innerPadding ->
            // One tree for every width, so folding never rebuilds the screens below: only the
            // rail (unfolded) or the bottom bar (phone, cover screen) comes and goes around them.
            val compact = windowLayout.isCompact
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (!compact && !fullScreen) NavigationRail {
                    Spacer(Modifier.weight(1f))
                    navTabs.forEach { tab ->
                        NavigationRailItem(
                            selected = isSelected(tab.route),
                            onClick = { onTab(tab.route) },
                            icon = { TabIcon(tab, isSelected(tab.route)) },
                            label = { Text(tab.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .then(
                            if (compact) Modifier
                            else Modifier
                                .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                                // No bottom bar here to sit above the gesture bar; screens use WindowInsets(0).
                                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                        ),
                ) {
                    navHost(Modifier.weight(1f).fillMaxSize())
                    if (!compact) {
                        tutorial()
                    } else if (terminalKeys && !fullScreen) {
                        // The Terminal's keyboard replaces the bar; keep it clear of the gesture bar.
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    } else if (!fullScreen) {
                        // Bottom nav bar. Lives in plain content (not Scaffold.bottomBar) and collapses
                        // its own height from the bottom in lockstep with the IME inset — read in the
                        // measure pass, exactly like ChatScreen's imePadding(). This keeps the input bar
                        // and the nav bar frame-synced: no hop, no gap.
                        CollapsingBottomBar {
                            tutorial()
                            NavigationBar {
                                navTabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = isSelected(tab.route),
                                        onClick = { onTab(tab.route) },
                                        icon = { TabIcon(tab, isSelected(tab.route)) },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(tab: NavTab, selected: Boolean) {
    Icon(
        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
        contentDescription = tab.label,
    )
}

/**
 * A bottom bar that collapses from the bottom by the live IME height. The keyboard inset is
 * read inside the measure lambda (the same mechanism `Modifier.imePadding()` uses), so the
 * reported height shrinks frame-for-frame in sync with the chat input rising — eliminating the
 * boolean-toggle "hop" and the reserved-height "gap". When the keyboard fully covers the bar it
 * reports height 0; `clipToBounds()` hides the part that slides below the new bottom edge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollapsingBottomBar(content: @Composable () -> Unit) {
    // Capture the IME insets in composition; getBottom() is then read inside the measure pass,
    // where snapshot reads re-trigger measurement each animation frame (no recomposition).
    val imeInsets = WindowInsets.ime
    Column(
        modifier = Modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val reduce = imeInsets.getBottom(this).coerceAtMost(placeable.height)
                layout(placeable.width, placeable.height - reduce) {
                    placeable.place(0, 0)
                }
            },
    ) {
        content()
    }
}
