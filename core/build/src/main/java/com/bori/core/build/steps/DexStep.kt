package com.bori.core.build.steps

import com.bori.core.build.model.BuildVariant
import com.bori.core.build.tools.ToolsManager
import dalvik.system.PathClassLoader
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class DexStep(private val tools: ToolsManager) {

    /**
     * Converts [inputs] (directories of .class files, or jars) to classes*.dex in [dexOutputDir]
     * using D8 (debug, or release without rules) or R8 (release with [proguardRulesFiles]). The
     * output directory is emptied first, so no dex from an earlier build is left behind.
     *
     * [classpath] is what the inputs compile against but which is dexed elsewhere (bundled AndroidX,
     * other inputs dexed separately); D8 and R8 read it for desugaring and R8 never shrinks it. R8 also
     * shrinks, obfuscates and optimises, and writes mapping.txt next to the dex so release stack
     * traces can be de-obfuscated.
     */
    fun convert(
        inputs: List<File>,
        dexOutputDir: File,
        minSdk: Int,
        emit: (String) -> Unit,
        classpath: List<File> = emptyList(),
        variant: BuildVariant = BuildVariant.DEBUG,
        proguardRulesFiles: List<File> = emptyList(),
        desugaredLibConfig: File? = null,
    ) {
        val programFiles = inputs.filter { it.exists() }.flatMap { input ->
            if (input.isDirectory) input.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
            else listOf(input)
        }.map { it.absolutePath }

        if (programFiles.isEmpty()) {
            emit("WARNING: No classes to dex in ${inputs.joinToString { it.name }}")
            return
        }

        val useR8 = variant == BuildVariant.RELEASE && proguardRulesFiles.isNotEmpty()
        val toolName = if (useR8) "R8" else "D8"
        emit("$toolName: converting ${inputs.joinToString { it.name }}${if (useR8) " with shrinking + obfuscation" else ""}")

        dexOutputDir.deleteRecursively()
        dexOutputDir.mkdirs()

        val args = buildList {
                add("--output"); add(dexOutputDir.absolutePath)
                add("--min-api"); add(minSdk.toString())
                add(if (variant == BuildVariant.RELEASE) "--release" else "--debug")
                add("--lib"); add(tools.androidStubs.absolutePath)
                // java.time & co. below their API level go through desugar_jdk_libs (dexed by [l8]).
                desugaredLibConfig?.takeIf { it.exists() }?.let { add("--desugared-lib"); add(it.absolutePath) }
                // Classpath, not library, for R8 too: those classes are desugared like the
                // program (library classes are assumed to keep their default methods).
                for (jar in classpath.filter { it.exists() }) {
                    add("--classpath"); add(jar.absolutePath)
                }
                if (useR8) {
                    proguardRulesFiles.filter { it.exists() }.forEach { rules ->
                        add("--pg-conf"); add(rules.absolutePath)
                        emit("$toolName: applying rules from ${rules.name}")
                    }
                    val mappingFile = File(dexOutputDir, "mapping.txt")
                    add("--pg-map-output"); add(mappingFile.absolutePath)
                    emit("$toolName: mapping file → ${mappingFile.absolutePath}")
                }
                addAll(programFiles)
            }.toTypedArray()
        run(if (useR8) "com.android.tools.r8.R8" else "com.android.tools.r8.D8", toolName, args, emit)
    }

    /**
     * Dexes the `desugar_jdk_libs` jar with L8 for [minSdk], into [dexOutputDir]: the library
     * implementations (java.time, streams…) that D8 rewrote calls to with [desugaredLibConfig].
     */
    fun l8(desugarJar: File, desugaredLibConfig: File, dexOutputDir: File, minSdk: Int, emit: (String) -> Unit) {
        dexOutputDir.deleteRecursively()
        dexOutputDir.mkdirs()
        emit("L8: dexing ${desugarJar.name} for API $minSdk")
        val args = arrayOf(
            "--output", dexOutputDir.absolutePath,
            "--min-api", minSdk.toString(),
            "--release",
            "--lib", tools.androidStubs.absolutePath,
            "--desugared-lib", desugaredLibConfig.absolutePath,
            desugarJar.absolutePath,
        )
        run("com.android.tools.r8.L8", "L8", args, emit)
    }

    private fun run(entryClass: String, toolName: String, args: Array<String>, emit: (String) -> Unit) {
        try {
            val loader = PathClassLoader(tools.d8Jar.absolutePath, ClassLoader.getSystemClassLoader())
            val toolClass = loader.loadClass(entryClass)
            // Through the command API, not main(): main() calls System.exit on a compile error,
            // which would end Bori Code itself. run() throws CompilationFailedException instead.
            val commandClass = loader.loadClass(entryClass + "Command")
            val originClass = loader.loadClass("com.android.tools.r8.origin.Origin")
            val builder = commandClass.getMethod("parse", Array<String>::class.java, originClass)
                .invoke(null, args, originClass.getMethod("root").invoke(null))!!
            val command = builder.javaClass.getMethod("build").invoke(builder)
            val runMethod = toolClass.getMethod("run", commandClass)

            // D8/R8 print their errors (duplicate classes, missing classes…) to stderr, so capture
            // it for the build log. System.err is process-wide; nothing else writes it during a build.
            val captured = java.io.ByteArrayOutputStream()
            val originalErr = System.err
            val executor = Executors.newSingleThreadExecutor()
            System.setErr(java.io.PrintStream(captured, true))
            val future = executor.submit<Unit> { runMethod.invoke(null, command) }

            val startMs = System.currentTimeMillis()
            val heartbeatEx = Executors.newSingleThreadScheduledExecutor()
            val heartbeat: ScheduledFuture<*> = heartbeatEx.scheduleAtFixedRate({
                val elapsed = (System.currentTimeMillis() - startMs) / 1000
                emit("$toolName: still converting... ${elapsed}s / ${DEX_TIMEOUT_SECONDS}s")
            }, 10, 10, TimeUnit.SECONDS)

            try {
                future.get(DEX_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw RuntimeException("$toolName timed out after ${DEX_TIMEOUT_SECONDS}s — the project may be too large for on-device dexing.")
            } finally {
                heartbeat.cancel(false)
                heartbeatEx.shutdownNow()
                executor.shutdownNow()
                System.setErr(originalErr)
                // Stack frames and repeats (one Kotlin-metadata warning per class) only bury the message.
                captured.toString().lines()
                    .filter { it.isNotBlank() && !it.trimStart().startsWith("at ") }
                    .distinct()
                    .take(MAX_TOOL_LINES)
                    .forEach { emit("$toolName: $it") }
            }
        } catch (e: ClassNotFoundException) {
            throw RuntimeException("$toolName class not found in d8.jar — verify the bundled asset is correct", e)
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Throwable) {
            val root = (e as? java.util.concurrent.ExecutionException)?.cause?.let { (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it } ?: e
            val message = when {
                root is OutOfMemoryError -> "$toolName: Out of Memory. The project is too large for on-device dexing."
                root.javaClass.simpleName == "CompilationFailedException" -> "$toolName failed: see the $toolName errors above"
                else -> "$toolName failed: ${root.message ?: root.javaClass.simpleName}"
            }
            throw RuntimeException(message, e)
        }
    }

    companion object {
        private const val DEX_TIMEOUT_SECONDS = 300L
        private const val MAX_TOOL_LINES = 60

        /** The classes*.dex files in [dir], in order (classes.dex, classes2.dex, …). */
        fun dexFiles(dir: File): List<File> =
            dir.listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }
                ?.sortedBy { it.name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1 }
                .orEmpty()
    }
}
