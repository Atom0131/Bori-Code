# Feature Map

User-facing features of Bori - Code, with the key files per area. All paths are
under `app/src/main/java/com/bori/app/`.

## App shell & navigation
- `BoriApp.kt` — `@HiltAndroidApp`; installs the hidden-API bypass (lets the bundled
  Kotlin compiler run), observes local-Gemma inference to start/stop the foreground
  service, migrates projects to app-specific storage on first launch.
- `MainActivity.kt` — `@AndroidEntryPoint`; sets `BoriTheme` + `BoriNavHost`,
  edge-to-edge.
- `ui/NavHost.kt` — nav graph + **5 bottom tabs: Chat, Build, Git, Editor, Settings**.
  Features an IME-aware **`CollapsingBottomBar`** (nav bar collapses frame-synced with
  the keyboard), an **onboarding gate** (first launch), and a **tutorial overlay**.

## Chat & AI assistant — `ui/chat/`
- `ChatScreen.kt`, `ChatViewModel.kt`
- Streaming chat with the AI agent; model picker (reads `ModelCatalog`).
- **Plan Mode** (AI proposes a plan via `write_plan`, user approves before edits) and
  **Ask Mode** (`ask_questions` multi-question card).
- **Attachments**: image vision, document text, whole-project context (chips above the
  input bar; caret menu).
- **Conversation compaction** (`compact_conversation`) to free context on long chats.
- Drives the agent loop + `ToolExecutor` (see [AI-SUBSYSTEM.md](AI-SUBSYSTEM.md)).

## Code editor — `ui/editor/`
- `EditorScreen.kt`, `EditorViewModel.kt` — VS Code-style layout.
- Panels/helpers: `ActivityBar.kt`, `EditorTabBar.kt`, `BottomPanel.kt`,
  `FileTreePanel.kt` (+ `FileNode.kt`), `SymbolOutlinePanel.kt`,
  `DependenciesPanel.kt`, `ShortcutBar.kt`, `FindInFilesSheet.kt` (+ ViewModel),
  `QuickOpenDialog.kt`, `LayoutPreviewSheet.kt`.
- **Live diagnostics**: `data/build/LiveDiagnosticsController.kt` debounces a real
  on-device compile (~3 s after last keystroke) → `DiagnosticsStateHolder` → error
  underlines + Problems panel.
- Symbol outline (Kotlin/Java extraction), find-in-files, quick-open, dependency view,
  XML layout preview.

## Projects & build — `ui/projects/`, `ui/build/`
- `ProjectsScreen.kt`, `ProjectsViewModel.kt` — list / create / import projects;
  `NewProjectDialog.kt` (name, package, minSdk, Kotlin/Java template),
  `ImportProjectSheet.kt` (ZIP import). Active project tracked in
  `data/project/ProjectStateHolder.kt`.
- `BuildSheet.kt`, `BuildViewModel.kt` — trigger **debug/release** builds, live log
  tail, install/launch APK, variant selector, custom keystore. `ApkInstallUtils.kt`
  installs a prebuilt APK without rebuilding. Runs a foreground service during builds.

## Settings — `ui/settings/`
- `SettingsScreen.kt`, `SettingsViewModel.kt` — AI provider/key config (one provider
  at a time), independent chat vs completion model selection, **per-provider token
  usage** (`data/usage/UsageRepository.kt`), performance profiles (work-turn budget),
  keystore management, backup/restore.
- `LocalModelSection.kt` + `LocalModelViewModel.kt` — on-device **Gemma** download UI
  (HuggingFace token, progress polling, storage usage).

## Backup & encryption — `data/backup/`
- `BackupManager.kt`, `BackupData.kt`, orchestrated by `ui/settings/BackupViewModel.kt`.
- Encrypted **`.mbackup`**: AES-256-GCM + PBKDF2 (120k iterations); magic-byte
  validation; exports API keys, settings, chat sessions/messages, and project files;
  imports with duplicate-skip and wrong-password detection.

## Offline model (Gemma) — `core/ai/local/` + `service/`
- `LocalGemmaClient.kt` (LiteRT-LM, GPU→CPU fallback), `ModelDownloadManager.kt`,
  `ConnectivityChecker.kt`.
- `service/ChatForegroundService.kt` — persistent notification during long inference.
- `ModelRouter` transparently falls back to Gemma when offline.

## Tutorial & onboarding
- `ui/tutorial/TutorialScreen.kt` + `TutorialViewModel.kt` — scripted, **no-API-key**
  guided build of a Calculator app (uses on-device Gemma); state machine
  WELCOME → CHECK_MODEL → CREATE_PROJECT → GENERATING_CODE → GENERATING_LAYOUT →
  BUILD_APP → DONE.
- `ui/onboarding/OnboardingScreen.kt` + `OnboardingViewModel.kt` — 3-page pager on
  first launch (`hasSeenOnboarding` in DataStore).

## Git, Logs, Terminal
- `ui/git/` — `GitScreen.kt` + `GitViewModel.kt` (wraps `core:project` JGit).
- `ui/logs/` — `LogsScreen.kt` + `LogsViewModel.kt` (build/compiler output viewer).
- `ui/terminal/` — `TerminalScreen.kt` + `TerminalViewModel.kt` (on-device shell), and
  `TerminalKeyboard.kt`: Bori's compact terminal keyboard (suggestions, swipe typing, arrows by Enter,
  F1-F12, Del, split in two halves when 600dp or wider), used instead of the phone's unless the user switches with the globe key, which opens a
  typing box with the phone keyboard's own prediction. Suggestions and swipes: `terminal/WordPredictor.kt`.

## Theme — `ui/theme/`
- `Theme.kt`, `Color.kt`, `Type.kt` — **Obsidian Flux** design system: static
  Material 3 light + dark tokens (no dynamic color), **Inter** (UI) + **JetBrains
  Mono** (code).

## Data layer (supporting)
- `data/prefs/UserPreferencesRepository.kt` — DataStore: provider/model selection,
  perf profile, work-turn budget, onboarding/tutorial flags, chat bubble color;
  auto-sanitizes stale model IDs.
- `data/db/` — Room chat history (`AppDatabase.kt`, `ChatEntities.kt`, `ChatDao.kt`).
- `data/build/BuildStateHolder.kt`, `DiagnosticsStateHolder.kt`, `KeystoreRepository.kt`.
- `di/AppModule.kt` — Hilt: Room DB, DAOs, DataStore.
