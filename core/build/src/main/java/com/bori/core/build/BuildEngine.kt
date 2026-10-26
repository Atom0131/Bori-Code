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
import com.bori.core.build.steps.BundlePackager
import com.bori.core.build.steps.DexStep
import com.bori.core.build.steps.JavaCompileStep
import com.bori.core.build.steps.KotlinCompileStep
import com.bori.core.build.steps.LibraryManifest
import com.bori.core.build.steps.ManifestMerger
import com.bori.core.build.steps.MavenResolver
import com.bori.core.build.steps.ResolvedArtifact
import com.bori.core.build.steps.UserLibraries
import com.bori.core.build.steps.UserLibraryMerger
import com.bori.core.build.steps.ViewBindingGenerator
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
        /** Release only: make an Android App Bundle (.aab, for Google Play) instead of an APK. */
        appBundle: Boolean = false,
    ): Flow<BuildEvent> = flow {
        emit(BuildEvent.Started)
        try {
            runBuild(project, kotlinTimeoutSeconds, compilerProfile, keystoreConfig, variant, appBundle && variant == BuildVariant.RELEASE)
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
        appBundle: Boolean,
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
        val repos = ModuleParser.repositories(projectRoot)
        if (repos.isNotEmpty()) emit(BuildEvent.Log("Extra repositories: ${repos.joinToString()}"))
        val resolver = MavenResolver(mavenCacheDir, repos)
        val platforms = modules.flatMap { it.platformDeps }.distinct()
        suspend fun resolve(label: String, coords: List<String>): List<ResolvedArtifact> {
            if (coords.isEmpty()) return emptyList()
            emit(BuildEvent.Log("▶ Resolving ${coords.size} $label"))
            val logs = mutableListOf<String>()
            val artifacts = try {
                resolver.resolve(coords, { logs += it }, platforms)
            } catch (e: Exception) {
                logs += "⚠ Resolution failed: ${e.message}"
                emptyList()
            }
            logs.forEach { emit(BuildEvent.Log(it)) }
            return artifacts
        }

        // View Binding classes implement androidx.viewbinding.ViewBinding, which AGP adds for you.
        val viewBindingDeps = if (modules.any { it.viewBinding }) listOf("androidx.databinding:viewbinding:$VIEWBINDING_VERSION") else emptyList()
        // @Parcelize's annotation and runtime, which the Kotlin Gradle plugin adds for kotlin-parcelize.
        val parcelizeDeps = if (modules.any { it.parcelizePlugin }) listOf("org.jetbrains.kotlin:kotlin-parcelize-runtime:$KOTLIN_VERSION") else emptyList()
        val userDeps = (modules.flatMap { it.mavenDeps } + viewBindingDeps + parcelizeDeps).distinct()
        val depArtifacts = resolve("dependency(-ies)", userDeps) + localArtifacts(modules)

        // The built-in AndroidX set is used unless the project needs a newer version of one of
        // its libraries; then everything comes from Maven, as in Gradle, so versions never mix.
        val bundledVersions = readBundledVersions(toolsManager.androidxVersionsTxt, toolsManager.androidxClassesJar)
        val needsNewer = depArtifacts.mapNotNull { a ->
            val bundled = bundledVersions[a.groupArtifact] ?: return@mapNotNull null
            val wanted = a.coord.substringAfterLast(':')
            if (MavenResolver.compareVersions(wanted, bundled) > 0) "${a.groupArtifact} $wanted (built-in $bundled)" else null
        }
        val bundle = if (needsNewer.isEmpty()) {
            Bundle(
                classesJar = toolsManager.androidxClassesJar,
                classes2Dex = toolsManager.androidxClasses2Dex,
                resFlatDir = toolsManager.androidxResFlatDir,
                manifestEntries = toolsManager.androidxManifestEntriesXml,
                packages = readPackagesList(toolsManager.androidxPackagesTxt),
            )
        } else {
            emit(BuildEvent.Log("AndroidX from Maven instead of the built-in set, which is older than: ${needsNewer.take(4).joinToString()}${if (needsNewer.size > 4) " and ${needsNewer.size - 4} more" else ""}"))
            null
        }
        val merger = UserLibraryMerger(
            aapt2 = toolsManager.aapt2,
            aarCacheDir = File(context.filesDir, "aar-cache"),
            bundledClassesJar = bundle?.classesJar,
            bundledPackages = bundle?.packages.orEmpty().toSet(),
        )
        var userLibs = UserLibraries.EMPTY
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
        // Symbol processors (Room, Hilt/Dagger, Moshi…) run through KSP inside the on-device
        // compiler. kapt and javac annotation processing need javac, which Android doesn't have, so
        // kapt(...) and annotationProcessor(...) processors that also support KSP run through KSP.
        val kspCoords = mutableListOf<String>()
        val noKsp = mutableListOf<String>()
        for (m in modules) {
            kspCoords += m.kspProcessors
            for (coord in m.kaptProcessors + m.annotationProcessors) {
                kspEquivalent(coord)?.let { kspCoords += it } ?: run { noKsp += coord }
            }
        }
        if (noKsp.isNotEmpty()) {
            emit(BuildEvent.Log("⚠ kapt/annotationProcessor can't run on the phone (no javac) and these have no KSP version, so they're skipped: ${noKsp.distinct().joinToString()}", BuildEvent.Log.Level.WARN))
        }
        val processors = resolve("symbol processor(s)", kspCoords.distinct())
            .filter { a -> a.groupArtifact !in KSP_PROVIDED && !a.groupArtifact.startsWith("org.jetbrains.kotlin:kotlin-stdlib") }
        var processorDex: List<File> = emptyList()
        if (processors.isNotEmpty()) {
            step("Prepare ${processors.size} symbol processor jar(s)") { log -> processorDex = processors.flatMap { processorDex(it, log) } }
        }
        val hilt = modules.any { it.hiltPlugin } || processors.any { it.groupArtifact == "com.google.dagger:hilt-compiler" }
        val apOptions = buildList {
            modules.forEach { m -> m.kspArgs.forEach { (k, v) -> add("$k=$v") } }
            if (processors.any { it.groupArtifact.startsWith("com.google.dagger:") }) {
                // google-java-format needs javac; the code compiles the same unformatted.
                add("dagger.formatGeneratedSource=disabled")
            }
            // The superclass is switched to Hilt_… after compiling (HiltTransform), as the Hilt plugin does.
            if (hilt) add("dagger.hilt.android.internal.disableAndroidSuperclassValidation=true")
        }

        // Kotlin compiler plugins, bundled with the app (Compose, kotlinx.serialization, Parcelize).
        val composeOn = modules.any { it.composeEnabled } ||
            (userDeps + platforms).any { it.substringBefore(':').startsWith("androidx.compose") }
        val compilerPlugins = buildList {
            if (composeOn) add(toolsManager.composePluginJar)
            if (modules.any { it.serializationPlugin }) add(toolsManager.serializationPluginJar)
            if (modules.any { it.parcelizePlugin }) add(toolsManager.parcelizePluginJar)
        }

        // Core library desugaring: java.time, streams… on Android versions that lack them.
        val desugarConfig: File?
        var desugarLib: File? = null
        if (app.coreLibraryDesugaring) {
            val coords = app.desugarLibs.ifEmpty { listOf("com.android.tools:desugar_jdk_libs:$DESUGAR_JDK_LIBS_VERSION") }
            val artifacts = resolve("desugaring library", coords)
            desugarLib = artifacts.firstOrNull { it.groupArtifact == "com.android.tools:desugar_jdk_libs" }?.file
            val configJar = artifacts.firstOrNull { it.groupArtifact == "com.android.tools:desugar_jdk_libs_configuration" }?.file
            desugarConfig = configJar?.let { extractDesugarConfig(it, File(buildRoot, "desugar.json")) }
            if (desugarLib == null || desugarConfig == null) {
                emit(BuildEvent.Log("⚠ Core library desugaring is on but desugar_jdk_libs couldn't be resolved; building without it", BuildEvent.Log.Level.WARN))
                desugarLib = null
            }
        } else desugarConfig = null

        // The libraries' own jars, not the merged one: compiler plugins read their manifests.
        val libraryJars = userLibs.jars.ifEmpty { listOfNotNull(userLibs.classesJar) }
        val baseClasspath = listOfNotNull(bundle?.classesJar) + libraryJars + compileOnlyJars
        val sourceRoots = modules.flatMap { it.sourceDirs }
        val ktFiles = sourceRoots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val javaUserFiles = sourceRoots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "java" }.toList() }

        // ── Step 2: Resources and manifest ─────────────────────────────────
        val compiledResRoot = File(buildRoot, "compiled-res")
        val rJavaDir = File(buildRoot, "r/java")
        val unsignedResApk = File(buildRoot, "res.apk")
        val aaptRules = File(buildRoot, "aapt-rules.pro")
        val resIds = File(buildRoot, "res-ids.txt")
        val protoResApk = File(buildRoot, "res-proto.apk")
        val libraryManifests = libraryModules.mapNotNull { m -> m.manifest?.let { LibraryManifest(it, m.namespace) } } +
            userLibs.manifests +
            listOfNotNull(bundle?.let { LibraryManifest(it.manifestEntries, "") })
        // Everything that changes what AAPT2 links, besides the files themselves.
        val linkSettings = writeIfChanged(File(buildRoot, "link-settings.txt"), listOf(
            app.applicationId, app.namespace, app.versionCode, app.versionName, app.minSdk, app.targetSdk,
            userLibs.resFlatDirs.joinToString(), userLibs.packages.joinToString(), libraryManifests.joinToString { it.file.path },
            if (bundle != null) "bundle" else "maven",
        ).joinToString("\n"))
        val resInputs = buildList {
            modules.mapNotNull { it.resDir }.forEach { dir -> dir.walkTopDown().filter { it.isFile }.forEach { add(it) } }
            libraryManifests.forEach { add(it.file) }
            add(appManifest)
            add(linkSettings)
        }
        val resManifestChanged = cache.changed("res+manifest", resInputs) || !unsignedResApk.exists() ||
            (appBundle && (!protoResApk.exists() || !resIds.exists()))

        if (resManifestChanged) {
            val flatDirs = mutableListOf<File>()
            bundle?.let { flatDirs += it.resFlatDir }
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
            val extraPackages = (bundle?.packages.orEmpty() + userLibs.packages + libraryModules.map { it.namespace })
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
                    emitIds = resIds,
                )
            }
            if (appBundle) {
                // The same resources again in the proto format Play reads, with the same IDs.
                step("AAPT2 link (app bundle)") { log ->
                    AaptStep(toolsManager).link(
                        manifestFile = manifestForLink,
                        flatDirs = flatDirs,
                        rJavaDir = null,
                        apkOut = protoResApk,
                        emit = log,
                        minSdk = app.minSdk,
                        targetSdk = app.targetSdk,
                        versionCode = app.versionCode,
                        versionName = app.versionName,
                        renameManifestPackage = app.applicationId.takeIf { it.isNotBlank() && it != app.namespace },
                        protoFormat = true,
                        stableIds = resIds,
                    )
                }
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
        val buildConfigFiles = writeBuildConfigs(modules, variant, buildConfigDir) + writeViewBindings(modules, File(buildRoot, "generated/viewbinding"))
        val buildConfigChanged = cache.changed("buildconfig", buildConfigFiles)
        if (resManifestChanged || buildConfigChanged) {
            emit(BuildEvent.Log("▶ Java compile R.java + BuildConfig${if (modules.any { it.viewBinding }) " + View Binding" else ""}"))
            val logs = mutableListOf<String>()
            val ok = JavaCompileStep(toolsManager).compile(
                sourceRoots = listOf(rJavaDir, buildConfigDir, File(buildRoot, "generated/viewbinding")),
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

        // 3b: KSP. It reads the Kotlin and Java sources and writes generated sources, which the
        // Kotlin and Java compiles below then include. It runs on every build that has processors.
        val kspRoot = File(buildRoot, "ksp")
        val kspKotlinOut = File(kspRoot, "kotlin")
        val kspJavaOut = File(kspRoot, "java")
        val kspClassesOut = File(kspRoot, "classes")
        val kspResourcesOut = File(kspRoot, "resources")
        if (processors.isNotEmpty()) {
            listOf(kspKotlinOut, kspJavaOut, kspClassesOut, kspResourcesOut).forEach { it.deleteRecursively(); it.mkdirs() }
            step("KSP (${processors.size} processor jar(s))") { log ->
                val p = "plugin:com.google.devtools.ksp.symbol-processing"
                val options = listOf(
                    "$p:apclasspath=${processors.joinToString(File.pathSeparator) { it.file.absolutePath }}",
                    "$p:projectBaseDir=${projectRoot.absolutePath}",
                    "$p:classOutputDir=${kspClassesOut.absolutePath}",
                    "$p:javaOutputDir=${kspJavaOut.absolutePath}",
                    "$p:kotlinOutputDir=${kspKotlinOut.absolutePath}",
                    "$p:resourceOutputDir=${kspResourcesOut.absolutePath}",
                    "$p:kspOutputDir=${kspRoot.absolutePath}",
                    "$p:cachesDir=${File(kspRoot, "caches").absolutePath}",
                    "$p:incremental=false",
                ) + apOptions.map { "$p:apoption=$it" }
                val ok = KotlinCompileStep(toolsManager).compile(
                    sourceRoots = sourceRoots,
                    classesDir = File(kspRoot, "analysis-out").also { it.mkdirs() },
                    extraClasspath = listOf(classesDir) + baseClasspath,
                    timeoutSeconds = kotlinTimeoutSeconds,
                    daemon = kotlinDaemon,
                    profile = compilerProfile,
                    compilerPlugins = listOf(toolsManager.kspPluginJar),
                    pluginOptions = options,
                    extraLoaderDexJars = processorDex,
                    k1 = true, // KSP1 runs on the K1 frontend
                    runWithoutKotlin = true,
                    emit = log,
                )
                if (!ok) throw RuntimeException("Symbol processing (KSP) failed — check errors above")
                val generated = listOf(kspKotlinOut, kspJavaOut).sumOf { d -> d.walkTopDown().count { it.isFile } }
                log("KSP: $generated generated source file(s)")
                kspClassesOut.takeIf { d -> d.walkTopDown().any { it.isFile } }?.copyRecursively(classesDir, overwrite = true)
            }
        }
        val generatedRoots = if (processors.isEmpty()) emptyList() else listOf(kspKotlinOut, kspJavaOut)
        val kotlinRoots = sourceRoots + generatedRoots
        val allKtFiles = ktFiles + (if (processors.isEmpty()) emptyList() else kspKotlinOut.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList())

        // 3c: Kotlin — skipped when no sources changed (plugins and KSP always force a full compile)
        // Kotlin reads the Java sources too, so a Java change can change what Kotlin compiles to.
        val ktChanged = allKtFiles.isNotEmpty() && cache.changed("sources-kt", ktFiles + javaUserFiles + baseClasspath)
        val forceFullKt = processors.isNotEmpty() || compilerPlugins.isNotEmpty()
        if (allKtFiles.isNotEmpty() && (forceFullKt || ktChanged || resManifestChanged || buildConfigChanged)) {
            step("Kotlin compile") { log ->
                val ok = KotlinCompileStep(toolsManager).compile(
                    sourceRoots = kotlinRoots,
                    classesDir = classesDir,
                    extraClasspath = listOf(classesDir) + baseClasspath,
                    timeoutSeconds = kotlinTimeoutSeconds,
                    daemon = kotlinDaemon,
                    profile = compilerProfile,
                    compilerPlugins = compilerPlugins,
                    emit = log,
                )
                if (!ok) throw RuntimeException("Kotlin compilation failed — check errors above")
            }
            if (!forceFullKt) cache.markClean("sources-kt", ktFiles + javaUserFiles + baseClasspath)
            recompiled = true
        } else if (allKtFiles.isNotEmpty()) {
            emit(BuildEvent.Log("↩ Skipped Kotlin compile (sources unchanged)"))
        }

        // 3d: Java (ECJ): the project's Java sources and the Java that KSP generated
        val javaSourceRoots = sourceRoots + listOf(kspJavaOut).filter { processors.isNotEmpty() && it.walkTopDown().any { f -> f.extension == "java" } }
        val generatedJava = processors.isNotEmpty() && kspJavaOut.walkTopDown().any { it.extension == "java" }
        val javaChanged = javaUserFiles.isNotEmpty() && cache.changed("sources-java", javaUserFiles + baseClasspath)
        if (generatedJava || javaChanged || (javaUserFiles.isNotEmpty() && (resManifestChanged || buildConfigChanged || recompiled))) {
            emit(BuildEvent.Log(if (generatedJava) "▶ Java compile (ECJ + generated sources)" else "▶ Java compile (ECJ)"))
            val compileLogs = mutableListOf<String>()
            val compileOk = JavaCompileStep(toolsManager).compile(
                sourceRoots = javaSourceRoots,
                classesDir = classesDir,
                extraClasspath = baseClasspath,
                applyHiddenApiBypass = { kotlinDaemon.applyHiddenApiBypass() },
            ) { compileLogs += it }
            compileLogs.forEach { emit(BuildEvent.Log(it)) }
            if (!compileOk) {
                emit(BuildEvent.Failure("Java compilation failed — check errors above"))
                return
            }
            if (!generatedJava) cache.markClean("sources-java", javaUserFiles + baseClasspath)
            recompiled = true
        } else if (javaUserFiles.isNotEmpty()) {
            emit(BuildEvent.Log("↩ Skipped Java compile (sources unchanged)"))
        }

        // 3e: Hilt entry points extend their generated Hilt_ classes (the Hilt Gradle plugin's transform)
        if (hilt && recompiled) {
            step("Hilt entry points") { log ->
                val n = com.bori.core.build.steps.HiltTransform.apply(classesDir, log)
                if (n == 0) log("Hilt: no @AndroidEntryPoint / @HiltAndroidApp classes to update")
            }
        }

        // ── Step 4: Dex ────────────────────────────────────────────────────
        // The app needs the Kotlin stdlib at run time (the bundled AndroidX uses it too), unless
        // a dependency already brought its own copy.
        val stdlibJar = toolsManager.kotlinStdlibJar.takeUnless { userLibs.hasKotlinStdlib }
        // The bundled AndroidX dex was desugared for API 21, so interface default methods became
        // static helpers. Code that implements those interfaces has to be desugared the same
        // way, or calls hit AbstractMethodError (DefaultLifecycleObserver in Compose).
        val dexMinApi = if (bundle != null) minOf(app.minSdk, BUNDLED_DEX_MIN_API) else app.minSdk
        val dexRoot = File(buildRoot, "dex")
        // Dex made with other settings is redone (each step below re-dexes an empty folder).
        val dexSettings = File(buildRoot, "dex-settings.txt")
        val dexSettingsText = "min-api $dexMinApi\ndesugar ${desugarLib?.name}\nbundle ${bundle != null}"
        if (!dexSettings.exists() || dexSettings.readText() != dexSettingsText) {
            dexRoot.deleteRecursively()
            dexSettings.writeText(dexSettingsText)
        }
        val bundleClasspath = listOfNotNull(bundle?.classesJar)
        val dexFiles = mutableListOf<File>()
        // R8 only when the build type asks for it (isMinifyEnabled), as in AGP.
        val useR8 = variant == BuildVariant.RELEASE && app.minifyEnabled
        if (useR8) {
            val rules = collectProguardFiles(buildRoot, aaptRules, app, libraryModules, userLibs) +
                kspResourcesOut.walkTopDown().filter { processors.isNotEmpty() && it.isFile && it.extension == "pro" }.toList()
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
                        classpath = bundleClasspath + listOf(rJar, kotlinStdlib) + compileOnlyJars,
                        variant = variant,
                        proguardRulesFiles = rules,
                        desugaredLibConfig = desugarConfig,
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
            bundle?.let { dexFiles += it.classes2Dex }
            dexFiles += stdlibDex(kotlinStdlib, dexMinApi) { line -> Log.d("BuildEngine", line) }
        } else {
            // The app's classes, its libraries and the stdlib are dexed separately, so a code
            // change only re-dexes the app's own classes.
            val appDexDir = File(dexRoot, "app")
            if (recompiled || DexStep.dexFiles(appDexDir).isEmpty()) {
                step("DEX (D8)") { log ->
                    DexStep(toolsManager).convert(
                        inputs = listOf(classesDir),
                        dexOutputDir = appDexDir,
                        minSdk = dexMinApi,
                        emit = log,
                        classpath = bundleClasspath + listOfNotNull(userLibs.classesJar, stdlibJar) + compileOnlyJars,
                        variant = variant,
                        desugaredLibConfig = desugarConfig,
                    )
                }
            }
            dexFiles += DexStep.dexFiles(appDexDir)
            // Before the dependencies: the app compiles against the bundled AndroidX, so a class
            // that's in both must resolve to the bundled copy at run time too.
            bundle?.let { dexFiles += it.classes2Dex }
            userLibs.classesJar?.let { libsJar ->
                val libsDexDir = File(dexRoot, "libs")
                if (cache.changed("dex-libs", libsJar) || DexStep.dexFiles(libsDexDir).isEmpty()) {
                    step("DEX (D8) dependencies") { log ->
                        DexStep(toolsManager).convert(
                            inputs = listOf(libsJar),
                            dexOutputDir = libsDexDir,
                            minSdk = dexMinApi,
                            emit = log,
                            classpath = bundleClasspath,
                            variant = variant,
                            desugaredLibConfig = desugarConfig,
                        )
                    }
                    cache.markClean("dex-libs", libsJar)
                }
                dexFiles += DexStep.dexFiles(libsDexDir)
            }
            stdlibJar?.let { dexFiles += stdlibDex(it, dexMinApi) { line -> Log.d("BuildEngine", line) } }
        }
        if (desugarLib != null && desugarConfig != null) {
            val lib = desugarLib
            step("Desugared library (L8)") { log -> dexFiles += l8Dex(lib, desugarConfig, dexMinApi, log) }
        }

        // ── Step 5: Package and sign ───────────────────────────────────────
        val assetsDirs = userLibs.assetsDirs + modules.mapNotNull { it.assetsDir }
        val nativeLibDirs = userLibs.jniDirs + modules.mapNotNull { it.jniLibsDir }
        // The dex order matters (the first copy of a class wins), and the cache sorts files.
        val dexOrder = writeIfChanged(File(buildRoot, "dex-order.txt"), dexFiles.joinToString("\n"))
        val packInputs = buildList {
            add(dexOrder)
            add(if (appBundle) protoResApk else unsignedResApk)
            addAll(dexFiles)
            (assetsDirs + nativeLibDirs).forEach { dir -> dir.walkTopDown().filter { it.isFile }.forEach { add(it) } }
            keystoreConfig?.let { add(File(it.keystorePath)) }
        }

        if (appBundle) {
            val signedAab = File(buildRoot, "app-release.aab")
            if (!cache.changed("bundle", packInputs) && signedAab.exists()) {
                emit(BuildEvent.Log("↩ Skipped bundle + sign (nothing changed)"))
            } else {
                val unsignedAab = File(buildRoot, "packed.aab")
                step("Package app bundle") { log ->
                    BundlePackager().pack(protoResApk, dexFiles, unsignedAab, log, assetsDirs, nativeLibDirs)
                }
                step("Sign app bundle") { log -> apkSigner.signBundle(unsignedAab, signedAab, log, keystoreConfig) }
                unsignedAab.delete()
                cache.markClean("bundle", packInputs)
            }
            emit(BuildEvent.Log("✓ App bundle ready: ${signedAab.absolutePath}"))
            emit(BuildEvent.Success(signedAab.absolutePath, app.applicationId))
            return
        }

        val apkName = if (variant == BuildVariant.RELEASE) "app-release.apk" else "app-debug.apk"
        val signedApk = File(buildRoot, apkName)
        val packedApk = File(buildRoot, "packed.apk")
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
            step("Sign APK ($variantLabel)") { log ->
                apkSigner.sign(packedApk, signedApk, log, keystoreConfig, variant, app.minSdk)
            }
            cache.markClean("pack+sign", packInputs)
        }

        emit(BuildEvent.Log("✓ Build successful: ${signedApk.absolutePath}"))
        emit(BuildEvent.Success(signedApk.absolutePath, app.applicationId))
    }

    /** The pre-built AndroidX set in the app's assets (see copyBuildTools). */
    private class Bundle(
        val classesJar: File,
        val classes2Dex: File,
        val resFlatDir: File,
        val manifestEntries: File,
        val packages: List<String>,
    )

    /**
     * group:artifact → version of each library in the bundled AndroidX set, from
     * androidx-versions.txt (copyBuildTools), or else from the META-INF/<group>_<artifact>.version
     * files AndroidX jars carry (skipping the ones Gradle left unfilled).
     */
    private fun readBundledVersions(versionsTxt: File, jar: File): Map<String, String> {
        if (versionsTxt.isFile) {
            return versionsTxt.readLines().mapNotNull { line ->
                val parts = line.trim().split(':')
                if (parts.size == 3) "${parts[0]}:${parts[1]}" to parts[2] else null
            }.toMap()
        }
        if (!jar.exists()) return emptyMap()
        return runCatching {
            java.util.zip.ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("META-INF/") && it.name.endsWith(".version") && it.name.count { c -> c == '/' } == 1 }
                    .mapNotNull { e ->
                        val name = e.name.removePrefix("META-INF/").removeSuffix(".version")
                        val group = name.substringBefore('_', "")
                        val artifact = name.substringAfter('_', "")
                        if (group.isEmpty() || artifact.isEmpty() || !group.contains('.')) return@mapNotNull null
                        val version = zip.getInputStream(e).bufferedReader().readText().trim()
                        if (version.firstOrNull()?.isDigit() != true) return@mapNotNull null
                        "$group:$artifact" to version
                    }
                    .toMap()
            }
        }.getOrDefault(emptyMap())
    }

    /** Jars and AARs from files(...) / fileTree(...), as artifacts keyed by file and date. */
    private fun localArtifacts(modules: List<ModuleInfo>): List<ResolvedArtifact> =
        modules.flatMap { it.localLibs }.distinct().map { f ->
            ResolvedArtifact(f, isAar = f.extension == "aar", coord = "local:${f.nameWithoutExtension}:${f.length()}-${f.lastModified()}")
        }

    /** The D8 desugaring configuration from desugar_jdk_libs_configuration. */
    private fun extractDesugarConfig(configJar: File, dest: File): File? = runCatching {
        java.util.zip.ZipFile(configJar).use { zip ->
            val entry = zip.getEntry("META-INF/desugar/d8/desugar.json") ?: return@runCatching null
            val text = zip.getInputStream(entry).bufferedReader().readText()
            if (!dest.exists() || dest.readText() != text) dest.writeText(text)
            dest
        }
    }.getOrNull()

    /** desugar_jdk_libs dexed by L8 for [minSdk], once per library version and shared by every project. */
    private fun l8Dex(desugarJar: File, config: File, minSdk: Int, emit: (String) -> Unit): List<File> {
        val dir = File(context.filesDir, "dex-cache/l8-${desugarJar.nameWithoutExtension}-api$minSdk")
        val existing = DexStep.dexFiles(dir)
        if (existing.isNotEmpty()) return existing
        val tmp = File(dir.parentFile, dir.name + ".tmp")
        DexStep(toolsManager).l8(desugarJar, config, tmp, minSdk, emit)
        dir.deleteRecursively()
        tmp.renameTo(dir)
        return DexStep.dexFiles(dir)
    }

    /**
     * A symbol processor jar dexed so ART can load it in the compiler (KSP loads processors from
     * plain jars, which ART can't run), once per artifact. Returns its read-only dex files.
     */
    private fun processorDex(artifact: ResolvedArtifact, emit: (String) -> Unit): List<File> {
        val key = artifact.coord.ifEmpty { artifact.file.nameWithoutExtension }.replace(':', '_')
        val dir = File(context.filesDir, "processor-dex/v${com.bori.core.build.steps.ProcessorRewriter.VERSION}/$key")
        val existing = DexStep.dexFiles(dir)
        if (existing.isNotEmpty()) return existing
        dir.parentFile?.mkdirs()
        val jar = if (artifact.isAar) {
            // An AAR processor (rare) keeps its classes in classes.jar.
            File(dir.parentFile, "$key-classes.jar").also { out ->
                java.util.zip.ZipFile(artifact.file).use { zip ->
                    zip.getEntry("classes.jar")?.let { e -> zip.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } } }
                }
            }
        } else artifact.file
        val rewritten = File(dir.parentFile, "$key-compat.jar")
        val n = com.bori.core.build.steps.ProcessorRewriter.rewrite(jar, rewritten)
        if (n > 0) emit("${artifact.coord}: $n class(es) adapted for Android")
        val tmp = File(dir.parentFile, "$key.tmp")
        DexStep(toolsManager).convert(listOf(rewritten), tmp, PROCESSOR_DEX_MIN_API, emit)
        rewritten.delete()
        // Android 14+ only loads dex files the app can't write to.
        DexStep.dexFiles(tmp).forEach { it.setReadOnly() }
        dir.deleteRecursively()
        tmp.renameTo(dir)
        return DexStep.dexFiles(dir)
    }

    /** View Binding classes for modules that turn it on, into [dir] (cleared first). */
    private fun writeViewBindings(modules: List<ModuleInfo>, dir: File): List<File> {
        dir.deleteRecursively()
        dir.mkdirs()
        return modules.filter { it.viewBinding && it.namespace.isNotBlank() }.flatMap { m ->
            m.resDir?.let { ViewBindingGenerator.generate(it, m.namespace, dir) }.orEmpty()
        }
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
        /** The on-device Kotlin compiler's version (kotlin-compiler-asset in gradle/libs.versions.toml). */
        private const val KOTLIN_VERSION = "2.0.21"

        /** Processor dex runs in the compiler on this device, so any API level ≥ 26 the app supports will do. */
        private const val PROCESSOR_DEX_MIN_API = 26

        /**
         * Already on the compiler's class loader, so not dexed again for processors. sqlite-jdbc
         * (Room's query check) is replaced by a stub there; its native library can't load on Android.
         */
        private val KSP_PROVIDED = setOf(
            "com.google.devtools.ksp:symbol-processing-api",
            "org.jetbrains.kotlin:kotlin-reflect",
            "org.jetbrains:annotations",
            "org.xerial:sqlite-jdbc",
        )

        /**
         * The KSP processor for a kapt(...) / annotationProcessor(...) coordinate, for processors
         * that ship one (the same artifact, or a sibling); null when there isn't one.
         */
        fun kspEquivalent(coord: String): String? {
            val parts = coord.split(':')
            if (parts.size < 3) return null
            val (group, artifact, version) = parts
            return when ("$group:$artifact") {
                "androidx.room:room-compiler", "com.google.dagger:dagger-compiler", "com.google.dagger:hilt-compiler",
                "androidx.hilt:hilt-compiler", "com.squareup.moshi:moshi-kotlin-codegen" -> coord
                "com.google.dagger:hilt-android-compiler" -> "com.google.dagger:hilt-compiler:$version"
                "com.github.bumptech.glide:compiler" -> "com.github.bumptech.glide:ksp:$version"
                else -> null
            }
        }

        /** The --min-api copyBuildTools dexes androidx-classes2.dex with. */
        private const val BUNDLED_DEX_MIN_API = 21

        /** androidx.databinding:viewbinding, which AGP adds for View Binding (its version follows AGP's). */
        private const val VIEWBINDING_VERSION = "8.7.3"

        /** Used when isCoreLibraryDesugaringEnabled is on without a coreLibraryDesugaring(...) line. */
        private const val DESUGAR_JDK_LIBS_VERSION = "2.1.3"
    }
}
