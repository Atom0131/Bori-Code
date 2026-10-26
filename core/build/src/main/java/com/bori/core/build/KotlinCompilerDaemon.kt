package com.bori.core.build

import android.util.Log
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.core.build.tools.ToolsManager
import dalvik.system.DelegateLastClassLoader
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import javax.inject.Singleton

/**
 * Manages the lifecycle of a "warm" Kotlin compiler instance to speed up builds
 * on devices that can afford the memory overhead.
 */
@Singleton
class KotlinCompilerDaemon @Inject constructor() {

    // Loads compiler classes from pre-dexed JARs via DelegateLastClassLoader (prefers its own
    // DEX over the parent, preventing the app's Kotlin runtime from shadowing compiler classes).
    private class CompilerClassLoader(
        loaderPath: String,
        @Suppress("unused") private val jarFiles: List<File>,
        parent: ClassLoader,
    ) : ClassLoader(parent) {
        private val delegate = DelegateLastClassLoader(loaderPath, parent)
        override fun loadClass(name: String): Class<*> = delegate.loadClass(name)
        override fun getResource(name: String) = delegate.getResource(name)
        override fun getResources(name: String) = delegate.getResources(name)
    }

    data class CompilerInstance(
        val loader: ClassLoader,
        val compilerClass: Class<*>,
        val instance: Any,
        val execMethod: java.lang.reflect.Method,
        val incrementalApi: IncrementalApi?,
    )

    /**
     * Reflection handles for the official Kotlin incremental compilation API.
     * Loaded lazily — null if the classes aren't available in the kotlinc.jar.
     */
    data class IncrementalApi(
        val argsClass: Class<*>,
        val parseArgumentsMethod: java.lang.reflect.Method,
        val makeIncrementallyMethod: java.lang.reflect.Method,
        val printingCollectorClass: Class<*>,
        val messageRendererClass: Class<*>,
        val messageRendererPlain: Any,
        val doNothingICReporter: Any,
    )

    private var cachedInstance: CompilerInstance? = null
    private var cachedLoaderPath: String? = null
    private var cachedPluginsDir: File? = null
    // The compiler class loader whose application environment already has compiler.xml's
    // extension points (see prepareApplicationEnvironment).
    private var preparedLoader: ClassLoader? = null

    // Hidden API bypass methods (cached globally)
    private var vmRuntime: Any? = null
    private var setHiddenApiExemptionsMethod: java.lang.reflect.Method? = null

    /**
     * Returns a compiler instance based on the performance profile.
     * If profile is LOW, always returns a fresh instance and clears cache.
     * If MEDIUM/HIGH, returns a cached instance if available, otherwise initializes it.
     */
    fun getCompiler(
        tools: ToolsManager,
        profile: CompilerPerformanceProfile,
        parentLoader: ClassLoader,
        extraPluginDexJars: List<File> = emptyList(),
    ): CompilerInstance {
        val currentLoaderPath = getLoaderPath(tools, extraPluginDexJars)
        if (cachedInstance != null && cachedLoaderPath == currentLoaderPath) {
            Log.d(TAG, "Returning cached compiler instance (warm start) [Profile: ${profile.name}]")
            return cachedInstance!!
        }

        Log.d(TAG, "Initializing compiler instance (cold start) [Profile: ${profile.name}]")
        val newInstance = createFreshInstance(tools, parentLoader, extraPluginDexJars)
        cachedInstance = newInstance
        cachedLoaderPath = currentLoaderPath
        return newInstance
    }

    fun clearCache() {
        cachedInstance = null
        cachedLoaderPath = null
        cachedPluginsDir = null
        preparedLoader = null
    }

