package com.bori.core.build.steps

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log
import com.bori.core.build.KotlinCompilerDaemon
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.core.build.tools.ToolsManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class KotlinCompileStep(private val tools: ToolsManager) {

    fun compile(
        sourceRoots: List<File>,
        classesDir: File,
        extraClasspath: List<File> = emptyList(),
        timeoutSeconds: Long = COMPILE_TIMEOUT_SECONDS,
        daemon: KotlinCompilerDaemon,
        profile: CompilerPerformanceProfile,
        processorJars: List<File> = emptyList(),
        kaptPluginJar: File? = null,
        kaptGeneratedSourcesDir: File? = null,
        composePluginJar: File? = null,
        pluginDexJars: List<File> = emptyList(),
        emit: (String) -> Unit,
    ): Boolean {
        val ktFiles = sourceRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }
        if (ktFiles.isEmpty()) return true

        val validProcessorJars = processorJars.filter { it.exists() }
        val useKapt = validProcessorJars.isNotEmpty() && kaptPluginJar?.exists() == true
        val useCompose = composePluginJar?.exists() == true
        // Any -Xplugin path forces the exec() compile route: the incremental API path doesn't
        // reliably register compiler plugins, and kapt already requires exec() for stub generation.
        val usePluginExec = useKapt || useCompose

        if (useKapt) {
            emit("Kotlin: compiling ${ktFiles.size} file(s) with kapt [${validProcessorJars.size} processor(s)] [Profile: ${profile.name}]")
        } else if (useCompose) {
            emit("Kotlin: compiling ${ktFiles.size} file(s) with Compose compiler plugin [Profile: ${profile.name}]")
        } else {
            emit("Kotlin: incremental compile [${ktFiles.size} source file(s)] [Profile: ${profile.name}]")
        }

        Log.d(TAG, "=== KotlinCompileStep.compile ===")
        Log.d(TAG, "Profile: $profile, useKapt: $useKapt, sourceFiles: ${ktFiles.size}")

        val kotlinHome = File(tools.kotlincJar.parentFile, "kotlinc-home")
        File(kotlinHome, "lib").mkdirs()
        val stdlibInHome = File(kotlinHome, "lib/kotlin-stdlib.jar")
        if (!stdlibInHome.exists()) tools.kotlinStdlibJar.copyTo(stdlibInHome)

        // Minimize reflection: use cached hidden API bypass from daemon
        daemon.applyHiddenApiBypass()

        val classpath = buildList {
            add(tools.androidStubs.absolutePath)
            add(tools.kotlinStdlibJar.absolutePath)
            addAll(extraClasspath.map { it.absolutePath })
        }.joinToString(":")

        // Pre-extracted plugins dir — passed as -Xintellij-plugin-root so KotlinCoreEnvironment
        // reads compiler.xml via plain file I/O instead of PathUtil + NIO2 ZipFileSystem
        // (ZipFileSystemProvider is not registered on Android ARM → ProviderNotFoundException).
        val pluginsDir = File(tools.kotlincJar.parentFile, "kotlinc-plugins")

        // Base args without source file paths (used by the incremental API path).
        // FastJarFileSystem avoids the NIO2 ZipFileSystem that crashes on Android.
        val baseArgs = buildList {
            add("-kotlin-home"); add(kotlinHome.absolutePath)
            add("-classpath"); add(classpath)
            add("-d"); add(classesDir.absolutePath)
            add("-jvm-target"); add("1.8")
            add("-no-jdk"); add("-no-stdlib"); add("-no-reflect")
            add("-Xuse-fast-jar-file-system")
            add("-Xintellij-plugin-root=${pluginsDir.absolutePath}")
            if (useKapt && kaptPluginJar != null) {
                val stubsDir = File(classesDir.parentFile, "kapt-stubs").also { it.mkdirs() }
                val kaptClassesDir = File(classesDir.parentFile, "kapt-classes").also { it.mkdirs() }
                kaptGeneratedSourcesDir?.mkdirs()
                add("-Xplugin=${kaptPluginJar.absolutePath}")
                add("-P"); add("plugin:org.jetbrains.kotlin.kapt3:sources=${kaptGeneratedSourcesDir?.absolutePath ?: stubsDir.absolutePath}")
                add("-P"); add("plugin:org.jetbrains.kotlin.kapt3:classes=${kaptClassesDir.absolutePath}")
                add("-P"); add("plugin:org.jetbrains.kotlin.kapt3:stubs=${stubsDir.absolutePath}")
                val processorPath = validProcessorJars.joinToString(File.pathSeparator) { it.absolutePath }
                add("-P"); add("plugin:org.jetbrains.kotlin.kapt3:apclasspath=$processorPath")
                add("-P"); add("plugin:org.jetbrains.kotlin.kapt3:correctErrorTypes=true")
            }
            if (useCompose && composePluginJar != null) {
                // The raw jar is passed so ServiceLoaderLite can scan META-INF/services for the
                // ComponentRegistrar class name; the actual classes are supplied as a pre-dexed jar
                // injected into the compiler's classloader (pluginDexJars → daemon loader path).
                add("-Xplugin=${composePluginJar.absolutePath}")
            }
        }

        Log.d(TAG, "compiler baseArgs: $baseArgs")

        val startMs = System.currentTimeMillis()
        val heartbeatEx = Executors.newSingleThreadScheduledExecutor()
        val heartbeat: ScheduledFuture<*> = heartbeatEx.scheduleAtFixedRate({
            val elapsed = (System.currentTimeMillis() - startMs) / 1000
            emit("Kotlin: still compiling... ${elapsed}s / ${timeoutSeconds}s")
        }, 10, 10, TimeUnit.SECONDS)

        return try {
            val (exitName, output) = if (usePluginExec) {
                // Plugin builds (kapt / Compose) require the exec() path: all source files are passed
                // explicitly, and -Xplugin compiler plugins are registered during CLI environment setup.
                val argsWithFiles = (baseArgs + ktFiles.map { it.absolutePath }).toTypedArray()
                invokeCompiler(daemon, profile, argsWithFiles, timeoutSeconds, pluginDexJars)
            } else {
                // Official incremental API: source files are discovered from sourceRoots.
                val cacheDir = File(classesDir.parentFile, "kotlin-ic-cache")
                invokeIncrementalApi(daemon, profile, baseArgs.toTypedArray(), sourceRoots, cacheDir, timeoutSeconds)
            }

            if (output.isNotBlank()) Log.d(TAG, "compiler output:\n$output")
            output.lines().filter { it.isNotBlank() }.forEach { emit(it) }
            exitName == "OK"
        } catch (e: Throwable) {
            val root = deepUnwrap(e)
            // Log full chain so the exact compiler class triggering the failure is visible
            Log.e(TAG, "Compile failed — full chain:", e)
            Log.e(TAG, "Compile failed — root: ${root.javaClass.name}: ${root.message}", root)
            val message = when (root) {
                is TimeoutException   -> "Kotlin compiler: Timed out after ${timeoutSeconds}s. Try increasing the timeout in Settings."
                is OutOfMemoryError   -> "Kotlin compiler: Out of Memory."
                is StackOverflowError -> "Kotlin compiler: Stack Overflow."
                else                  -> "Kotlin compiler error: ${root.javaClass.simpleName}: ${root.message}"
            }
            emit(message)
            false
        } finally {
            heartbeat.cancel(false)
            heartbeatEx.shutdownNow()
        }
    }

    /** Calls exec() on the compiler via the daemon (used for kapt builds). */
    private fun invokeCompiler(
        daemon: KotlinCompilerDaemon,
        profile: CompilerPerformanceProfile,
        args: Array<String>,
        timeoutSeconds: Long,
        pluginDexJars: List<File> = emptyList(),
    ): Pair<String, String> = invokeOnCompilerThread(timeoutSeconds, "compileIncremental", profile) { ps ->
        daemon.compileIncremental(tools, profile, KotlinCompileStep::class.java.classLoader, args, ps, pluginDexJars)
    }

    /** Calls makeIncrementally() on the daemon (used for normal non-kapt builds). */
    private fun invokeIncrementalApi(
        daemon: KotlinCompilerDaemon,
        profile: CompilerPerformanceProfile,
        argsNoFiles: Array<String>,
        sourceRoots: List<File>,
        cacheDir: File,
        timeoutSeconds: Long,
    ): Pair<String, String> = invokeOnCompilerThread(timeoutSeconds, "makeIncrementally", profile) { ps ->
        daemon.compileWithIncrementalApi(
            tools, profile, KotlinCompileStep::class.java.classLoader,
            argsNoFiles, sourceRoots, cacheDir, ps,
        )
    }

    /**
     * Runs [block] on a dedicated 32 MB-stack compiler thread, capturing its output and
     * returning (exitName, capturedOutput). Handles timeout, performance hints, and crash
     * unwrapping the same way for both the exec() and makeIncrementally() paths.
     */
    private fun invokeOnCompilerThread(
        timeoutSeconds: Long,
        logTag: String,
        profile: CompilerPerformanceProfile,
        block: (PrintStream) -> String,
    ): Pair<String, String> {
        val output = ByteArrayOutputStream()
        val ps = PrintStream(output)
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(null, r, "kotlin-compiler", 32 * 1024 * 1024)
        }
        return try {
            executor.submit<Pair<String, String>> {
                var hintSession: Any? = null
                try {
                    val threadId = android.os.Process.myTid()
                    if (profile == CompilerPerformanceProfile.HIGH && Build.VERSION.SDK_INT >= 31) {
                        try {
                            val phm = tools.context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
                            if (phm != null) {
                                hintSession = phm.createHintSession(intArrayOf(threadId), 1_000_000_000L)
                            }
                        } catch (_: Exception) { }
                    }

                    val compilerStart = System.nanoTime()
                    Log.d(TAG, "Calling $logTag via daemon")
                    val exitName = block(ps)

                    if (hintSession != null && Build.VERSION.SDK_INT >= 31) {
                        try {
                            val actualNanos = System.nanoTime() - compilerStart
                            hintSession.javaClass.getMethod("reportActualWorkDuration", Long::class.javaPrimitiveType)
                                .invoke(hintSession, actualNanos)
                        } catch (_: Exception) { }
                    }

                    Pair(exitName, output.toString())
                } catch (ite: InvocationTargetException) {
                    throw deepUnwrap(ite)
                } finally {
                    if (hintSession != null && Build.VERSION.SDK_INT >= 31) {
                        try { hintSession.javaClass.getMethod("close").invoke(hintSession) } catch (_: Exception) {}
                    }
                }
            }.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            val root = deepUnwrap(e)
            val captured = output.toString()
            if (captured.isNotBlank()) Log.d(TAG, "Compiler output before crash:\n$captured")
            Log.e(TAG, "Compiler crash: ${root.javaClass.name}: ${root.message}", root)
            throw root
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Unwraps the full cause chain, treating InvocationTargetException.targetException
     * as the true cause so the real compiler exception is always surfaced.
     */
    private fun deepUnwrap(t: Throwable): Throwable {
        var current = t
        val seen = mutableSetOf<Throwable>()
        while (true) {
            seen.add(current)
            val next = when (current) {
                is InvocationTargetException -> current.targetException ?: current.cause
                else -> current.cause
            } ?: break
            if (next in seen) break
            current = next
        }
        return current
    }

    companion object {
        private const val TAG = "KotlinCompileStep"
        private const val COMPILE_TIMEOUT_SECONDS = 120L
    }
}
