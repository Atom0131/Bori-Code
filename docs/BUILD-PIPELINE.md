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
tail logs live. Steps run in this order:

| # | Step | Class | Path |
|---|---|---|---|
| 1 | Extract bundled toolchain to filesDir (kotlinc, d8, aapt2, stubs); clear IC cache if re-extracted | `ToolsManager` | `tools/ToolsManager.kt` |
| 2 | Resolve `implementation`/`annotationProcessor`/`kapt` deps from Maven Central + Google Maven; merge into a classpath | `MavenResolver` | `steps/MavenResolver.kt` |
| 3 | *(optional)* If the project uses Compose, resolve + runtime-dex the Compose compiler plugin and inject it into the kotlinc classloader | `PluginDexer` | `steps/PluginDexer.kt` |
| 4 | AAPT2 **compile** (`.xml/.png → .flat`) then **link** (merge user+lib flats, generate `R.java`, emit `res.apk`); patch manifest (strip `@mipmap`, `tools:` attrs, backup attrs) and theme parents | `AaptStep` | `steps/AaptStep.kt` |
| 5 | Compile `R.java` with **ECJ** (whenever resources changed) | `JavaCompileStep` | `steps/JavaCompileStep.kt` |
| 6 | Compile Kotlin with the **patched kotlinc 1.8.22 daemon** (32 MB stack; full compile when kapt/Compose present, else incremental; configurable timeout + heartbeat) | `KotlinCompileStep` | `steps/KotlinCompileStep.kt` |
| 7 | Compile Java sources (incl. kapt-generated) with **ECJ**; run annotation processors | `JavaCompileStep` | `steps/JavaCompileStep.kt` |
| 8 | Dex: **D8** for debug; **R8** for release (shrink + obfuscate + `mapping.txt`); multidex (`classes.dex`, `classes2.dex`, …); 60 s timeout + heartbeat | `DexStep` | `steps/DexStep.kt` |
| 9 | Package `res.apk` + all DEX (+ optional library DEX) into a 4-byte-aligned ZIP | `ApkPackager` | `steps/ApkPackager.kt` |
| 10 | Sign — debug: auto-generated `debug.keystore` (RSA-2048); release: user keystore (JKS/PKCS12/BKS). Both: **v1 (JAR) + v2 (APK)** | `ApkSigner` | `steps/ApkSigner.kt` |

Supporting pieces: **`ModuleParser.kt`** (multi-module projects — topological sort,
shared `classesDir`, AAPT2 link across modules), **`ManifestMerger.kt`**,
**`BuildCache.kt`** (incremental-compile cache).

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
