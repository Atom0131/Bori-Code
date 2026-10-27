# CLAUDE.md — Onboarding for Claude (read me first)

This file orients an AI assistant (or new contributor) working on **Bori - Code**.
Read this, then `README.md`, then the deep dives in `docs/`.

## What this project is
Bori - Code is an **on-device Android IDE**: it compiles Android projects into
**signed APKs entirely on the phone — no Gradle at runtime** — and ships a
**multi-provider AI coding assistant** (Claude, OpenAI, Gemini, Kimi, Kimi Code,
DeepSeek, OpenRouter, a user-run "Local server" (Ollama / LM Studio), plus offline
on-device Gemma 4 E2B/E4B via LiteRT-LM).

- applicationId: `com.bori.code`  (namespace `com.bori.app`); see the flavor table below
- **Formerly Mimir Codebot** (renamed 2026-10-25; new app ID `com.bori.code` 2026-10-26, so it's a
  new app on devices and on Play). Data: `bori.db`, `bori_keyvault`, projects in `files/Bori`. Backups
  have the `BCBK` header; the old `MMBK` (Mimir) ones still import. The keystore alias inside
  `bori-code-release.jks` is still `mimir`. That's only a name in the key file.
- versionName `4.2.0`, versionCode `8`
- minSdk 26, targetSdk 35, compileSdk 35 (Android 8.0 → 15), JVM target 11
- ~160 Kotlin files across 6 Gradle modules. UI is 100% Jetpack Compose. DI is Hilt.

## Module map
| Module | Path | Responsibility |
|---|---|---|
| `:app` | `app/` | Compose UI, screens+ViewModels, navigation, data layer, AI tools/agent loop (77 files) |
| `:core:build` | `core/build/` | **The on-device build pipeline** — AAPT2, ECJ, patched kotlinc, D8/R8, packaging, signing (FRAGILE) |
| `:core:ai` | `core/ai/` | Provider clients, ModelCatalog, ModelRouter, ContextManager, local Gemma, tools |
| `:core:project` | `core/project/` | Project model, scaffold/templates, Room DB, JGit, RepoMapper |
| `:core:editor` | `core/editor/` | Sora Editor bindings, syntax highlight, diagnostics, completion, symbol extraction |
| `:core:security` | `core/security/` | `KeyVault` (EncryptedSharedPreferences), `AiProvider` enum |
| `:core:linux` | `core/linux/` | **Full flavor only.** proot launcher, distro installer, guest policy, Claude Code / Antigravity chat clients (`cli/`) |
| `:core:terminal` | `core/terminal/` | Vendored Termux terminal-emulator / terminal-view (Apache-2.0, see its NOTICE) |

Dependency direction: `app → core:{ai,build,editor,project,security,terminal}` (+ `core:linux`
in the full flavor only); `core:ai → core:security, core:editor`; `core:build → core:project`;
`core:linux → core:{ai,project,security}`.

## ⚠️ HARD RULES — read before building or editing

1. **NEVER run `gradlew clean`.** The build module bundles a *patched*
   `patched_kotlinc_embeddable.jar` (Kotlin 2.0.21 patched for Android/ART) plus
   patched ECJ/d8/aapt2 assets, produced by the `copyBuildTools` task. `clean`
   destroys them and **breaks every build** until manually regenerated. See
   `docs/BUILD-PIPELINE.md`.
2. **Always run builds in the FOREGROUND** (never background) so you see failures.
3. **Always build BOTH variants together:**
   `gradlew.bat :app:assembleDebug :app:bundleRelease`
   This builds both flavors (see "Two flavors" below). Only `playRelease`'s AAB goes to Play;
   the `full` builds go to GitHub, and `fullDebug` is the dev build on test devices.
4. **Do NOT touch `core/build/` pipeline files for UI/feature work.** That code is
   load-bearing and fragile. If you must edit it, **re-verify the imports in
   `BuildEngine.kt` and `steps/JavaCompileStep.kt`** afterward.
5. **Model IDs live in ONE place:** `core/ai/.../model/ModelCatalog.kt` is the single
   source of truth (defaults, free-tier flags, output caps, picker notes). The
   pickers, `ModelRouter`, and ViewModels all read from it — never hardcode model IDs
   elsewhere.

