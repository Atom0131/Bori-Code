# On-Device Build Pipeline

Bori Code compiles Android projects into **signed APKs entirely on the phone, with no
Gradle at runtime**. The pipeline is the `:core:build` module
(`core/build/src/main/java/com/bori/core/build/`). It is the most complex and most
fragile part of the codebase — **read the warnings at the bottom before editing it.**

## Why this is hard
Android devices ship no JDK, no Gradle, no SDK build-tools. So the app **bundles a
full toolchain as assets** (a patched Kotlin compiler, the Eclipse ECJ Java
compiler, AAPT2, D8/R8, Android stubs) and drives them in-process on ART (the
Android runtime), which is *not* a standard JVM. Several of these tools had to be
**bytecode-patched** to run on ART at all (see "Known issues & fixes").

## Pipeline order
Orchestrated by **`BuildEngine.kt`**, which emits a `Flow<BuildEvent>` so the UI can
tail logs live. A project with one module and one with several go through the same steps:
**`ModuleParser.kt`** reads settings.gradle(.kts) and each module's build file (Kotlin or
Groovy DSL, comments stripped), and the app module plus the library modules it depends on are
built together. Debug and release keep separate outputs in `builds/<project>/{debug,release}/`
(BuildConfig constants are inlined into compiled code, so they can't share classes).

| # | Step | Class | Path |
|---|---|---|---|
| 1 | Extract bundled toolchain to filesDir (kotlinc, d8, aapt2, stubs); clear IC cache if re-extracted | `ToolsManager` | `tools/ToolsManager.kt` |
| 2 | Resolve `implementation`/`api`/`runtimeOnly`, `compileOnly`, `annotationProcessor` and `kapt` deps from Maven Central + Google Maven (highest version wins). Unpack each AAR once into `aar-cache/` (classes, res compiled by AAPT2, manifest, assets, jni, proguard.txt); libraries already in the bundled AndroidX are skipped except for their manifests | `MavenResolver`, `UserLibraryMerger` | `steps/MavenResolver.kt`, `steps/UserLibraryMerger.kt` |
| 3 | *(optional)* If the project uses Compose, resolve + runtime-dex the Compose compiler plugin and inject it into the kotlinc classloader | `PluginDexer` | `steps/PluginDexer.kt` |
| 4 | AAPT2 **compile** each module's res, merge library manifests into the app's (components, permissions, `${applicationId}`, `tools:node="remove"`), then **link** everything as overlays (bundled < dependencies < library modules < app), setting versionCode/versionName and the applicationId (`--rename-manifest-package`), and writing R.java plus R8 keep rules | `AaptStep`, `ManifestMerger` | `steps/AaptStep.kt`, `steps/ManifestMerger.kt` |
| 5 | Generate `BuildConfig.java` (APPLICATION_ID, VERSION_*, DEBUG, BUILD_TYPE, `buildConfigField`s) and compile it with R.java using **ECJ** | `BuildEngine`, `JavaCompileStep` | `steps/JavaCompileStep.kt` |
| 6 | Compile Kotlin (src/main/java and src/main/kotlin of every module) with the **patched kotlinc 1.8.22 daemon** (32 MB stack; full compile when kapt/Compose present, else incremental; configurable timeout + heartbeat) | `KotlinCompileStep` | `steps/KotlinCompileStep.kt` |
| 7 | Compile Java sources (incl. kapt-generated) with **ECJ**; run annotation processors | `JavaCompileStep` | `steps/JavaCompileStep.kt` |
| 8 | Dex. Debug: **D8** on the app's classes, its dependencies and the Kotlin stdlib separately (the last two cached). Release: **R8** shrinks the app and its dependencies; R classes and the stdlib are dexed as they are (the bundled AndroidX, which R8 only sees as classpath, uses them). Everything is dexed at min-api ≤ 21 to match the bundled AndroidX dex | `DexStep` | `steps/DexStep.kt` |
| 9 | Package res.apk + all DEX + assets + native libs (`lib/<abi>/*.so`, stored and 16 KB aligned) into a ZIP streamed to disk | `ApkPackager` | `steps/ApkPackager.kt` |
| 10 | Sign — debug: auto-generated `debug.keystore` (RSA-2048); release: user keystore (PKCS12, JKS via `JksReader`, or BKS). Both: **v1 (JAR) + v2 (APK)** | `ApkSigner` | `steps/ApkSigner.kt` |

Supporting pieces: **`VersionCatalogParser.kt`** (`libs.versions.toml`: libraries, bundles,
versions), **`BuildCache.kt`** (content-hash cache that lets unchanged steps skip).

### Testing the builder
`scripts/test-projects/` has small projects that each exercise part of the pipeline, and debug
builds of Bori Code have an adb-only receiver (`app/src/debug/.../DebugBuildReceiver.kt`) that
builds one from the shell. See `scripts/test-projects/README.md`.

## Bundled / patched toolchain
The `copyBuildTools` task in `core/build/build.gradle.kts` (700+ lines) pre-dexes and
asset-bundles the toolchain and applies bytecode patches at build time, producing —
among others — **`patched_kotlinc_embeddable.jar`**.

| Asset | What/why |
|---|---|
| `patched_kotlinc_embeddable.jar` | Kotlin **1.8.22** compiler, patched for Android/ART. **1.8.22 (not K2/2.x)** because the K2 frontend uses `sun.misc.Unsafe` hidden APIs that fail on ART. Patches include a `FastJarFileSystem` to avoid the NIO2 `ZipFileSystemProvider` crash. |
| Patched ECJ (Eclipse JDT) | Java compiler; patched with a hidden-API bypass and `ExceptionInInitializerError` recovery for Android 16 / release builds (ProGuard keep rules + per-thread init). |
| `d8.jar` | Dexer; also provides R8 for release shrinking/obfuscation. |
| AAPT2 + `android-stubs.jar` + `core-lambda-stubs.jar` | Resource compiler and the `android.jar` stubs / desugaring stubs to compile against. |

## BuildEvent streaming model
`BuildEngine.build()` returns `Flow<BuildEvent>`:
- `Started`
- `Log(text, level)` — streamed compiler/tool output (tailed by the Build UI)
- `Failure(message)`
- `Success(apkPath)` — UI flips to install/launch

## Known issues & fixes (history)
These are the hard-won fixes that keep the toolchain running on ART. Sources:
`KOTLIN_PIPELINE_FIXES.txt`, plus memory notes `project_kotlin_pipeline_fixes` and
`project_ecj_fix`.

- **Kotlin 1.8.22 on ART** — K2 / `sun.misc.Unsafe` hidden-API usage is blocked on
  Android; pinned to 1.8.22 and patched. Swing/`java.awt` and StAX class references
  reachable from the compiler had to be stubbed/avoided because they don't exist on
  Android. A hidden-API bypass is installed at app startup (`BoriApp`) to allow the
  compiler to run under a lower targetSdk policy.
- **NIO2 zip filesystem crash** — replaced with a `FastJarFileSystem` so kotlinc can
  read jars without the unavailable `ZipFileSystemProvider`.
- **ECJ `ExceptionInInitializerError` on Android 16 / release** — fixed with ProGuard
  keep rules, a per-thread hidden-API bypass, and careful static-initializer ordering.
- **compiler.xml extension points** — `copyBuildTools` no-ops
  `PluginManagerCore.registerExtensionPointAndExtensions` (it needs StAX), so the daemon
  registers compiler.xml's application extension points itself on a kept-alive application
  environment. Without them the compiler crashed on the first error or warning
  ("Missing extension point: org.jetbrains.kotlin.diagnosticSuppressor").
- **Dynamic dex must be read-only** — Android 14+ refuses to load a writable dex, so the
  runtime-dexed Compose plugin jar is marked read-only.

## ⚠️ Gotchas for contributors
1. **NEVER run `gradlew clean`.** It deletes `patched_kotlinc_embeddable.jar` (and the
   other generated assets) and **breaks every on-device build** until `copyBuildTools`
   regenerates them. This is the #1 way to brick the build.
2. **Always build in the foreground**, and always build **both**
   `:app:assembleDebug` and `:app:bundleRelease` together.
3. **Don't casually edit pipeline files.** This module is load-bearing; small changes
   ripple. After any edit, **re-verify the imports in `BuildEngine.kt` and
   `steps/JavaCompileStep.kt`** (they've regressed before). Do UI/feature work
   elsewhere — never touch `core/build/` for unrelated changes.
4. Release builds emit R8 `mapping.txt`; keep it if you need to deobfuscate crashes.
