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
| 1 | Extract bundled toolchain to filesDir (kotlinc, its plugins, KSP, d8, aapt2, stubs); clear IC cache if re-extracted | `ToolsManager` | `tools/ToolsManager.kt` |
| 2 | Resolve dependencies from Maven Central, Google Maven and the repositories in settings.gradle (JitPack…): parent POMs, `${property}` versions, `<dependencyManagement>` and imported BOMs, `platform(...)` BOMs from the build file, exclusions, same-group version alignment, highest version wins. Local `files(...)` / `fileTree(...)` jars and AARs join them. Unpack each AAR once into `aar-cache/` (classes, res compiled by AAPT2, manifest, assets, jni, proguard.txt) | `MavenResolver`, `UserLibraryMerger` | `steps/MavenResolver.kt`, `steps/UserLibraryMerger.kt` |
| 3 | Bundled AndroidX or Maven: the bundled set (appcompat, material, lifecycle… in `androidx-versions.txt`) is used unless the project needs a newer version of one of its libraries; then the whole graph comes from Maven, so versions never mix | `BuildEngine` | `BuildEngine.kt` |
| 4 | AAPT2 **compile** each module's res, merge library manifests into the app's (components, permissions, `${applicationId}`, `tools:node="remove"`), then **link** everything as overlays (bundled < dependencies < library modules < app), setting versionCode/versionName and the applicationId (`--rename-manifest-package`), and writing R.java plus R8 keep rules. For an app bundle, link again in proto format with the same resource IDs (`--emit-ids` / `--stable-ids`) | `AaptStep`, `ManifestMerger` | `steps/AaptStep.kt`, `steps/ManifestMerger.kt` |
| 5 | Generate `BuildConfig.java` and the View Binding classes (`buildFeatures { viewBinding = true }`), and compile them with R.java using **ECJ** | `BuildEngine`, `ViewBindingGenerator`, `JavaCompileStep` | `steps/ViewBindingGenerator.kt` |
| 6 | *(processors)* **KSP** in the compiler (K1, as KSP1 needs): Room, Hilt/Dagger, Moshi… `kapt(...)` / `annotationProcessor(...)` processors with a KSP version run this way (no javac on Android). Each processor jar is adapted (`ProcessorRewriter`) and dexed once into `processor-dex/` | `KotlinCompileStep`, `ProcessorRewriter` | `steps/KotlinCompileStep.kt` |
| 7 | Compile Kotlin (every module's src/main/java and src/main/kotlin, plus KSP's output) with the **patched kotlinc 2.0.21 (K2)**, with the bundled Compose, kotlinx.serialization and Parcelize plugins as needed. If K2 ever crashes on a device, the compile is retried with K1 and later builds use K1 | `KotlinCompileStep`, `KotlinCompilerDaemon` | `steps/KotlinCompileStep.kt` |
| 8 | Compile Java sources and KSP-generated Java with **ECJ**; then point Hilt entry points at their `Hilt_` classes (the Hilt plugin's transform) | `JavaCompileStep`, `HiltTransform` | `steps/HiltTransform.kt` |
| 9 | Dex. **R8** only when the build type has `isMinifyEnabled`; otherwise **D8** (`--debug` or `--release`) on the app's classes, its dependencies and the Kotlin stdlib separately (the last two cached). With `isCoreLibraryDesugaringEnabled`, D8/R8 use `desugar_jdk_libs` and **L8** dexes the library. With the bundled AndroidX, everything is dexed at min-api ≤ 21 to match its dex | `DexStep` | `steps/DexStep.kt` |
| 10 | Package an APK (res.apk + dex + assets + native libs, stored and 16 KB aligned), or an app bundle (`base/` module + `BundleConfig.pb`) | `ApkPackager`, `BundlePackager` | `steps/ApkPackager.kt`, `steps/BundlePackager.kt` |
| 11 | Sign. APK: **apksig** v1 (when minSdk < 24) + v2 + v3, RSA/EC/DSA keys. App bundle: JAR signature with SHA-256 digests (what Play checks). Debug: auto-generated `debug.keystore`; release: user keystore (PKCS12, JKS via `JksReader`, or BKS) | `ApkSigner` | `steps/ApkSigner.kt` |

Supporting pieces: **`VersionCatalogParser.kt`** (`libs.versions.toml`: libraries, bundles,
versions, plugins), **`BuildCache.kt`** (content-hash cache that lets unchanged steps skip).
D8, R8 and L8 run through their command API (`run(...Command)`), not `main()`, which would
`System.exit` the app on a compile error.

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
| `kotlinc.jar` | Kotlin **2.0.21** compiler (embeddable), patched for ART: `stubs/art-replacements` replaces IntelliJ's `Unsafe` wrapper (its MethodHandle lookups fail on ART), plus the older try/catch patches. FastJarFileSystem avoids the NIO2 `ZipFileSystemProvider` crash. |
| `compose-plugin.jar`, `serialization-plugin.jar`, `parcelize-plugin.jar`, `ksp-plugin.jar` (+ `ksp-api.jar`, `ksp-deps-dex.jar`) | Compiler plugins and KSP, dex plus their original entries (for `-Xplugin`'s service lookup). Bundled, not downloaded: the app may not download code to run itself (Play policy). |
| `kotlinx-coroutines-dex.jar` | Needed by the 2.x compiler at run time. |
| `java-compiler-api-dex.jar` | OpenJDK's javax.lang.model / javax.annotation.processing / javax.tools (JavaPoet in Room/Dagger needs them) and six javax.xml.stream interfaces (the compiler's bundled Aalto StAX parser reads plugin descriptors). `SourceVersion.getLatestSupported()` is patched (it calls `Runtime.version()`). |
| `processor-stubs-dex.jar` | `stubs/processor-stubs`: an sqlite-jdbc stand-in (Room's build-time query check needs a native library; it now warns and skips) and `ProcessorCompat` (JDK 9+ methods processors call). |
| Patched ECJ (Eclipse JDT) | Java compiler; patched with a hidden-API bypass and `ExceptionInInitializerError` recovery for Android 16 / release builds (ProGuard keep rules + per-thread init). |
| `d8.jar` | D8, R8 and L8. |
| AAPT2 + `android-stubs.jar` + `core-lambda-stubs.jar` | Resource compiler and the `android.jar` stubs / desugaring stubs to compile against. |
| `androidx-*` | The bundled AndroidX set: merged classes jar, its dex, compiled resources, packages, manifest entries and `androidx-versions.txt`. |

## BuildEvent streaming model
`BuildEngine.build()` returns `Flow<BuildEvent>`:
- `Started`
- `Log(text, level)` — streamed compiler/tool output (tailed by the Build UI)
- `Failure(message)`
- `Success(apkPath, applicationId)` — an `.apk` (UI offers install/launch) or an `.aab`

## Known issues & fixes (history)
These are the hard-won fixes that keep the toolchain running on ART. Sources:
`KOTLIN_PIPELINE_FIXES.txt`, plus memory notes `project_kotlin_pipeline_fixes` and
`project_ecj_fix`.

- **Kotlin 2.0.21 (K2) on ART** (2026-10-26; 1.8.22 before) — needed: `idea.home.path` set to an
  app folder (PathManager), the `Unsafe` wrapper replacement, the StAX API classes, and
  kotlinx-coroutines. Swing/`java.awt` references are stubbed. A hidden-API bypass lets the
  compiler use `sun.misc.Unsafe`.
- **Compile classpath is the libraries' own jars**, not the merged `user-libs.jar`: the
  serialization plugin reads the runtime's version from its jar manifest.
- **Symbol processors on ART** — KSP loads processors through a `URLClassLoader`, whose parent
  (the compiler's loader) holds the dexed processor classes. Known gaps handled:
  `Runtime.version()` (SourceVersion), `AccessibleObject.trySetAccessible()` (ProcessorRewriter),
  sqlite-jdbc's native library (stub), google-java-format (`dagger.formatGeneratedSource=disabled`).
- **NIO2 zip filesystem crash** — replaced with a `FastJarFileSystem` so kotlinc can
  read jars without the unavailable `ZipFileSystemProvider`.
- **ECJ `ExceptionInInitializerError` on Android 16 / release** — fixed with ProGuard
  keep rules, a per-thread hidden-API bypass, and careful static-initializer ordering.
- **compiler.xml extension points** — the daemon also registers compiler.xml's application
  extension points itself on a kept-alive application environment (1.8.22 couldn't read the
  descriptor without StAX). Without them the compiler crashed on the first error or warning
  ("Missing extension point: org.jetbrains.kotlin.diagnosticSuppressor").
- **Dynamic dex must be read-only** — Android 14+ refuses to load a writable dex, so
  processor dex files are marked read-only.

## ⚠️ Gotchas for contributors
1. **NEVER run `gradlew clean`.** It deletes the patched compiler (and the other generated
   assets) and **breaks every on-device build** until `copyBuildTools`
   regenerates them. This is the #1 way to brick the build.
2. **Always build in the foreground**, and always build **both**
   `:app:assembleDebug` and `:app:bundleRelease` together.
3. **Don't casually edit pipeline files.** This module is load-bearing; small changes
   ripple. After any edit, **re-verify the imports in `BuildEngine.kt` and
   `steps/JavaCompileStep.kt`** (they've regressed before). Do UI/feature work
   elsewhere — never touch `core/build/` for unrelated changes.
4. Release builds emit R8 `mapping.txt`; keep it if you need to deobfuscate crashes.