## Build & run
```
# From the project root "Bori - Code":
gradlew.bat :app:assembleDebug :app:bundleRelease    # Windows
./gradlew  :app:assembleDebug :app:bundleRelease      # Linux/macOS
```
On Linux, `gradle.properties` pins a Windows `org.gradle.java.home`, so override it
and use a JDK 21 (Gradle 8.11.1 can't run on newer JDKs):
`./gradlew -Dorg.gradle.java.home=$JAVA_HOME :app:assembleDebug :app:bundleRelease`

`copyBuildTools` is pinned to platform 35 + build-tools 37.0.0 and has declared
inputs/outputs, so normal builds report it UP-TO-DATE; it regenerates the bundled
assets only when its inputs change.

**Two flavors** (dimension `dist`; the switch is `HAS_LINUX` in each flavor's `flavor/LinuxFlavor.kt`). Play doesn't allow the Linux environment
(it downloads and runs native code), so only `full` has it.

| Variant | App ID | What it's for |
|---|---|---|
| playRelease | `com.bori.code` | Play Store AAB. No Linux, no CLI providers, no proot |
| fullRelease | `com.bori.code.full` | GitHub Releases APK, with Linux (versionName `-full`) |
| fullDebug | `com.bori.code.debug` | **the dev build on the emulator and the Fold** |
| playDebug | `com.bori.code.play.debug` | checking the Play build side by side |

- IDs are set in `androidComponents.onVariants` (`app/build.gradle.kts`).
- **No Linux code ships in Play.** `:core:linux` (with the Claude Code / Antigravity clients in
  `core/linux/.../cli`) is a `fullImplementation` dependency. The Linux screens, `LinuxInstallService`,
  `ProotShells` and proot's `jniLibs` live in `app/src/full`.
- Shared code reaches Linux only through `flavor/LinuxFlavor.kt`, which exists in both `src/full` and
  `src/play` with the same functions (`startLinuxJobs`, `LinuxMoreRow`, `linuxRoute`, and the
  `LinuxShells` binding); the Play copies do nothing. ModelRouter gets CLI clients from a Hilt set
  (`CliProviderClient`), which is empty in Play.
- Shared code checks `HAS_LINUX` for the terminal's "Set up Linux…" item (it navigates to the
  `linux` route, which only the full flavor registers), the chat hint, About's Linux notices, and
  ignoring a saved CLI provider in prefs. In Play a Linux terminal tab falls back to Android's shell.

Outputs:
- Debug APKs → `app/build/outputs/apk/{full,play}/debug/app-{full,play}-debug.apk`
- Release AABs → `app/build/outputs/bundle/{play,full}Release/app-{play,full}-release.aab`
  (Play gets `playRelease`)

Toolchain (from `gradle/libs.versions.toml`): AGP 8.7.3, Kotlin 2.0.21 (build),
Compose BOM 2024.12.01, Hilt 2.57, Room 2.7.0. The *bundled on-device* compilers are
separate from these: Kotlin 2.0.21 (`kotlin-compiler-asset`, patched for ART by `copyBuildTools`)
and ECJ 3.32 (Java up to 17, patched for Android 8+ by the `patchEcj` task).

## Where things live (quick index)
- AI request flow: `core/ai/.../context/ContextManager.kt` → `router/ModelRouter.kt`
  → `provider/*Client.kt` → `Flow<ChatChunk>`.
- AI tools / agent loop: `app/.../tools/ToolDefinitions.kt`, `tools/ToolExecutor.kt`,
  agent loop in `app/.../ui/chat/ChatViewModel.kt`.
- Build pipeline: `core/build/.../BuildEngine.kt` + `core/build/.../steps/*.kt`.
- Navigation/shell: `app/.../ui/NavHost.kt`. Tabs Chat · Code · Terminal · Projects · More (Settings, Linux,
  Logs, About live under More; Git opens from Code). Bottom bar on phones/cover screen, rail when wider;
  Chat, Code and Terminal live in one `ui/workspace/WorkspaceScreen` composed outside the NavHost and
  never rebuilt (their routes are empty): unfolded it's Chat beside Code or Terminal, split at the hinge;
  on a phone the tab maximizes one side. Folding opens the side last touched (fold continuity); hidden
  panes read `LocalPaneVisible`. Open project, editor tabs and terminal split persist in
  `data/prefs/SessionStateStore`. Chat/Code/Terminal/Projects ViewModels are activity-scoped.
  `ui/adaptive/WindowLayout` gives the width class and hinge; screen Scaffolds use `ShellContentInsets`.
- Terminal panes: `terminal/PaneLayout.kt` + `TerminalSessionManager.layout` (one session per pane).
- Preferences: `app/.../data/prefs/UserPreferencesRepository.kt` (auto-sanitizes
  stale model IDs; also caches each provider's live model list from Settings →
  "Refresh models").
- Agent tool calls: every call in an assistant turn is queued and answered; results go
  back together in one message (`toolBatch` / `processNextToolCall` in ChatViewModel).
- Secrets: `core/security/.../KeyVault.kt`.

## Documentation set
- `README.md` — what the app is / does, features, tech stack.
- `docs/ARCHITECTURE.md` — modules, data flow, the two streaming spines.
- `docs/BUILD-PIPELINE.md` — the on-device compile pipeline in depth (+ Kotlin/ECJ fixes).
- `docs/AI-SUBSYSTEM.md` — providers, routing, models, tools, per-model facts.
- `docs/FEATURES.md` — user-facing feature map (screen + ViewModel per area).
- `docs/CHANGE-NOTES-2026-06.md` — recent feature changelog.

Legacy: `KOTLIN_PIPELINE_FIXES.txt` (folded into BUILD-PIPELINE.md).

Session handoffs live in `ref_notes/` (start with `current-state.md`). It's kept on the maintainer's
machine and isn't in the public repo (see `.gitignore`).