    /**
     * Creates the compiler's IntelliJ application environment, keeps it alive, and registers the
     * application extension points declared in compiler.xml.
     *
     * copyBuildTools patches out PluginManagerCore.registerExtensionPointAndExtensions (it needs
     * StAX, which Android lacks), so those points never get registered. Without them the compiler
     * crashes on the first error or warning it reports ("Missing extension point:
     * org.jetbrains.kotlin.diagnosticSuppressor"), and the user never sees the real message.
     * The environment is kept alive (kotlin.environment.keepalive), so each compile on this class
     * loader reuses it, extension points included.
     */
    private fun prepareApplicationEnvironment(inst: CompilerInstance, tools: ToolsManager) {
        if (preparedLoader === inst.loader) return
        val l = inst.loader
        System.setProperty("kotlin.environment.keepalive", "true")
        val pluginsDir = extractPluginsDir(tools)

        val configClass = l.loadClass("org.jetbrains.kotlin.config.CompilerConfiguration")
        val config = configClass.getDeclaredConstructor().newInstance()
        val pluginRootKey = l.loadClass("org.jetbrains.kotlin.cli.common.CLIConfigurationKeys")
            .getField("INTELLIJ_PLUGIN_ROOT").get(null)
        configClass.getMethod("put", l.loadClass("org.jetbrains.kotlin.config.CompilerConfigurationKey"), Any::class.java)
            .invoke(config, pluginRootKey, pluginsDir.absolutePath)
        val disposable = l.loadClass("org.jetbrains.kotlin.com.intellij.openapi.util.Disposer")
            .getMethod("newDisposable").invoke(null)
        val envCompanion = l.loadClass("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment")
            .getField("Companion").get(null)
        envCompanion.javaClass.getMethod(
            "getOrCreateApplicationEnvironmentForProduction",
            l.loadClass("org.jetbrains.kotlin.com.intellij.openapi.Disposable"),
            configClass,
        ).invoke(envCompanion, disposable, config)

        val app = l.loadClass("org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager")
            .getMethod("getApplication").invoke(null)
        val area = app.javaClass.getMethod("getExtensionArea").invoke(app)
        val kindClass = l.loadClass("org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint\$Kind")
        val interfaceKind = kindClass.getField("INTERFACE").get(null)
        val hasPoint = area.javaClass.getMethod("hasExtensionPoint", String::class.java)
        val register = area.javaClass.getMethod("registerExtensionPoint", String::class.java, String::class.java, kindClass)

        val xml = File(pluginsDir, "META-INF/extensions/compiler.xml")
        val points = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
            .getElementsByTagName("extensionPoint")
        var added = 0
        for (i in 0 until points.length) {
            val el = points.item(i) as Element
            val name = el.getAttribute("qualifiedName")
            val iface = el.getAttribute("interface")
            if (name.isEmpty() || iface.isEmpty() || hasPoint.invoke(area, name) as Boolean) continue
            register.invoke(area, name, iface, interfaceKind)
            added++
        }
        Log.d(TAG, "Application environment ready: registered $added extension point(s) from compiler.xml")
        preparedLoader = inst.loader
    }

