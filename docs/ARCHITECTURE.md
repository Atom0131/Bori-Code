# Architecture

Bori - Code is a 6-module Gradle project. The `:app` module (Jetpack Compose UI +
the AI agent/tool layer) sits on top of five `:core` libraries.

## Module graph
```
                    ┌──────────────────────────────┐
                    │            :app              │  Compose UI, ViewModels,
                    │  ui/ data/ di/ tools/ service/│  navigation, agent tools
                    └───────────────┬──────────────┘
        ┌───────────────┬───────────┼───────────────┬────────────────┐
        ▼               ▼           ▼               ▼                ▼
   :core:ai        :core:build  :core:editor   :core:project   :core:security
   providers,      on-device    Sora editor,   Room DB,        KeyVault,
   router,         build        diagnostics,   scaffold,       AiProvider enum
   models, Gemma   pipeline     completion     templates, git
        │               │                           ▲                ▲
        └── depends on ──┴──────────► core:project ──┘                │
        └── core:ai also depends on core:editor + core:security ──────┘
```

- `:app` depends on all `:core` modules.
- `:core:ai` → `:core:security` (keys), `:core:editor` (context/symbols).
- `:core:build` → `:core:project` (project model, module parsing).
- `:core:security` and `:core:project` are leaf modules.

## The two streaming spines
Everything long-running is modeled as a Kotlin **Flow**, consumed incrementally by
the UI:

1. **Build:** `BuildEngine.build()` → `Flow<BuildEvent>`
   (`Started` / `Log(text, level)` / `Failure(msg)` / `Success(apkPath)`).
   The Build UI tails logs live and flips to install/launch on `Success`.
2. **AI:** `AiProviderClient.chat()` → `Flow<ChatChunk>`
   (`TextDelta`, `ToolCallStart/Delta/End`, `ReasoningDelta`, `Usage`, `Done`, …).
   The chat UI renders tokens as they arrive and drives the agent loop on tool calls.

## AI request flow (end to end)
```
User message (ChatScreen)
  → ChatViewModel                         app/ui/chat/ChatViewModel.kt
  → ContextManager.buildChatRequest()     core/ai/context/ContextManager.kt
        builds system prompt (+ open file, repo map, symbols, project language),
        trims history to the token budget, appends the user message
  → ModelRouter                           core/ai/router/ModelRouter.kt
        picks provider+model from saved prefs / ModelCatalog defaults;
        if offline and local Gemma is ready → transparently route to Gemma
  → AiProviderClient.chat(request)        core/ai/provider/*Client.kt
        provider-specific JSON + SSE streaming
  → Flow<ChatChunk> back to ChatViewModel
        TextDelta → UI; ToolCall* → ToolExecutor → result fed back as next turn
```
The **agent loop** (in `ChatViewModel`) answers exactly **one tool call per turn**,
runs it via `ToolExecutor` (`app/tools/ToolExecutor.kt`), appends the tool result,
and re-invokes the model until it stops requesting tools or the work-turn budget is
hit. Tools are declared in `app/tools/ToolDefinitions.kt`. See
[AI-SUBSYSTEM.md](AI-SUBSYSTEM.md).

## Build flow (end to end)
`BuildEngine` orchestrates ordered steps, each emitting log events:
`ToolsManager` (extract bundled toolchain) → `MavenResolver` (deps) → optional
`PluginDexer` (Compose) → `AaptStep` (compile+link resources) → `JavaCompileStep`
(R.java via ECJ) → `KotlinCompileStep` (daemon, Kotlin 1.8.22) → `JavaCompileStep`
(Java via ECJ) → `DexStep` (D8 debug / R8 release) → `ApkPackager` → `ApkSigner`
(v1+v2). Multi-module projects are topologically ordered by `ModuleParser`. Full
detail in [BUILD-PIPELINE.md](BUILD-PIPELINE.md).

## Data layer (`app/data/`)
| Concern | Type | File |
|---|---|---|
| User prefs (provider/model, perf profile, flags) | DataStore | `data/prefs/UserPreferencesRepository.kt` |
| Chat history | Room | `data/db/AppDatabase.kt`, `ChatEntities.kt`, `ChatDao.kt` |
| Active project | StateFlow singleton | `data/project/ProjectStateHolder.kt` |
| Last build result / diagnostics | Singletons | `data/build/BuildStateHolder.kt`, `DiagnosticsStateHolder.kt` |
| Live compile (debounced) | Controller | `data/build/LiveDiagnosticsController.kt` |
| Token usage | DataStore | `data/usage/UsageRepository.kt` |
| Encrypted backup | AES-256-GCM | `data/backup/BackupManager.kt` |
| Signing keystores | Repo | `data/build/KeystoreRepository.kt` |

## Dependency injection
Hilt throughout. `app/di/AppModule.kt` provides the Room `AppDatabase` + DAOs and
the Preferences `DataStore`; each `:core` module contributes its own Hilt bindings
(provider clients, `ModelRouter`, `KeyVault`, `ContextManager`, etc.) consumed via
constructor injection. `BoriApp` is `@HiltAndroidApp`; activities/ViewModels use
`@AndroidEntryPoint` / `@HiltViewModel`.

## Key cross-cutting patterns
- **Single source of truth** for model metadata: `ModelCatalog.kt`.
- **Transparent offline fallback** to on-device Gemma in `ModelRouter`.
- **Streaming-first** I/O (build + chat + completion all Flow-based).
- **State holders** (`ProjectStateHolder`, `BuildStateHolder`, `DiagnosticsStateHolder`)
  decouple producers (build/compile) from consumers (editor/agent tools).
