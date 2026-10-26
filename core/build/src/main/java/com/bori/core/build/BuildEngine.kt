package com.bori.core.build

import android.content.Context
import android.util.Log
import com.bori.core.build.model.BuildConfig
import com.bori.core.build.model.BuildEvent
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.core.build.model.KeystoreConfig
import com.bori.core.build.model.BuildVariant
import com.bori.core.build.model.ModuleInfo
import com.bori.core.build.model.ModuleType

import com.bori.core.build.steps.AaptStep
import com.bori.core.build.steps.ApkPackager
import com.bori.core.build.steps.ApkSigner
import com.bori.core.build.steps.DexStep
import com.bori.core.build.steps.JavaCompileStep
import com.bori.core.build.steps.KotlinCompileStep
import com.bori.core.build.steps.ManifestMerger
import com.bori.core.build.steps.MavenResolver
import com.bori.core.build.steps.PluginDexer
import com.bori.core.build.steps.UserLibraries
import com.bori.core.build.steps.UserLibraryMerger
import com.bori.core.build.tools.ToolsManager
import com.bori.core.project.model.Project
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuildEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolsManager: ToolsManager,
    private val apkSigner: ApkSigner,
    private val kotlinDaemon: KotlinCompilerDaemon,
) {
    fun build(
        project: Project,
        kotlinTimeoutSeconds: Long = 120L,
        compilerProfile: CompilerPerformanceProfile = CompilerPerformanceProfile.LOW,
        keystoreConfig: KeystoreConfig? = null,
        variant: BuildVariant = BuildVariant.DEBUG,
    ): Flow<BuildEvent> = flow {
        emit(BuildEvent.Started)
        try {
            val projectRoot = File(project.rootPath)
            val modules = detectModules(projectRoot)
            if (modules != null) {
                buildMultiModule(project, modules, kotlinTimeoutSeconds, compilerProfile, keystoreConfig, variant)
                return@flow
            }

            val cfg = makeBuildConfig(project)
            cfg.buildRoot.mkdirs()
            val cache = BuildCache(cfg.buildRoot)

            // Step 1: Extract tools + AndroidX library bundle (always fast — skips if already done)
            emit(BuildEvent.Log("▶ Preparing build tools"))
            val toolsReExtracted = toolsManager.prepare()
            if (toolsReExtracted) {
                // Clear the warm compiler instance — it holds a ClassLoader over the old DEX files.
                kotlinDaemon.clearCache()
                // Clear the Kotlin IC cache — it references class files from the old build and
                // will produce assertion failures or wrong incremental decisions if reused.
                File(cfg.buildRoot, "kotlin-ic-cache").deleteRecursively()
                emit(BuildEvent.Log("Tools re-extracted — Kotlin compiler cache cleared"))
            }

            val runtime = Runtime.getRuntime()
            val maxMb = runtime.maxMemory() / 1_048_576
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
            val availMb = maxMb - usedMb
            emit(BuildEvent.Log("Build: Heap ${availMb} MB available / ${maxMb} MB max — Profile: ${compilerProfile.name} — Kotlin timeout ${kotlinTimeoutSeconds}s"))

            suspend fun step(label: String, block: (log: (String) -> Unit) -> Unit) {
                emit(BuildEvent.Log("▶ $label"))
                val lines = mutableListOf<String>()
                try {
                    block { lines += it }
                } finally {
                    lines.forEach { emit(BuildEvent.Log(it)) }
                }
            }

            // Step 1b: Resolve user-declared dependencies (implementation(...) in build.gradle)
            // Maven artifact cache makes subsequent runs fast even when re-checked.
            var userLibs: UserLibraries = UserLibraries(null, null, emptyList())
            val userDeps = parseUserDependencies(File(project.rootPath))
            if (userDeps.isNotEmpty()) {
                emit(BuildEvent.Log("▶ Resolving ${userDeps.size} user dependency(-ies)"))
                val mavenCacheDir = File(context.filesDir, "maven-cache")
                val userLibWorkDir = File(cfg.buildRoot, "user-libs").also { it.mkdirs() }
                val depLogs = mutableListOf<String>()
                try {
                    val artifacts = MavenResolver(mavenCacheDir).resolve(userDeps) { depLogs += it }
                    userLibs = UserLibraryMerger(toolsManager.aapt2).merge(artifacts, userLibWorkDir) { depLogs += it }
                } catch (e: Exception) {
                    depLogs += "⚠ Dependency resolution failed: ${e.message}"
                }
                depLogs.forEach { emit(BuildEvent.Log(it)) }
            }

            // Step 1c: Resolve annotation processors (annotationProcessor(...) in build.gradle)
            var processorJars: List<File> = emptyList()
            val processorDeps = parseAnnotationProcessors(File(project.rootPath))
            if (processorDeps.isNotEmpty()) {
                emit(BuildEvent.Log("▶ Resolving ${processorDeps.size} annotation processor(s)"))
                val mavenCacheDir = File(context.filesDir, "maven-cache")
                val procLogs = mutableListOf<String>()
                try {
                    val artifacts = MavenResolver(mavenCacheDir).resolve(processorDeps) { procLogs += it }
                    processorJars = artifacts.map { it.file }.filter { it.exists() }
                } catch (e: Exception) {
                    procLogs += "⚠ Annotation processor resolution failed: ${e.message}"
                }
                procLogs.forEach { emit(BuildEvent.Log(it)) }
            }

            // Step 1d: Resolve kapt processors (kapt(...) in build.gradle.kts)
            var kaptProcessorJars: List<File> = emptyList()
            var kaptPluginJar: File? = null
            val kaptDeps = parseKaptProcessors(File(project.rootPath))
            if (kaptDeps.isNotEmpty()) {
                emit(BuildEvent.Log("▶ Resolving ${kaptDeps.size} kapt processor(s)"))
                val mavenCacheDir = File(context.filesDir, "maven-cache")
                val kaptLogs = mutableListOf<String>()
                try {
                    val procArtifacts = MavenResolver(mavenCacheDir).resolve(kaptDeps) { kaptLogs += it }
                    kaptProcessorJars = procArtifacts.map { it.file }.filter { it.exists() }
                    val pluginArtifacts = MavenResolver(mavenCacheDir).resolve(
                        listOf("org.jetbrains.kotlin:kotlin-annotation-processing-embeddable:1.8.22")
                    ) { kaptLogs += it }
                    kaptPluginJar = pluginArtifacts.firstOrNull()?.file?.takeIf { it.exists() }
                } catch (e: Exception) {
                    kaptLogs += "⚠ kapt resolution failed: ${e.message}"
                }
                kaptLogs.forEach { emit(BuildEvent.Log(it)) }
            }

            // Step 1e: Compose compiler plugin (verification spike).
            // Enabled when the project depends on androidx.compose. The plugin jar is resolved from
            // Maven, runtime-dexed, and the dex injected into the compiler's classloader so the
            // ComponentRegistrar loads on ART. Compose runtime classes ride the normal user-dep
            // classpath (baseClasspath) since they're declared as implementation(...) deps.
            var composePluginJar: File? = null
            var composePluginDexJars: List<File> = emptyList()
            val composeEnabled = userDeps.any { it.substringBefore(':').startsWith("androidx.compose") }
            if (composeEnabled) {
                emit(BuildEvent.Log("▶ Resolving Compose compiler plugin $COMPOSE_COMPILER_VERSION (spike)"))
                val mavenCacheDir = File(context.filesDir, "maven-cache")
                val composeLogs = mutableListOf<String>()
                try {
                    val artifacts = MavenResolver(mavenCacheDir).resolve(
                        listOf("androidx.compose.compiler:compiler:$COMPOSE_COMPILER_VERSION")
                    ) { composeLogs += it }
                    // Inject ONLY the compose compiler jar (self-contained embeddable). Transitive
                    // jars (stdlib, kotlin-compiler-embeddable) must not shadow the on-device kotlinc.
                    val rawJar = artifacts.map { it.file }
                        .firstOrNull { it.name == "compiler-$COMPOSE_COMPILER_VERSION.jar" && it.exists() }
                    if (rawJar != null) {
                        val dexDir = File(context.filesDir, "plugin-dex").also { it.mkdirs() }
                        val dexOut = File(dexDir, "compose-compiler-$COMPOSE_COMPILER_VERSION-dex.jar")
                        val dexJar = PluginDexer(toolsManager).dex(rawJar, dexOut) { composeLogs += it }
                        composePluginJar = rawJar
                        composePluginDexJars = listOf(dexJar)
                    } else {
                        composeLogs += "⚠ Compose compiler jar not found in resolved artifacts"
                    }
                } catch (e: Exception) {
                    composeLogs += "⚠ Compose plugin setup failed: ${e.message}"
                }
                composeLogs.forEach { emit(BuildEvent.Log(it)) }
            }

            val baseClasspath = listOfNotNull(cfg.androidxClassesJar, userLibs.classesJar)

            // Collect source files by type for incremental checks
            val ktFiles = cfg.sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }.toList()
            val javaUserFiles = cfg.sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "java" }.toList()

            // ── Step 2+3: AAPT2 compile + link ───────────────────────────────
            // Skipped when res/ and AndroidManifest.xml are both unchanged.
            val compiledRes = File(cfg.buildRoot, "compiled-res").also { it.mkdirs() }
            val rJavaDir   = File(cfg.buildRoot, "r/java").also { it.mkdirs() }
            val unsignedResApk = File(cfg.buildRoot, "res.apk")

            val resInputs = buildList {
                cfg.resRoot.walkTopDown().filter { it.isFile }.forEach { add(it) }
                add(cfg.manifestFile)
            }
            // Force AAPT2 re-run if source res contains framework theme parents that need patching.
            // The cache key is based on source files which haven't changed, so we must override it.
            val hasBadThemeParent = cfg.resRoot.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".xml") && it.parentFile?.name?.startsWith("values") == true }
                .any { it.readText().let { t -> t.contains("parent=\"android:Theme.Material") || t.contains("parent=\"android:Theme.DeviceDefault") } }
            val resManifestChanged = hasBadThemeParent
                || cache.changed("res+manifest", resInputs)
                || !unsignedResApk.exists()
                || compiledRes.list()?.isEmpty() != false

            if (resManifestChanged) {
                val patchedRes = snapshotResources(cfg.resRoot, cfg.buildRoot)
                step("AAPT2 compile resources") { log ->
                    AaptStep(toolsManager).compile(patchedRes, compiledRes, log)
                }
                val libraryPackages = readPackagesList(cfg.androidxPackagesTxt) + userLibs.packages
                val manifestForLink = patchManifest(cfg.manifestFile, cfg.buildRoot, cfg.packageName, cfg.androidxManifestEntriesXml, cfg.resRoot)
                val resFlatDirs = listOfNotNull(cfg.androidxResFlatDir, userLibs.resFlatDir)
                step("AAPT2 link") { log ->
                    AaptStep(toolsManager).link(
                        manifestFile = manifestForLink,
                        compiledResDir = compiledRes,
                        libraryResFlatDirs = resFlatDirs,
                        rJavaDir = rJavaDir,
                        apkOut = unsignedResApk,
                        emit = log,
                        minSdk = cfg.minSdk,
                        targetSdk = cfg.targetSdk,
                        extraPackages = libraryPackages,
                    )
                }
                cache.markClean("res+manifest", resInputs)
            } else {
                emit(BuildEvent.Log("↩ Skipped AAPT2 compile+link (resources unchanged)"))
            }

            // ── Step 4: Compile sources ───────────────────────────────────────
            val classesDir = File(cfg.buildRoot, "classes").also { it.mkdirs() }
            var recompiled = false

            // 4a: R.java — recompile whenever AAPT2 link ran (R.java regenerated)
            if (resManifestChanged) {
                emit(BuildEvent.Log("▶ Java compile R.java"))
                val rJavaLogs = mutableListOf<String>()
                val rJavaOk = JavaCompileStep(toolsManager).compile(
                    sourceRoots = listOf(rJavaDir),
                    classesDir = classesDir,
                    extraClasspath = baseClasspath,
                    applyHiddenApiBypass = { kotlinDaemon.applyHiddenApiBypass() },
                ) { rJavaLogs += it }
                rJavaLogs.forEach { emit(BuildEvent.Log(it)) }
                if (!rJavaOk) {
                    emit(BuildEvent.Failure("R.java compilation failed — check errors above"))
                    return@flow
                }
                recompiled = true
            } else {
                emit(BuildEvent.Log("↩ Skipped R.java compile (resources unchanged)"))
            }

            // 4b: Kotlin — skipped when no .kt files changed (kapt always forces full compile)
            val kaptGeneratedSourcesDir = if (kaptProcessorJars.isNotEmpty())
                File(cfg.buildRoot, "kapt-generated").also { it.mkdirs() } else null
            val ktChanged = ktFiles.isNotEmpty() && cache.changed("sources-kt", ktFiles)
            // Compose forces a full compile (plugin runs over all sources), like kapt.
            val forceFullKt = kaptProcessorJars.isNotEmpty() || composePluginJar != null
            if (forceFullKt || ktChanged || (resManifestChanged && ktFiles.isNotEmpty())) {
                step("Kotlin compile") { log ->
                    val ok = KotlinCompileStep(toolsManager).compile(
                        sourceRoots = listOf(cfg.sourceRoot),
                        classesDir = classesDir,
                        extraClasspath = listOf(classesDir) + baseClasspath,
                        timeoutSeconds = kotlinTimeoutSeconds,
                        daemon = kotlinDaemon,
                        profile = compilerProfile,
                        processorJars = kaptProcessorJars,
                        kaptPluginJar = kaptPluginJar,
                        kaptGeneratedSourcesDir = kaptGeneratedSourcesDir,
                        composePluginJar = composePluginJar,
                        pluginDexJars = composePluginDexJars,
                        emit = log,
                    )
                    if (!ok) throw RuntimeException("Kotlin compilation failed — check errors above")
                }
                if (!forceFullKt) cache.markClean("sources-kt", ktFiles)
                recompiled = true
            } else if (ktFiles.isNotEmpty()) {
                emit(BuildEvent.Log("↩ Skipped Kotlin compile (sources unchanged)"))
            }

            // 4c: Java (ECJ) user sources — include kapt-generated sources in source roots
            val generatedSourcesDir = File(cfg.buildRoot, "generated-sources").also { it.mkdirs() }
            val javaSourceRoots = buildList {
                add(cfg.sourceRoot)
                kaptGeneratedSourcesDir?.takeIf { it.exists() && it.walkTopDown().any { f -> f.extension == "java" } }
                    ?.let { add(it) }
            }
            val javaChanged = javaUserFiles.isNotEmpty() && cache.changed("sources-java", javaUserFiles)
            if (kaptProcessorJars.isNotEmpty() || javaChanged || (resManifestChanged && javaUserFiles.isNotEmpty())) {
                val label = if (kaptGeneratedSourcesDir != null) "Java compile (ECJ + kapt sources)" else "Java compile (ECJ)"
                emit(BuildEvent.Log("▶ $label"))
                val compileLogs = mutableListOf<String>()
                val compileOk = JavaCompileStep(toolsManager).compile(
                    sourceRoots = javaSourceRoots,
                    classesDir = classesDir,
                    extraClasspath = baseClasspath,
                    processorJars = processorJars,
                    generatedSourcesDir = if (processorJars.isNotEmpty()) generatedSourcesDir else null,
                    applyHiddenApiBypass = { kotlinDaemon.applyHiddenApiBypass() },
                ) { compileLogs += it }
                compileLogs.forEach { emit(BuildEvent.Log(it)) }
                if (!compileOk) {
                    emit(BuildEvent.Failure("Java compilation failed — check errors above"))
                    return@flow
                }
                // Second pass to compile generated sources (from annotation processors)
                if (processorJars.isNotEmpty()) {
                    val generatedFiles = generatedSourcesDir.walkTopDown()
                        .filter { it.isFile && it.extension == "java" }.toList()
                    if (generatedFiles.isNotEmpty()) {
                        emit(BuildEvent.Log("▶ Java compile generated sources (${generatedFiles.size} file(s))"))
                        val genLogs = mutableListOf<String>()
                        val genOk = JavaCompileStep(toolsManager).compile(
                            sourceRoots = listOf(generatedSourcesDir),
                            classesDir = classesDir,
                            extraClasspath = baseClasspath + listOf(classesDir),
                            applyHiddenApiBypass = { kotlinDaemon.applyHiddenApiBypass() },
                        ) { genLogs += it }
                        genLogs.forEach { emit(BuildEvent.Log(it)) }
                        if (!genOk) {
                            emit(BuildEvent.Failure("Generated source compilation failed — check errors above"))
                            return@flow
                        }
                    }
                }
                if (kaptProcessorJars.isEmpty()) cache.markClean("sources-java", javaUserFiles)
                recompiled = true
            } else if (javaUserFiles.isNotEmpty()) {
                emit(BuildEvent.Log("↩ Skipped Java compile (sources unchanged)"))
            }

            val dexFile = File(cfg.buildRoot, "dex/classes.dex")
            val dexDir = File(cfg.buildRoot, "dex")
            val apkName = if (variant == BuildVariant.RELEASE) "app-release.apk" else "app-debug.apk"
            val signedApk = File(cfg.buildRoot, apkName)
            val packedApk = File(cfg.buildRoot, "packed.apk")

            // Detect APKs built by the old single-dex packager: if D8 produced more DEX files
            // than what is currently in packed.apk, force a re-pack even if nothing else changed.
            val producedDexCount = dexDir.listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }?.size ?: 0
            val packedDexCount = countApkDexEntries(packedApk)
            val dexCountMismatch = producedDexCount > 0 && producedDexCount > packedDexCount

            if (!recompiled && !dexCountMismatch && dexFile.exists() && signedApk.exists()) {
                emit(BuildEvent.Log("↩ Skipped DEX + pack + sign (nothing changed)"))
            } else {
                if (dexCountMismatch) emit(BuildEvent.Log("↩ Re-packing: DEX count mismatch ($packedDexCount packed vs $producedDexCount produced)"))
                // Step 5: DEX (D8 for debug, R8 for release)
                dexDir.mkdirs()
                val proguardFiles = collectProguardFiles(File(project.rootPath), cfg.buildRoot, variant)
                val dexLabel = if (variant == BuildVariant.RELEASE && proguardFiles.isNotEmpty()) "R8 (shrink+obfuscate)" else "DEX (D8)"
                // Skip D8 when only re-packing (DEX files are already correct, just not all bundled).
                if (recompiled) step(dexLabel) { log ->
                    DexStep(toolsManager).convert(
                        classesDir, dexDir, cfg.minSdk, log,
                        extraJars = listOfNotNull(userLibs.classesJar),
                        variant = variant,
                        proguardRulesFiles = proguardFiles,
                    )
                }

                // Step 6: Package APK
                step("Package APK") { log ->
                    ApkPackager().pack(
                        aapt2Apk = unsignedResApk,
                        classesDex = dexFile,
                        outputApk = packedApk,
                        emit = log,
                        libraryDex = cfg.androidxClasses2Dex,
                    )
                }

                // Step 7: Sign APK (v1 + v2)
                val variantLabel = if (variant == BuildVariant.RELEASE) "release" else "debug"
                step("Sign APK ($variantLabel, v1+v2)") { log ->
                    apkSigner.sign(packedApk, signedApk, log, keystoreConfig, variant)
                }
            }

            emit(BuildEvent.Log("✓ Build successful: ${signedApk.absolutePath}"))
            emit(BuildEvent.Success(signedApk.absolutePath))
        } catch (e: Throwable) {
            Log.e("BuildEngine", "Build failed with exception", e)
            val message = when (e) {
                is OutOfMemoryError -> "Build failed: Out of Memory. The project may be too large for this device."
                is StackOverflowError -> "Build failed: Stack Overflow (Deep code nesting)."
                else -> e.message ?: "${e.javaClass.simpleName} (see logcat for details)"
            }
            emit(BuildEvent.Log("✗ $message", BuildEvent.Log.Level.ERROR))
            emit(BuildEvent.Failure(message))
        }
    }.flowOn(Dispatchers.IO)

    private fun detectModules(projectRoot: File): List<ModuleInfo>? {
        val settingsFile = listOf(
            File(projectRoot, "settings.gradle.kts"),
            File(projectRoot, "settings.gradle"),
        ).firstOrNull { it.exists() } ?: return null
        val includes = ModuleParser.parseIncludes(settingsFile)
        if (includes.size <= 1) return null
        val catalog = VersionCatalogParser.parse(File(projectRoot, "gradle/libs.versions.toml"))
        return includes.mapNotNull { ModuleParser.parseModuleInfo(projectRoot, it, catalog) }
    }

    private suspend fun FlowCollector<BuildEvent>.buildMultiModule(
        project: Project,
        modules: List<ModuleInfo>,
        kotlinTimeoutSeconds: Long,
        compilerProfile: CompilerPerformanceProfile,
        keystoreConfig: KeystoreConfig?,
        variant: BuildVariant = BuildVariant.DEBUG,
    ) {
        val sorted = ModuleParser.topologicalSort(modules)
        val appModule = sorted.firstOrNull { it.type == ModuleType.APPLICATION }
            ?: throw RuntimeException("No application module found in multi-module project")

        emit(BuildEvent.Log("▶ Multi-module build: ${sorted.map { it.name }}"))
        emit(BuildEvent.Log("▶ Preparing build tools"))
        val toolsReExtracted = toolsManager.prepare()
        if (toolsReExtracted) {
            kotlinDaemon.clearCache()
            emit(BuildEvent.Log("Tools re-extracted — Kotlin compiler cache cleared"))
        }

        val buildRoot = File(context.filesDir, "builds/${project.name}")
        buildRoot.mkdirs()
        if (toolsReExtracted) {
            File(buildRoot, "kotlin-ic-cache").deleteRecursively()
        }

        suspend fun step(label: String, block: (log: (String) -> Unit) -> Unit) {
            emit(BuildEvent.Log("▶ $label"))
            val lines = mutableListOf<String>()
            try { block { lines += it } } finally { lines.forEach { emit(BuildEvent.Log(it)) } }
        }

        // ── Resolve Maven deps (union across all modules) ─────────────────
        val allMavenDeps = sorted.flatMap { it.mavenDeps }.distinct()
        val allProcessorDeps = sorted.flatMap { it.annotationProcessors }.distinct()

        var userLibs = UserLibraries(null, null, emptyList())
        if (allMavenDeps.isNotEmpty()) {
            emit(BuildEvent.Log("▶ Resolving ${allMavenDeps.size} dependency(-ies) across modules"))
            val mavenCacheDir = File(context.filesDir, "maven-cache")
            val workDir = File(buildRoot, "user-libs").also { it.mkdirs() }
            val logs = mutableListOf<String>()
            try {
                val artifacts = MavenResolver(mavenCacheDir).resolve(allMavenDeps) { logs += it }
                userLibs = UserLibraryMerger(toolsManager.aapt2).merge(artifacts, workDir) { logs += it }
            } catch (e: Exception) { logs += "⚠ Dependency resolution failed: ${e.message}" }
            logs.forEach { emit(BuildEvent.Log(it)) }
        }

        var processorJars: List<File> = emptyList()
        if (allProcessorDeps.isNotEmpty()) {
            emit(BuildEvent.Log("▶ Resolving ${allProcessorDeps.size} annotation processor(s)"))
            val mavenCacheDir = File(context.filesDir, "maven-cache")
            val logs = mutableListOf<String>()
            try {
                val artifacts = MavenResolver(mavenCacheDir).resolve(allProcessorDeps) { logs += it }
                processorJars = artifacts.map { it.file }.filter { it.exists() }
            } catch (e: Exception) { logs += "⚠ Annotation processor resolution failed: ${e.message}" }
            logs.forEach { emit(BuildEvent.Log(it)) }
        }

        val baseClasspath = listOfNotNull(toolsManager.androidxClassesJar, userLibs.classesJar)

        // ── AAPT2 compile each module's resources ──────────────────────────
        val compiledResDirByModule = mutableMapOf<String, File>()
        for (module in sorted) {
            val resDir = File(module.dir, "src/main/res")
            if (!resDir.exists() || resDir.walkTopDown().none { it.isFile }) continue
            val outDir = File(buildRoot, "compiled-res-${module.name.replace(":", "_")}").also { it.mkdirs() }
            step("AAPT2 compile ${module.name} resources") { log ->
                AaptStep(toolsManager).compile(resDir, outDir, log)
            }
            compiledResDirByModule[module.name] = outDir
        }

        // ── AAPT2 link (single call for all modules) ───────────────────────
        val rJavaDir = File(buildRoot, "r/java").also { it.mkdirs() }
        val unsignedResApk = File(buildRoot, "res.apk")

        val appCompiledResDir = compiledResDirByModule[appModule.name]
            ?: File(buildRoot, "compiled-res-app-empty").also { it.mkdirs() }

        val libResFlatDirs = buildList {
            sorted.filter { it.name != appModule.name }
                .mapNotNull { compiledResDirByModule[it.name] }
                .forEach { add(it) }
            if (toolsManager.androidxResFlatDir.exists()) add(toolsManager.androidxResFlatDir)
            userLibs.resFlatDir?.let { add(it) }
        }

        val extraPackages = buildList {
            sorted.filter { it.name != appModule.name && it.namespace.isNotBlank() }
                .forEach { add(it.namespace) }
            addAll(readPackagesList(toolsManager.androidxPackagesTxt))
            addAll(userLibs.packages)
        }.distinct()

        val appManifest = listOf(
            File(appModule.dir, "src/main/AndroidManifest.xml"),
            File(appModule.dir, "AndroidManifest.xml"),
        ).firstOrNull { it.exists() }
            ?: throw RuntimeException("AndroidManifest.xml not found for ${appModule.name}")

        val packageName = appModule.namespace.ifBlank { project.packageName }
        val appResRoot = listOf(
            File(appModule.dir, "src/main/res"),
            File(appModule.dir, "res"),
        ).firstOrNull { it.isDirectory } ?: File(appModule.dir, "src/main/res")
        val patchedManifest = patchManifest(appManifest, buildRoot, packageName, toolsManager.androidxManifestEntriesXml, appResRoot)

        step("AAPT2 link (${sorted.size} modules)") { log ->
            AaptStep(toolsManager).link(
                manifestFile = patchedManifest,
                compiledResDir = appCompiledResDir,
                libraryResFlatDirs = libResFlatDirs,
                rJavaDir = rJavaDir,
                apkOut = unsignedResApk,
                emit = log,
                minSdk = appModule.minSdk,
                targetSdk = appModule.targetSdk,
                extraPackages = extraPackages,
            )
        }

        // ── Compile all sources into shared classesDir ─────────────────────
        val classesDir = File(buildRoot, "classes").also { it.mkdirs() }

        emit(BuildEvent.Log("▶ Java compile R.java"))
        val rJavaLogs = mutableListOf<String>()
        val rJavaOk = JavaCompileStep(toolsManager).compile(
            sourceRoots = listOf(rJavaDir),
            classesDir = classesDir,
            extraClasspath = baseClasspath,
            applyHiddenApiBypass = { kotlinDaemon.applyHiddenApiBypass() },
        ) { rJavaLogs += it }
        rJavaLogs.forEach { emit(BuildEvent.Log(it)) }
        if (!rJavaOk) { emit(BuildEvent.Failure("R.java compilation failed")); return }

        val allSourceRoots = sorted
            .map { File(it.dir, "src/main/java") }
            .filter { it.exists() }

        val hasKt = allSourceRoots.any { root -> root.walkTopDown().any { it.extension == "kt" } }
        if (hasKt) {
            step("Kotlin compile (${sorted.size} modules)") { log ->
                val ok = KotlinCompileStep(toolsManager).compile(
                    sourceRoots = allSourceRoots,
                    classesDir = classesDir,
                    extraClasspath = listOf(classesDir) + baseClasspath,
                    timeoutSeconds = kotlinTimeoutSeconds,
                    daemon = kotlinDaemon,
                    profile = compilerProfile,
                    emit = log,
                )
                if (!ok) throw RuntimeException("Kotlin compilation failed")
            }
        }

        val hasJava = allSourceRoots.any { root -> root.walkTopDown().any { it.extension == "java" } }
        if (hasJava) {
            val generatedSourcesDir = File(buildRoot, "generated-sources").also { it.mkdirs() }
            emit(BuildEvent.Log("▶ Java compile (ECJ) — ${sorted.size} module(s)"))
            val javaLogs = mutableListOf<String>()
            val javaOk = JavaCompileStep(toolsManager).compile(
                sourceRoots = allSourceRoots,
                classesDir = classesDir,
                extraClasspath = baseClasspath,
                processorJars = processorJars,
                generatedSourcesDir = if (processorJars.isNotEmpty()) generatedSourcesDir else null,
                applyHiddenApiBypass = { kotlinDaemon.applyHiddenApiBypass() },
            ) { javaLogs += it }
            javaLogs.forEach { emit(BuildEvent.Log(it)) }
            if (!javaOk) { emit(BuildEvent.Failure("Java compilation failed")); return }

            if (processorJars.isNotEmpty()) {
                val genFiles = generatedSourcesDir.walkTopDown()
                    .filter { it.isFile && it.extension == "java" }.toList()
                if (genFiles.isNotEmpty()) {
                    emit(BuildEvent.Log("▶ Java compile generated sources (${genFiles.size} file(s))"))
                    val genLogs = mutableListOf<String>()
                    val genOk = JavaCompileStep(toolsManager).compile(
                        sourceRoots = listOf(generatedSourcesDir),
                        classesDir = classesDir,
                        extraClasspath = baseClasspath + listOf(classesDir),
                        applyHiddenApiBypass = { kotlinDaemon.applyHiddenApiBypass() },
                    ) { genLogs += it }
                    genLogs.forEach { emit(BuildEvent.Log(it)) }
                    if (!genOk) { emit(BuildEvent.Failure("Generated source compilation failed")); return }
                }
            }
        }

        // ── DEX + Pack + Sign ──────────────────────────────────────────────
        val dexDir = File(buildRoot, "dex").also { it.mkdirs() }
        val proguardFiles = collectProguardFiles(File(project.rootPath), buildRoot, variant)
        val dexLabel = if (variant == BuildVariant.RELEASE && proguardFiles.isNotEmpty()) "R8 (shrink+obfuscate)" else "DEX (D8)"
        step(dexLabel) { log ->
            DexStep(toolsManager).convert(
                classesDir, dexDir, appModule.minSdk, log,
                extraJars = listOfNotNull(userLibs.classesJar),
                variant = variant,
                proguardRulesFiles = proguardFiles,
            )
        }

        val dexFile = File(buildRoot, "dex/classes.dex")
        val packedApk = File(buildRoot, "packed.apk")
        step("Package APK") { log ->
            ApkPackager().pack(
                aapt2Apk = unsignedResApk,
                classesDex = dexFile,
                outputApk = packedApk,
                emit = log,
                libraryDex = toolsManager.androidxClasses2Dex,
            )
        }

        val apkName = if (variant == BuildVariant.RELEASE) "app-release.apk" else "app-debug.apk"
        val signedApk = File(buildRoot, apkName)
        val variantLabel = if (variant == BuildVariant.RELEASE) "release" else "debug"
        step("Sign APK ($variantLabel, v1+v2)") { log ->
            apkSigner.sign(packedApk, signedApk, log, keystoreConfig, variant)
        }

        emit(BuildEvent.Log("✓ Multi-module build successful: ${signedApk.absolutePath}"))
        emit(BuildEvent.Success(signedApk.absolutePath))
    }

    // Copies res/ to a build-dir snapshot so the compiled-res output stays isolated from srcRes.
    // Also fixes framework theme parents that crash AppCompatActivity (android:Theme.Material.*
    // is a framework theme; AppCompatActivity requires Theme.AppCompat.*).
    private fun snapshotResources(srcRes: File, buildRoot: File): File {
        if (!srcRes.exists()) return srcRes
        val dest = File(buildRoot, "res-patched")
        dest.deleteRecursively()
        srcRes.walkTopDown().forEach { src ->
            val rel = src.relativeTo(srcRes)
            val target = File(dest, rel.path)
            if (src.isDirectory) target.mkdirs()
            else { target.parentFile?.mkdirs(); src.copyTo(target, overwrite = true) }
        }
        dest.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".xml") && it.parentFile?.name?.startsWith("values") == true }
            .forEach { xmlFile ->
                val original = xmlFile.readText()
                val patched = original
                    .replace(
                        "parent=\"android:Theme.Material.Light.NoActionBar\"",
                        "parent=\"Theme.AppCompat.Light.NoActionBar\""
                    )
                    .replace(
                        "parent=\"android:Theme.Material.Light\"",
                        "parent=\"Theme.AppCompat.Light\""
                    )
                    .replace(
                        "parent=\"android:Theme.Material\"",
                        "parent=\"Theme.AppCompat\""
                    )
                    .replace(
                        "parent=\"android:Theme.DeviceDefault.Light.NoActionBar\"",
                        "parent=\"Theme.AppCompat.Light.NoActionBar\""
                    )
                if (patched != original) xmlFile.writeText(patched)
            }
        return dest
    }

    // Rewrites the manifest to a build-dir copy, stripping patterns AAPT2 cannot resolve
    // (mipmap icon refs, dataExtractionRules, fullBackupContent, tools: namespace), then merges
    // library <uses-permission> and <uses-feature> entries via ManifestMerger.
    private fun patchManifest(
        src: File,
        buildRoot: File,
        packageName: String,
        libraryEntriesXml: File,
        resRoot: File,
    ): File {
        if (!src.exists()) return src
        var text = src.readText()

        if (!text.contains("package=")) {
            text = text.replace("<manifest", "<manifest\n    package=\"$packageName\"")
        }

        // Strip @mipmap/ icon refs ONLY when the resource is absent — projects that ship
        // their own res/mipmap-*/ launcher icons keep them (those refs link fine via aapt2).
        text = Regex("\\s*android:icon\\s*=\\s*\"@mipmap/([^\"]*)\"").replace(text) { m ->
            if (mipmapResourceExists(resRoot, m.groupValues[1])) m.value else ""
        }
        text = Regex("\\s*android:roundIcon\\s*=\\s*\"@mipmap/([^\"]*)\"").replace(text) { m ->
            if (mipmapResourceExists(resRoot, m.groupValues[1])) m.value else ""
        }
        // Strip AGP-generated backup/extraction attributes that reference absent res files
        text = text.replace(Regex("\\s*android:dataExtractionRules\\s*=\\s*\"[^\"]*\""), "")
        text = text.replace(Regex("\\s*android:fullBackupContent\\s*=\\s*\"[^\"]*\""), "")
        text = text.replace(Regex("\\s*tools:targetApi\\s*=\\s*\"[^\"]*\""), "")
        if (!text.contains("tools:")) {
            text = text.replace(Regex("\\s*xmlns:tools\\s*=\\s*\"[^\"]*\""), "")
        }

        val patched = File(buildRoot, "AndroidManifest.xml")
        patched.writeText(text)

        val merged = File(buildRoot, "AndroidManifest-merged.xml")
        try {
            ManifestMerger.merge(patched, libraryEntriesXml, merged)
        } catch (e: Exception) {
            Log.w("BuildEngine", "ManifestMerger failed (using unmerged manifest): ${e.message}")
            return patched
        }
        return merged
    }

    /**
     * True when [resRoot] contains a mipmap resource named [name] (any density qualifier,
     * any file extension) — e.g. res/mipmap-mdpi/ic_launcher.png for name "ic_launcher".
     */
    private fun mipmapResourceExists(resRoot: File, name: String): Boolean {
        if (!resRoot.isDirectory) return false
        return resRoot.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap") }
            ?.any { dir -> dir.listFiles()?.any { it.isFile && it.nameWithoutExtension == name } == true }
            ?: false
    }

    private fun readPackagesList(packagesTxt: File): List<String> {
        if (!packagesTxt.exists()) return emptyList()
        return packagesTxt.readLines().map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun loadVersionCatalog(root: File): Map<String, String> =
        VersionCatalogParser.parse(File(root, "gradle/libs.versions.toml"))

    private fun parseUserDependencies(root: File): List<String> {
        val gradle = listOf(File(root, "app/build.gradle.kts"), File(root, "app/build.gradle"))
            .firstOrNull { it.exists() } ?: return emptyList()
        val text = gradle.readText()
        val catalog = loadVersionCatalog(root)
        val inline = Regex("""implementation\s*\(\s*["']([^"']+:[^"']+:[^"']+)["']\s*\)""")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.count { c -> c == ':' } == 2 }
        val catalogRefs = Regex("""implementation\s*\(\s*libs\.([a-zA-Z0-9._-]+)\s*\)""")
            .findAll(text)
            .mapNotNull { VersionCatalogParser.resolve(it.groupValues[1], catalog) }
        return (inline + catalogRefs).distinct().toList()
    }

    private fun parseAnnotationProcessors(root: File): List<String> {
        val gradle = listOf(File(root, "app/build.gradle.kts"), File(root, "app/build.gradle"))
            .firstOrNull { it.exists() } ?: return emptyList()
        val text = gradle.readText()
        val catalog = loadVersionCatalog(root)
        val inline = Regex("""annotationProcessor\s*\(\s*["']([^"']+:[^"']+:[^"']+)["']\s*\)""")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.count { c -> c == ':' } == 2 }
        val catalogRefs = Regex("""annotationProcessor\s*\(\s*libs\.([a-zA-Z0-9._-]+)\s*\)""")
            .findAll(text)
            .mapNotNull { VersionCatalogParser.resolve(it.groupValues[1], catalog) }
        return (inline + catalogRefs).distinct().toList()
    }

    private fun parseKaptProcessors(root: File): List<String> {
        val gradle = listOf(File(root, "app/build.gradle.kts"), File(root, "app/build.gradle"))
            .firstOrNull { it.exists() } ?: return emptyList()
        val text = gradle.readText()
        val catalog = loadVersionCatalog(root)
        val inline = Regex("""kapt\s*\(\s*["']([^"']+:[^"']+:[^"']+)["']\s*\)""")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.count { c -> c == ':' } == 2 }
        val catalogRefs = Regex("""kapt\s*\(\s*libs\.([a-zA-Z0-9._-]+)\s*\)""")
            .findAll(text)
            .mapNotNull { VersionCatalogParser.resolve(it.groupValues[1], catalog) }
        return (inline + catalogRefs).distinct().toList()
    }

    /**
     * For release builds: writes the default Android keep rules to [buildRoot] and collects
     * any user-defined rules from [projectRoot]/app/proguard-rules.pro.
     * Returns an empty list for debug builds (no shrinking).
     */
    private fun collectProguardFiles(projectRoot: File, buildRoot: File, variant: BuildVariant): List<File> {
        if (variant != BuildVariant.RELEASE) return emptyList()
        val defaultRules = File(buildRoot, "default-proguard-rules.pro")
        defaultRules.writeText(com.bori.core.build.steps.DefaultProguardRules.RULES)
        return buildList {
            add(defaultRules)
            val userRules = File(projectRoot, "app/proguard-rules.pro")
            if (userRules.exists()) add(userRules)
        }
    }

    private fun countApkDexEntries(apk: File): Int {
        if (!apk.exists()) return 0
        return try {
            ZipInputStream(apk.inputStream().buffered()).use { zin ->
                var count = 0
                var ze = zin.nextEntry
                while (ze != null) {
                    if (ze.name.matches(Regex("classes\\d*\\.dex"))) count++
                    ze = zin.nextEntry
                }
                count
            }
        } catch (e: Exception) { 0 }
    }

    private fun readNamespace(root: File): String? =
        listOf(File(root, "app/build.gradle.kts"), File(root, "app/build.gradle"))
            .firstOrNull { it.exists() }
            ?.readText()
            ?.let { Regex("""namespace\s*[=\s]\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }

    /** Reads the declared targetSdk (both `targetSdk = N` and `targetSdkVersion N` forms). */
    private fun readTargetSdk(root: File): Int? =
        listOf(File(root, "app/build.gradle.kts"), File(root, "app/build.gradle"))
            .firstOrNull { it.exists() }
            ?.readText()
            ?.let { Regex("""targetSdk(?:Version)?\s*[=\s]\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun makeBuildConfig(project: Project): BuildConfig {
        val root = File(project.rootPath)
        val packageName = readNamespace(root) ?: project.packageName
        val manifestFile = listOf(
            File(root, "app/src/main/AndroidManifest.xml"),
            File(root, "app/AndroidManifest.xml"),
        ).firstOrNull { it.exists() }
            ?: throw RuntimeException("AndroidManifest.xml not found in ${project.rootPath}")
        return BuildConfig(
            projectName = project.name,
            packageName = packageName,
            minSdk = project.minSdk,
            targetSdk = readTargetSdk(root) ?: project.minSdk,
            sourceRoot = File(root, "app/src/main/java"),
            resRoot = File(root, "app/src/main/res"),
            manifestFile = manifestFile,
            buildRoot = File(context.filesDir, "builds/${project.name}"),
            androidxClassesJar = toolsManager.androidxClassesJar,
            androidxClasses2Dex = toolsManager.androidxClasses2Dex,
            androidxResFlatDir = toolsManager.androidxResFlatDir,
            androidxPackagesTxt = toolsManager.androidxPackagesTxt,
            androidxManifestEntriesXml = toolsManager.androidxManifestEntriesXml,
        )
    }

    companion object {
        // Compose compiler that matches the pinned on-device Kotlin 1.8.22 (see core/build/build.gradle.kts).
        private const val COMPOSE_COMPILER_VERSION = "1.4.8"
    }
}