    /**
     * Extracts extensions/compiler.xml from kotlinc.jar to a plain directory so the
     * IntelliJ PluginManagerCore reads it with ordinary file I/O (no NIO2 ZipFileSystem).
     *
     * Both "extensions/compiler.xml" and "META-INF/extensions/compiler.xml" are written
     * to handle whichever path PluginDescriptorLoader.loadFromDirectory() resolves.
     */
    private fun extractPluginsDir(tools: ToolsManager): File {
        val pluginsDir = File(File(tools.context.filesDir, "tools"), "kotlinc-plugins")
        val xmlTarget = File(pluginsDir, "extensions/compiler.xml")

        // Only skip extraction if the cache is valid AND the file still exists on disk.
        // Without the xmlTarget.exists() check, a tool re-extraction that deletes toolsDir
        // leaves cachedPluginsDir pointing to a now-absent directory, causing plugin-load failure.
        cachedPluginsDir?.let {
            if (it == pluginsDir && xmlTarget.exists()) return pluginsDir
        }

        if (!xmlTarget.exists()) {
            pluginsDir.deleteRecursively()
            pluginsDir.mkdirs()
            try {
                ZipFile(tools.kotlincJar).use { zip ->
                    // The embeddable JAR stores it under META-INF/extensions/; the standalone
                    // distribution stores it at extensions/. Try both.
                    val entry = zip.getEntry("extensions/compiler.xml")
                        ?: zip.getEntry("META-INF/extensions/compiler.xml")
                        ?: return@use Unit.also {
                            Log.w(TAG, "extensions/compiler.xml not found in kotlinc.jar at either path")
                        }
                    for (relPath in listOf("extensions/compiler.xml", "META-INF/extensions/compiler.xml")) {
                        val dest = File(pluginsDir, relPath)
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                Log.d(TAG, "Extracted extensions/compiler.xml to $pluginsDir")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract compiler plugins dir: $e")
            }
        }

        cachedPluginsDir = pluginsDir
        return pluginsDir
    }

    /**
     * Applies the hidden API bypass using cached reflection objects.
     */
    fun applyHiddenApiBypass() {
        try {
            if (vmRuntime == null || setHiddenApiExemptionsMethod == null) {
                val clazz = Class.forName("dalvik.system.VMRuntime")
                val getRuntime = clazz.getDeclaredMethod("getRuntime").also { it.isAccessible = true }
                vmRuntime = getRuntime.invoke(null)
                setHiddenApiExemptionsMethod = clazz.getDeclaredMethod(
                    "setHiddenApiExemptions", Array<String>::class.java
                ).also { it.isAccessible = true }
            }
            setHiddenApiExemptionsMethod!!.invoke(vmRuntime, arrayOf("Lsun/", "Ljdk/", "Ldalvik/system/VMRuntime;"))
            Log.d(TAG, "Hidden API bypass applied successfully")

            // Lower runtime target SDK so the max-targetSdk hidden API tier doesn't block
            // sun.misc.Unsafe.getAndAddInt (needed by Kotlin compiler 1.8.22 static init).
            try {
                val clazz = Class.forName("dalvik.system.VMRuntime")
                val setTargetSdk = clazz.getDeclaredMethod("setTargetSdkVersion", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                setTargetSdk.invoke(vmRuntime, 26)
                Log.d(TAG, "Hidden API bypass: setTargetSdkVersion(26) applied")
            } catch (e: Exception) {
                Log.w(TAG, "setTargetSdkVersion(26) failed (non-critical): $e")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply hidden API bypass — Kotlin compiler may crash", e)
        }
    }

    private fun probeUnsafeAccess(): String {
        return try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            unsafeClass.getDeclaredMethod(
                "getAndAddInt",
                Object::class.java,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            "OK — getAndAddInt accessible"
        } catch (e: Exception) {
            "BLOCKED — ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Manually bootstraps the compiler environment and compiles files.
     * This bypasses the CLI's dependency on Java's NIO ZipFileSystem.
     */
    fun compileIncremental(
        tools: ToolsManager,
        profile: CompilerPerformanceProfile,
        parentLoader: ClassLoader,
        args: Array<String>,
        out: java.io.PrintStream,
        extraPluginDexJars: List<File> = emptyList(),
    ): String {
        val inst = getCompiler(tools, profile, parentLoader, extraPluginDexJars)
        
        // Ensure the thread context classloader is set to our resource-aware loader
        val oldLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = inst.loader
        
        return try {
            prepareApplicationEnvironment(inst, tools)
            Log.d(TAG, "Executing compilation via CLI exec (reflection)")
            val exitCode = inst.execMethod.invoke(inst.instance, out, args)
            exitCode!!.javaClass.getMethod("name").invoke(exitCode) as String
        } catch (ite: java.lang.reflect.InvocationTargetException) {
            // Unwrap so the real compiler exception (not InvocationTargetException) propagates
            throw ite.targetException ?: ite
        } finally {
            Thread.currentThread().contextClassLoader = oldLoader
        }
    }

    private fun createFreshInstance(
        tools: ToolsManager,
        parentLoader: ClassLoader,
        extraPluginDexJars: List<File> = emptyList(),
    ): CompilerInstance {
        // Re-apply bypass from this thread — hidden API enforcement is checked at the call site,
        // so applying it in the compilation thread ensures it's in effect when classes load.
        applyHiddenApiBypass()
        Log.w(TAG, "Unsafe.getAndAddInt probe (post-bypass): ${probeUnsafeAccess()}")

        // Suppress IntelliJ subsystems that misbehave on Android.
        // Note: do NOT set kotlin.compiler.use.zip.file.system=false here — that disables
        // FastJarFileSystem, which we rely on (passed as -Xuse-fast-jar-file-system in args).
        System.setProperty("idea.io.use.fallback.list", "true")
        System.setProperty("idea.io.use.nio2", "false")
        System.setProperty("idea.use.native.fs", "false")
        System.setProperty("idea.use.nio.fs", "false")

        val loaderPath = getLoaderPath(tools, extraPluginDexJars)

        val pluginsDir = extractPluginsDir(tools)
        Log.d(TAG, "kotlincJar exists=${tools.kotlincJar.exists()} size=${tools.kotlincJar.length()}")
        Log.d(TAG, "pluginsDir=$pluginsDir exists=${pluginsDir.exists()}")
        Log.d(TAG, "compiler.xml exists=${File(pluginsDir, "extensions/compiler.xml").exists()}")

        // Use system classloader as parent to ensure we don't pick up the app's Kotlin libraries.
        // This prevents ClassCastException during reflection (ClassReference vs KClassImpl).
        val jarFiles = listOf(tools.kotlincJar, tools.kotlinStdlibDexJar, tools.kotlinReflectDexJar, tools.trove4jDexJar, tools.swingStubsDexJar, tools.jetbrainsAnnotationsDexJar) + extraPluginDexJars
        if (extraPluginDexJars.isNotEmpty()) {
            Log.d(TAG, "Compiler classloader includes ${extraPluginDexJars.size} plugin DEX jar(s): ${extraPluginDexJars.map { it.name }}")
        }
        val bootstrapLoader = CompilerClassLoader(loaderPath, jarFiles, ClassLoader.getSystemClassLoader())
        val compilerClass = bootstrapLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val instance = compilerClass.getDeclaredConstructor().newInstance()
        val execMethod = compilerClass.getMethod("exec", java.io.PrintStream::class.java, Array<String>::class.java)

        val incrementalApi = tryLoadIncrementalApi(bootstrapLoader, compilerClass)
        return CompilerInstance(bootstrapLoader, compilerClass, instance, execMethod, incrementalApi)
    }

    private fun tryLoadIncrementalApi(loader: ClassLoader, compilerClass: Class<*>): IncrementalApi? {
        return try {
            val argsClass = loader.loadClass("org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments")
            // parseArguments is inherited from CLITool; search up the hierarchy
            val parseArgumentsMethod = generateSequence<Class<*>>(compilerClass) { it.superclass }
                .firstNotNullOfOrNull { c ->
                    try {
                        c.getDeclaredMethod("parseArguments", Array<String>::class.java, argsClass)
                            .also { it.isAccessible = true }
                    } catch (_: NoSuchMethodException) { null }
                } ?: throw NoSuchMethodException("parseArguments not found")

            val collectorClass = loader.loadClass("org.jetbrains.kotlin.cli.common.messages.MessageCollector")
            val icReporterClass = loader.loadClass("org.jetbrains.kotlin.build.report.ICReporter")
            val runnerClass = loader.loadClass("org.jetbrains.kotlin.incremental.IncrementalJvmCompilerRunnerKt")
            val makeIncrementallyMethod = runnerClass.getMethod(
                "makeIncrementally",
                File::class.java,
                Iterable::class.java,
                argsClass,
                collectorClass,
                icReporterClass,
            )

            val messageRendererClass = loader.loadClass("org.jetbrains.kotlin.cli.common.messages.MessageRenderer")
            val messageRendererPlain = messageRendererClass.getField("PLAIN_RELATIVE_PATHS").get(null)
            val printingCollectorClass = loader.loadClass("org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector")

            val doNothingClass = loader.loadClass("org.jetbrains.kotlin.build.report.DoNothingICReporter")
            val doNothingICReporter = doNothingClass.getField("INSTANCE").get(null)

            Log.d(TAG, "Incremental API loaded (makeIncrementally available)")
            IncrementalApi(
                argsClass = argsClass,
                parseArgumentsMethod = parseArgumentsMethod,
                makeIncrementallyMethod = makeIncrementallyMethod,
                printingCollectorClass = printingCollectorClass,
                messageRendererClass = messageRendererClass,
                messageRendererPlain = messageRendererPlain,
                doNothingICReporter = doNothingICReporter,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Incremental API unavailable (${e.javaClass.simpleName}: ${e.message}), will use exec()")
            null
        }
    }

    /**
     * Compiles using Kotlin's official incremental API (makeIncrementally).
     * Falls back to exec() if the API classes are not available in the bundled kotlinc.jar.
     *
     * [argsNoFiles] must NOT contain source file paths — the incremental runner discovers
     * Kotlin sources by walking [sourceRoots] itself.
     */
    fun compileWithIncrementalApi(
        tools: ToolsManager,
        profile: CompilerPerformanceProfile,
        parentLoader: ClassLoader,
        argsNoFiles: Array<String>,
        sourceRoots: List<File>,
        cacheDir: File,
        out: PrintStream,
        extraPluginDexJars: List<File> = emptyList(),
    ): String {
        val inst = getCompiler(tools, profile, parentLoader, extraPluginDexJars)
        val api = inst.incrementalApi
        if (api == null) {
            Log.w(TAG, "IC API not available — falling back to exec()")
            return execFallback(tools, profile, parentLoader, argsNoFiles, sourceRoots, out, extraPluginDexJars)
        }

        val oldLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = inst.loader
        return try {
            prepareApplicationEnvironment(inst, tools)
            val argsObj = api.argsClass.getDeclaredConstructor().newInstance()
            api.parseArgumentsMethod.invoke(inst.instance, argsNoFiles, argsObj)

            val collector = api.printingCollectorClass.getDeclaredConstructor(
                PrintStream::class.java,
                api.messageRendererClass,
                Boolean::class.javaPrimitiveType,
            ).newInstance(out, api.messageRendererPlain, false)

            cacheDir.mkdirs()
            api.makeIncrementallyMethod.invoke(
                null, cacheDir, sourceRoots, argsObj, collector, api.doNothingICReporter,
            )

            val hasErrors = api.printingCollectorClass.getMethod("hasErrors").invoke(collector) as Boolean
            if (hasErrors) "COMPILATION_ERROR" else "OK"
        } catch (ite: java.lang.reflect.InvocationTargetException) {
            val root = ite.targetException ?: ite
            Log.e(TAG, "makeIncrementally threw — full chain:", ite)
            Log.w(TAG, "makeIncrementally root: ${root.javaClass.name}: ${root.message} — falling back to exec()")
            Thread.currentThread().contextClassLoader = oldLoader
            return execFallback(tools, profile, parentLoader, argsNoFiles, sourceRoots, out, extraPluginDexJars)
        } finally {
            Thread.currentThread().contextClassLoader = oldLoader
        }
    }

    private fun execFallback(
        tools: ToolsManager,
        profile: CompilerPerformanceProfile,
        parentLoader: ClassLoader,
        argsNoFiles: Array<String>,
        sourceRoots: List<File>,
        out: PrintStream,
        extraPluginDexJars: List<File> = emptyList(),
    ): String {
        val ktFiles = sourceRoots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        val argsWithFiles = argsNoFiles + ktFiles.map { it.absolutePath }
        return compileIncremental(tools, profile, parentLoader, argsWithFiles, out, extraPluginDexJars)
    }

    private fun getLoaderPath(tools: ToolsManager, extraPluginDexJars: List<File> = emptyList()): String {
        val base = "${tools.kotlincJar.absolutePath}:" +
                "${tools.kotlinStdlibDexJar.absolutePath}:" +
                "${tools.kotlinReflectDexJar.absolutePath}:" +
                "${tools.trove4jDexJar.absolutePath}:" +
                "${tools.swingStubsDexJar.absolutePath}:" +
                "${tools.jetbrainsAnnotationsDexJar.absolutePath}"
        if (extraPluginDexJars.isEmpty()) return base
        return base + ":" + extraPluginDexJars.joinToString(":") { it.absolutePath }
    }

    companion object {
        private const val TAG = "KotlinCompilerDaemon"
    }
}
