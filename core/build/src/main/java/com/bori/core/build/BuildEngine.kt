package com.bori.core.build

import android.content.Context
import android.util.Log
import com.bori.core.build.model.BuildEvent
import com.bori.core.build.model.BuildVariant
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.core.build.model.KeystoreConfig
import com.bori.core.build.model.ModuleInfo
import com.bori.core.build.model.ModuleType

import com.bori.core.build.steps.AaptStep
import com.bori.core.build.steps.ApkPackager
import com.bori.core.build.steps.ApkSigner
import com.bori.core.build.steps.DexStep
import com.bori.core.build.steps.JavaCompileStep
import com.bori.core.build.steps.KotlinCompileStep
import com.bori.core.build.steps.LibraryManifest
import com.bori.core.build.steps.ManifestMerger
import com.bori.core.build.steps.MavenResolver
import com.bori.core.build.steps.PluginDexer
import com.bori.core.build.steps.ResolvedArtifact
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a project into a signed APK on the device. A project with one module and one with
 * several go through the same steps: the modules the app depends on are compiled together
 * into one classes directory, and their resources, manifests, assets and native libraries are
 * merged with the app's and its Maven dependencies'.
 */
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
            runBuild(project, kotlinTimeoutSeconds, compilerProfile, keystoreConfig, variant)
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

    private suspend fun FlowCollector<BuildEvent>.runBuild(
        project: Project,
        kotlinTimeoutSeconds: Long,
        compilerProfile: CompilerPerformanceProfile,
        keystoreConfig: KeystoreConfig?,
        variant: BuildVariant,
    ) {
        val projectRoot = File(project.rootPath)
        val allModules = ModuleParser.readProject(projectRoot, variant, project.minSdk)
        val app = allModules.lastOrNull { it.type == ModuleType.APPLICATION }
            ?: allModules.singleOrNull { it.name == ":app" }
            ?: throw RuntimeException("No app module found: one module's build.gradle must apply com.android.application")
        val appManifest = app.manifest ?: throw RuntimeException("AndroidManifest.xml not found in ${app.dir}")
        val modules = dependencyClosure(app, allModules)
        val libraryModules = modules.filter { it !== app }
        if (libraryModules.isNotEmpty()) emit(BuildEvent.Log("▶ Modules: ${modules.joinToString { it.name }}"))

        // Debug and release keep separate outputs: BuildConfig constants are inlined into the
        // compiled code, so switching variants must not reuse the other variant's classes.
        val projectBuildDir = File(context.filesDir, "builds/${project.name}")
        removeOldLayout(projectBuildDir)
        val buildRoot = File(projectBuildDir, variant.name.lowercase()).also { it.mkdirs() }
        val cache = BuildCache(buildRoot)

        emit(BuildEvent.Log("▶ Preparing build tools"))
        if (toolsManager.prepare()) {
            // The warm compiler holds a ClassLoader over the old DEX files, and the Kotlin IC
            // cache references class files from the old build.
            kotlinDaemon.clearCache()
            BuildVariant.entries.forEach { File(projectBuildDir, "${it.name.lowercase()}/kotlin-ic-cache").deleteRecursively() }
            emit(BuildEvent.Log("Tools re-extracted — Kotlin compiler cache cleared"))
        }

        val runtime = Runtime.getRuntime()
        val maxMb = runtime.maxMemory() / 1_048_576
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
        emit(BuildEvent.Log("Build: Heap ${maxMb - usedMb} MB available / ${maxMb} MB max — Profile: ${compilerProfile.name} — Kotlin timeout ${kotlinTimeoutSeconds}s"))

        suspend fun step(label: String, block: (log: (String) -> Unit) -> Unit) {
            emit(BuildEvent.Log("▶ $label"))
            val lines = mutableListOf<String>()
            try {
                block { lines += it }
            } finally {
                lines.forEach { emit(BuildEvent.Log(it)) }
            }
        }

        // ── Step 1: Dependencies ───────────────────────────────────────────
        val mavenCacheDir = File(context.filesDir, "maven-cache")
        val bundledPackages = readPackagesList(toolsManager.androidxPackagesTxt)
        val merger = UserLibraryMerger(
            aapt2 = toolsManager.aapt2,
            aarCacheDir = File(context.filesDir, "aar-cache"),
            bundledClassesJar = toolsManager.androidxClassesJar,
            bundledPackages = bundledPackages.toSet(),
        )
        suspend fun resolve(label: String, coords: List<String>): List<ResolvedArtifact> {
            if (coords.isEmpty()) return emptyList()
            emit(BuildEvent.Log("▶ Resolving ${coords.size} $label"))
            val logs = mutableListOf<String>()
            val artifacts = try {
                MavenResolver(mavenCacheDir).resolve(coords) { logs += it }
            } catch (e: Exception) {
                logs += "⚠ Resolution failed: ${e.message}"
                emptyList()
            }
            logs.forEach { emit(BuildEvent.Log(it)) }
            return artifacts
        }

        val userDeps = modules.flatMap { it.mavenDeps }.distinct()
        var userLibs = UserLibraries.EMPTY
        val depArtifacts = resolve("dependency(-ies)", userDeps)
        if (depArtifacts.isNotEmpty()) {
            val logs = mutableListOf<String>()
            try {
                userLibs = merger.merge(depArtifacts, File(buildRoot, "user-libs")) { logs += it }
            } catch (e: Exception) {
                logs += "⚠ Unpacking dependencies failed: ${e.message}"
            }
            logs.forEach { emit(BuildEvent.Log(it)) }
        }
        val compileOnlyJars = run {
            val logs = mutableListOf<String>()
            val jars = merger.classpathJars(resolve("compileOnly dependency(-ies)", modules.flatMap { it.compileOnlyDeps }.distinct() - userDeps.toSet())) { logs += it }
            logs.forEach { emit(BuildEvent.Log(it)) }
            jars
        }
        val processorJars = resolve("annotation processor(s)", modules.flatMap { it.annotationProcessors }.distinct())
            .map { it.file }.filter { it.exists() }

        var kaptProcessorJars: List<File> = emptyList()
        var kaptPluginJar: File? = null
        val kaptDeps = modules.flatMap { it.kaptProcessors }.distinct()
        if (kaptDeps.isNotEmpty()) {
            kaptProcessorJars = resolve("kapt processor(s)", kaptDeps).map { it.file }.filter { it.exists() }
            kaptPluginJar = resolve("kapt plugin", listOf("org.jetbrains.kotlin:kotlin-annotation-processing-embeddable:1.8.22"))
                .firstOrNull()?.file?.takeIf { it.exists() }
        }
        val kspDeps = modules.flatMap { it.kspProcessors }.distinct()
        if (kspDeps.isNotEmpty()) {
            emit(BuildEvent.Log("⚠ KSP isn't supported on the phone yet, so these ksp(...) processors are skipped: ${kspDeps.joinToString()}", BuildEvent.Log.Level.WARN))
        }

        // Compose compiler plugin, when the project depends on androidx.compose. The plugin jar
        // is resolved from Maven, runtime-dexed, and the dex injected into the compiler's
        // classloader so the ComponentRegistrar loads on ART. Compose runtime classes ride the
        // normal user-dep classpath since they're declared as implementation(...) deps.
        var composePluginJar: File? = null
        var composePluginDexJars: List<File> = emptyList()
        if (userDeps.any { it.substringBefore(':').startsWith("androidx.compose") }) {
            val logs = mutableListOf<String>()
            val artifacts = resolve("Compose compiler plugin $COMPOSE_COMPILER_VERSION", listOf("androidx.compose.compiler:compiler:$COMPOSE_COMPILER_VERSION"))
            try {
                // Inject ONLY the compose compiler jar (self-contained embeddable). Transitive
                // jars (stdlib, kotlin-compiler-embeddable) must not shadow the on-device kotlinc.
                val rawJar = artifacts.map { it.file }.firstOrNull { it.name == "compiler-$COMPOSE_COMPILER_VERSION.jar" && it.exists() }
                if (rawJar != null) {
                    val dexDir = File(context.filesDir, "plugin-dex").also { it.mkdirs() }
                    val dexOut = File(dexDir, "compose-compiler-$COMPOSE_COMPILER_VERSION-dex.jar")
                    composePluginDexJars = listOf(PluginDexer(toolsManager).dex(rawJar, dexOut) { logs += it })
                    composePluginJar = rawJar
                } else {
                    logs += "⚠ Compose compiler jar not found in resolved artifacts"
                }
            } catch (e: Exception) {
                logs += "⚠ Compose plugin setup failed: ${e.message}"
            }
            logs.forEach { emit(BuildEvent.Log(it)) }
        }

        val baseClasspath = listOfNotNull(toolsManager.androidxClassesJar, userLibs.classesJar) + compileOnlyJars
        val sourceRoots = modules.flatMap { it.sourceDirs }
        val ktFiles = sourceRoots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val javaUserFiles = sourceRoots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "java" }.toList() }

        // ── Step 2: Resources and manifest ─────────────────────────────────
        val compiledResRoot = File(buildRoot, "compiled-res")
        val rJavaDir = File(buildRoot, "r/java")
        val unsignedResApk = File(buildRoot, "res.apk")
        val aaptRules = File(buildRoot, "aapt-rules.pro")
        val libraryManifests = libraryModules.mapNotNull { m -> m.manifest?.let { LibraryManifest(it, m.namespace) } } +
            userLibs.manifests +
            LibraryManifest(toolsManager.androidxManifestEntriesXml, "")
        // Everything that changes what AAPT2 links, besides the files themselves.
        val linkSettings = writeIfChanged(File(buildRoot, "link-settings.txt"), listOf(
            app.applicationId, app.namespace, app.versionCode, app.versionName, app.minSdk, app.targetSdk,
            userLibs.resFlatDirs.joinToString(), userLibs.packages.joinToString(), libraryManifests.joinToString { it.file.path },
        ).joinToString("\n"))
        val resInputs = buildList {
            modules.mapNotNull { it.resDir }.forEach { dir -> dir.walkTopDown().filter { it.isFile }.forEach { add(it) } }
            libraryManifests.forEach { add(it.file) }
            add(appManifest)
            add(linkSettings)
        }
        val resManifestChanged = cache.changed("res+manifest", resInputs) || !unsignedResApk.exists()

        if (resManifestChanged) {
            val flatDirs = mutableListOf<File>()
            flatDirs += toolsManager.androidxResFlatDir
            flatDirs += userLibs.resFlatDirs
            for (module in modules) {
                val resDir = module.resDir ?: continue
                if (resDir.walkTopDown().none { it.isFile }) continue
                val outDir = File(compiledResRoot, module.name.trim(':').replace(':', '_').ifEmpty { "root" })
                outDir.deleteRecursively()
                outDir.mkdirs()
                val patchedRes = snapshotResources(resDir, File(buildRoot, "res-patched/${outDir.name}"))
                step("AAPT2 compile ${module.name} resources") { log -> AaptStep(toolsManager).compile(patchedRes, outDir, log) }
                flatDirs += outDir
            }
            val patched = patchManifest(appManifest, buildRoot, app.namespace, app.resDir)
            val merged = File(buildRoot, "AndroidManifest-merged.xml")
            val manifestForLink = try {
                ManifestMerger.merge(patched, libraryManifests, merged, app.applicationId)
                merged
            } catch (e: Exception) {
                emit(BuildEvent.Log("⚠ Manifest merge failed (using the app manifest alone): ${e.message}", BuildEvent.Log.Level.WARN))
                patched
            }
            val extraPackages = (bundledPackages + userLibs.packages + libraryModules.map { it.namespace })
                .filter { it.isNotBlank() && it != app.namespace }
                .distinct()
            rJavaDir.deleteRecursively()
            rJavaDir.mkdirs()
            step("AAPT2 link") { log ->
                AaptStep(toolsManager).link(
                    manifestFile = manifestForLink,
                    flatDirs = flatDirs,
                    rJavaDir = rJavaDir,
                    apkOut = unsignedResApk,
                    emit = log,
                    minSdk = app.minSdk,
                    targetSdk = app.targetSdk,
                    extraPackages = extraPackages,
                    versionCode = app.versionCode,
                    versionName = app.versionName,
                    renameManifestPackage = app.applicationId.takeIf { it.isNotBlank() && it != app.namespace },
                    proguardRulesOut = aaptRules,
                )
            }
            cache.markClean("res+manifest", resInputs)
        } else {
            emit(BuildEvent.Log("↩ Skipped AAPT2 compile+link (resources unchanged)"))
        }

        // ── Step 3: Compile sources ────────────────────────────────────────
        val classesDir = File(buildRoot, "classes").also { it.mkdirs() }
        var recompiled = false

        // 3a: R.java and BuildConfig.java, whenever AAPT2 link ran or BuildConfig changed
        val buildConfigDir = File(buildRoot, "generated/buildconfig")
        val buildConfigFiles = writeBuildConfigs(modules, variant, buildConfigDir)
        val buildConfigChanged = cache.changed("buildconfig", buildConfigFiles)
        if (resManifestChanged || buildConfigChanged) {
            emit(BuildEvent.Log("▶ Java compile R.java + BuildConfig"))
            val logs = mutableListOf<String>()
            val ok = JavaCompileStep(toolsManager).compile(
                sourceRoots = listOf(rJavaDir, buildConfigDir),
                classesDir = classesDir,
                extraClasspath = baseClasspath,
                applyHiddenApiBypass = { kotlinDaemon.applyHiddenApiBypass() },
            ) { logs += it }
            logs.forEach { emit(BuildEvent.Log(it)) }
            if (!ok) {
                emit(BuildEvent.Failure("R.java compilation failed — check errors above"))
                return
            }
            cache.markClean("buildconfig", buildConfigFiles)
            recompiled = true
        } else {
            emit(BuildEvent.Log("↩ Skipped R.java compile (resources unchanged)"))
        }

        // 3b: Kotlin — skipped when no sources changed (kapt and Compose always force a full compile)
        val kaptGeneratedSourcesDir = if (kaptProcessorJars.isNotEmpty()) File(buildRoot, "kapt-generated").also { it.mkdirs() } else null
        // Kotlin reads the Java sources too, so a Java change can change what Kotlin compiles to.
        val ktChanged = ktFiles.isNotEmpty() && cache.changed("sources-kt", ktFiles + javaUserFiles + baseClasspath)
        val forceFullKt = kaptProcessorJars.isNotEmpty() || composePluginJar != null
        if (ktFiles.isNotEmpty() && (forceFullKt || ktChanged || resManifestChanged || buildConfigChanged)) {
            step("Kotlin compile") { log ->
                val ok = KotlinCompileStep(toolsManager).compile(
                    sourceRoots = sourceRoots,
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
            if (!forceFullKt) cache.markClean("sources-kt", ktFiles + javaUserFiles + baseClasspath)
            recompiled = true
        } else if (ktFiles.isNotEmpty()) {
            emit(BuildEvent.Log("↩ Skipped Kotlin compile (sources unchanged)"))
        }

        // 3c: Java (ECJ) user sources, with kapt-generated sources
        val generatedSourcesDir = File(buildRoot, "generated-sources").also { it.mkdirs() }
        val javaSourceRoots = buildList {
            addAll(sourceRoots)
            kaptGeneratedSourcesDir?.takeIf { it.exists() && it.walkTopDown().any { f -> f.extension == "java" } }?.let { add(it) }
        }
        val javaChanged = javaUserFiles.isNotEmpty() && cache.changed("sources-java", javaUserFiles + baseClasspath)
        if (kaptProcessorJars.isNotEmpty() || javaChanged || (javaUserFiles.isNotEmpty() && (resManifestChanged || buildConfigChanged))) {
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
                return
            }
            // Second pass to compile generated sources (from annotation processors)
            if (processorJars.isNotEmpty()) {
                val generatedFiles = generatedSourcesDir.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
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
                        return
                    }
                }
            }
            if (kaptProcessorJars.isEmpty()) cache.markClean("sources-java", javaUserFiles + baseClasspath)
            recompiled = true
        } else if (javaUserFiles.isNotEmpty()) {
            emit(BuildEvent.Log("↩ Skipped Java compile (sources unchanged)"))
        }

        // ── Step 4: Dex ────────────────────────────────────────────────────
        // The app needs the Kotlin stdlib at run time (the bundled AndroidX uses it too), unless
        // a dependency already brought its own copy.
        val stdlibJar = toolsManager.kotlinStdlibJar.takeUnless { userLibs.hasKotlinStdlib }
        // The bundled AndroidX dex was desugared for API 21, so interface default methods became
        // static helpers. Code that implements those interfaces has to be desugared the same
        // way, or calls hit AbstractMethodError (DefaultLifecycleObserver in Compose).
        val dexMinApi = minOf(app.minSdk, BUNDLED_DEX_MIN_API)
        val dexRoot = File(buildRoot, "dex")
        // Dex made with other settings is redone (each step below re-dexes an empty folder).
        val dexSettings = File(buildRoot, "dex-settings.txt")
        if (!dexSettings.exists() || dexSettings.readText() != "min-api $dexMinApi") {
            dexRoot.deleteRecursively()
            dexSettings.writeText("min-api $dexMinApi")
        }
        val dexFiles = mutableListOf<File>()
        if (variant == BuildVariant.RELEASE) {
            val rules = collectProguardFiles(buildRoot, aaptRules, app, libraryModules, userLibs)
            // R classes skip R8: they're only constants, there are one per library package with
            // every resource in it (too big for R8 in a phone's heap), and the bundled AndroidX,
            // which R8 can't see into, uses them. R8 reads them as library classes.
            val (rClasses, codeClasses) = splitRClasses(classesDir, rJavaDir)
            val rJar = File(dexRoot, "r-classes.jar")
            writeJar(rJar, classesDir, rClasses)
            // The Kotlin stdlib skips R8 too: the bundled AndroidX (library classes to R8) extends
            // its classes, and R8 doesn't allow library classes to depend on program classes.
            val libsJar = userLibs.classesJar
            val (programLibs, kotlinStdlib) = if (libsJar != null && userLibs.hasKotlinStdlib) {
                val rest = File(dexRoot, "user-libs-without-stdlib.jar")
                val stdlib = File(dexRoot, "user-kotlin-stdlib.jar")
                splitJar(libsJar, { it.startsWith("kotlin/") }, stdlib, rest)
                rest to stdlib
            } else libsJar to toolsManager.kotlinStdlibJar
            val r8Dir = File(dexRoot, "r8")
            val r8Inputs = codeClasses + listOfNotNull(programLibs)
            if (recompiled || cache.changed("r8", r8Inputs + rules) || DexStep.dexFiles(r8Dir).isEmpty()) {
                // R8 needs most of the heap; the warm Kotlin compiler holds hundreds of MB.
                kotlinDaemon.clearCache()
                System.gc()
                step("R8 (shrink+obfuscate)") { log ->
                    DexStep(toolsManager).convert(
                        inputs = r8Inputs,
                        dexOutputDir = r8Dir,
                        minSdk = dexMinApi,
                        emit = log,
                        classpath = listOf(toolsManager.androidxClassesJar, rJar, kotlinStdlib) + compileOnlyJars,
                        variant = variant,
                        proguardRulesFiles = rules,
                    )
                }
                cache.markClean("r8", r8Inputs + rules)
            }
            val rDexDir = File(dexRoot, "r")
            if (cache.changed("dex-r", rJar) || DexStep.dexFiles(rDexDir).isEmpty()) {
                step("DEX (D8) R classes") { log ->
                    DexStep(toolsManager).convert(listOf(rJar), rDexDir, dexMinApi, log, variant = variant)
                }
                cache.markClean("dex-r", rJar)
            }
            dexFiles += DexStep.dexFiles(r8Dir)
            dexFiles += DexStep.dexFiles(rDexDir)
            dexFiles += toolsManager.androidxClasses2Dex
            dexFiles += stdlibDex(kotlinStdlib, dexMinApi) { line -> Log.d("BuildEngine", line) }
        } else {
            // Debug: the app's classes, its libraries and the stdlib are dexed separately, so a
            // code change only re-dexes the app's own classes.
            val appDexDir = File(dexRoot, "app")
            if (recompiled || DexStep.dexFiles(appDexDir).isEmpty()) {
                step("DEX (D8)") { log ->
                    DexStep(toolsManager).convert(
                        inputs = listOf(classesDir),
                        dexOutputDir = appDexDir,
                        minSdk = dexMinApi,
                        emit = log,
                        classpath = listOfNotNull(toolsManager.androidxClassesJar, userLibs.classesJar, stdlibJar) + compileOnlyJars,
                    )
                }
            }
            dexFiles += DexStep.dexFiles(appDexDir)
            // Before the dependencies: the app compiles against the bundled AndroidX, so a class
            // that's in both must resolve to the bundled copy at run time too.
            dexFiles += toolsManager.androidxClasses2Dex
            userLibs.classesJar?.let { libsJar ->
                val libsDexDir = File(dexRoot, "libs")
                if (cache.changed("dex-libs", libsJar) || DexStep.dexFiles(libsDexDir).isEmpty()) {
                    step("DEX (D8) dependencies") { log ->
                        DexStep(toolsManager).convert(
                            inputs = listOf(libsJar),
                            dexOutputDir = libsDexDir,
                            minSdk = dexMinApi,
                            emit = log,
                            classpath = listOf(toolsManager.androidxClassesJar),
                        )
                    }
                    cache.markClean("dex-libs", libsJar)
                }
                dexFiles += DexStep.dexFiles(libsDexDir)
            }
            stdlibJar?.let { dexFiles += stdlibDex(it, dexMinApi) { line -> Log.d("BuildEngine", line) } }
        }

        // ── Step 5: Package and sign ───────────────────────────────────────
        val apkName = if (variant == BuildVariant.RELEASE) "app-release.apk" else "app-debug.apk"
        val signedApk = File(buildRoot, apkName)
        val packedApk = File(buildRoot, "packed.apk")
        val assetsDirs = userLibs.assetsDirs + modules.mapNotNull { it.assetsDir }
        val nativeLibDirs = userLibs.jniDirs + modules.mapNotNull { it.jniLibsDir }
        // The dex order matters (the first copy of a class wins), and the cache sorts files.
        val dexOrder = writeIfChanged(File(buildRoot, "dex-order.txt"), dexFiles.joinToString("\n"))
        val packInputs = buildList {
            add(dexOrder)
            add(unsignedResApk)
            addAll(dexFiles)
            (assetsDirs + nativeLibDirs).forEach { dir -> dir.walkTopDown().filter { it.isFile }.forEach { add(it) } }
            keystoreConfig?.let { add(File(it.keystorePath)) }
        }
        if (!cache.changed("pack+sign", packInputs) && signedApk.exists()) {
            emit(BuildEvent.Log("↩ Skipped pack + sign (nothing changed)"))
        } else {
            step("Package APK") { log ->
                ApkPackager().pack(
                    aapt2Apk = unsignedResApk,
                    dexFiles = dexFiles,
                    outputApk = packedApk,
                    emit = log,
                    assetsDirs = assetsDirs,
                    nativeLibDirs = nativeLibDirs,
                )
            }
            val variantLabel = if (variant == BuildVariant.RELEASE) "release" else "debug"
            step("Sign APK ($variantLabel, v1+v2)") { log ->
                apkSigner.sign(packedApk, signedApk, log, keystoreConfig, variant)
            }
            cache.markClean("pack+sign", packInputs)
        }

        emit(BuildEvent.Log("✓ Build successful: ${signedApk.absolutePath}"))
        emit(BuildEvent.Success(signedApk.absolutePath, app.applicationId))
    }

    /** [app] and the library modules it depends on (directly or not), dependencies first. */
    private fun dependencyClosure(app: ModuleInfo, modules: List<ModuleInfo>): List<ModuleInfo> {
        val byName = modules.associateBy { it.name }
        val needed = mutableSetOf<String>()
        fun visit(m: ModuleInfo) {
            if (!needed.add(m.name)) return
            m.moduleDeps.forEach { dep -> byName[dep]?.let(::visit) }
        }
        visit(app)
        return modules.filter { it.name in needed }
    }

    /** Builds used to keep their outputs directly in builds/<project>; they now go in debug/ and release/. */
    private fun removeOldLayout(projectBuildDir: File) {
        projectBuildDir.listFiles()
            ?.filter { it.name != "debug" && it.name != "release" }
            ?.forEach { it.deleteRecursively() }
    }

    /** A Kotlin stdlib jar dexed for [minSdk], dexed once per jar content and shared by every project. */
    private fun stdlibDex(stdlibJar: File, minSdk: Int, emit: (String) -> Unit): List<File> {
        val crc = java.util.zip.CRC32().also { c -> stdlibJar.forEachBlock { b, n -> c.update(b, 0, n) } }.value
        val dir = File(context.filesDir, "dex-cache/kotlin-stdlib-${crc.toString(16)}-api$minSdk")
        val existing = DexStep.dexFiles(dir)
        if (existing.isNotEmpty()) return existing
        val tmp = File(dir.parentFile, dir.name + ".tmp")
        DexStep(toolsManager).convert(listOf(stdlibJar), tmp, minSdk, emit)
        dir.deleteRecursively()
        tmp.renameTo(dir)
        return DexStep.dexFiles(dir)
    }

    /**
     * Writes BuildConfig.java for the app module and for library modules that turn it on, into
     * [dir] (cleared first). Returns the files written.
     */
    private fun writeBuildConfigs(modules: List<ModuleInfo>, variant: BuildVariant, dir: File): List<File> {
        dir.deleteRecursively()
        val debug = variant == BuildVariant.DEBUG
        val buildType = variant.name.lowercase()
        return modules.filter { it.buildConfig && it.namespace.isNotBlank() }.map { m ->
            val fields = buildList {
                add("public static final boolean DEBUG = $debug;")
                if (m.type == ModuleType.APPLICATION) {
                    add("public static final String APPLICATION_ID = \"${m.applicationId}\";")
                } else {
                    add("public static final String LIBRARY_PACKAGE_NAME = \"${m.namespace}\";")
                }
                add("public static final String BUILD_TYPE = \"$buildType\";")
                if (m.type == ModuleType.APPLICATION) {
                    add("public static final int VERSION_CODE = ${m.versionCode ?: 1};")
                    add("public static final String VERSION_NAME = \"${m.versionName ?: ""}\";")
                }
                m.buildConfigFields.forEach { add("public static final ${it.type} ${it.name} = ${it.value};") }
            }
            val file = File(dir, m.namespace.replace('.', '/') + "/BuildConfig.java")
            file.parentFile?.mkdirs()
            file.writeText(buildString {
                appendLine("// Generated by Bori Code from ${m.name}'s build file. Do not edit.")
                appendLine("package ${m.namespace};")
                appendLine()
                appendLine("public final class BuildConfig {")
                fields.forEach { appendLine("    $it") }
                appendLine("}")
            })
            file
        }
    }

    // Copies res/ to a build-dir snapshot so the compiled-res output stays isolated from srcRes.
    // Also fixes framework theme parents that crash AppCompatActivity (android:Theme.Material.*
    // is a framework theme; AppCompatActivity requires Theme.AppCompat.*).
    private fun snapshotResources(srcRes: File, dest: File): File {
        if (!srcRes.exists()) return srcRes
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
    // (missing mipmap icon refs, dataExtractionRules, fullBackupContent).
    private fun patchManifest(src: File, buildRoot: File, packageName: String, resRoot: File?): File {
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
        // Strip AGP-generated backup/extraction attributes when they reference absent res files
        text = Regex("\\s*android:(dataExtractionRules|fullBackupContent)\\s*=\\s*\"@xml/([^\"]*)\"").replace(text) { m ->
            if (resRoot != null && File(resRoot, "xml").listFiles()?.any { it.nameWithoutExtension == m.groupValues[2] } == true) m.value else ""
        }

        val patched = File(buildRoot, "AndroidManifest.xml")
        patched.writeText(text)
        return patched
    }

    /**
     * True when [resRoot] contains a mipmap resource named [name] (any density qualifier,
     * any file extension) — e.g. res/mipmap-mdpi/ic_launcher.png for name "ic_launcher".
     */
    private fun mipmapResourceExists(resRoot: File?, name: String): Boolean {
        if (resRoot?.isDirectory != true) return false
        return resRoot.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap") }
            ?.any { dir -> dir.listFiles()?.any { it.isFile && it.nameWithoutExtension == name } == true }
            ?: false
    }

    private fun readPackagesList(packagesTxt: File): List<String> {
        if (!packagesTxt.exists()) return emptyList()
        return packagesTxt.readLines().map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * R8 rules for a release build: the default Android keep rules, the keep rules AAPT2 wrote
     * for the manifest and layouts, the app's proguardFiles, and the consumer rules of library
     * modules and dependencies.
     */
    private fun collectProguardFiles(
        buildRoot: File,
        aaptRules: File,
        app: ModuleInfo,
        libraryModules: List<ModuleInfo>,
        userLibs: UserLibraries,
    ): List<File> {
        val defaultRules = File(buildRoot, "default-proguard-rules.pro")
        defaultRules.writeText(com.bori.core.build.steps.DefaultProguardRules.RULES)
        return buildList {
            add(defaultRules)
            if (aaptRules.exists()) add(aaptRules)
            addAll(app.proguardFiles)
            libraryModules.forEach { addAll(it.proguardFiles) }
            addAll(userLibs.proguardFiles)
        }
    }

    /**
     * Splits the class files in [classesDir] into the R classes AAPT2 generated (R and R$* in each
     * package that has an R.java under [rJavaDir]) and everything else.
     */
    private fun splitRClasses(classesDir: File, rJavaDir: File): Pair<List<File>, List<File>> {
        val rPackages = rJavaDir.walkTopDown().filter { it.name == "R.java" }.map { it.parentFile.relativeTo(rJavaDir).path }.toSet()
        return classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.partition { f ->
            (f.name == "R.class" || f.name.startsWith("R$")) && f.parentFile.relativeTo(classesDir).path in rPackages
        }
    }

    /** Copies [src]'s entries into [matching] or [rest] by name, with fixed times (see [writeJar]). */
    private fun splitJar(src: File, predicate: (String) -> Boolean, matching: File, rest: File) {
        java.util.zip.ZipFile(src).use { zip ->
            java.util.zip.ZipOutputStream(matching.outputStream().buffered()).use { a ->
                java.util.zip.ZipOutputStream(rest.outputStream().buffered()).use { b ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory) continue
                        val out = if (predicate(entry.name)) a else b
                        out.putNextEntry(java.util.zip.ZipEntry(entry.name).apply { time = 315532800000L })
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
        }
    }

    /** Writes [files] (under [root]) to [jar], with fixed times so the same classes give the same bytes. */
    private fun writeJar(jar: File, root: File, files: List<File>) {
        jar.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(jar.outputStream().buffered()).use { out ->
            for (f in files.sortedBy { it.path }) {
                out.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(root).invariantSeparatorsPath).apply { time = 315532800000L })
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    /** Writes [text] to [file] only when it differs, so the file's content tracks the setting. */
    private fun writeIfChanged(file: File, text: String): File {
        if (!file.exists() || file.readText() != text) file.writeText(text)
        return file
    }

    companion object {
        // Compose compiler that matches the pinned on-device Kotlin 1.8.22 (see core/build/build.gradle.kts).
        private const val COMPOSE_COMPILER_VERSION = "1.4.8"

        /** The --min-api copyBuildTools dexes androidx-classes2.dex with. */
        private const val BUNDLED_DEX_MIN_API = 21
    }
}
