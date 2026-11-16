package com.bori.core.build.steps

import com.bori.core.build.model.BuildVariant
import com.bori.core.build.tools.ToolsManager
import dalvik.system.PathClassLoader
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
        threads: Int? = null,
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
                threads?.let { add("--thread-count"); add(it.toString()) }
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
        run(if (useR8) "com.android.tools.r8.R8" else "com.android.tools.r8.D8", toolName, args, emit, inputs.joinToString { it.name })
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

    /** D8, R8 or L8 ran out of the app's heap: not an error in the project's code. */
    class OutOfMemory(message: String, cause: Throwable) : RuntimeException(message, cause)

    private fun run(entryClass: String, toolName: String, args: Array<String>, emit: (String) -> Unit, what: String = "") {
        waitForAbandoned(toolName, emit)
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

            // A big project only needs time; a stuck or starved D8 needs stopping. So it stops when it
            // makes no CPU progress for a while, when the heap stays full (it would only crawl), or
            // at a hard cap, not at a fixed wall that large projects hit while still working.
            val startMs = System.currentTimeMillis()
            var lastCpu = android.os.Process.getElapsedCpuTime()
            var idleSince = startMs
            var fullSince: Long? = null
            try {
                while (true) {
                    try {
                        future.get(HEARTBEAT_SECONDS, TimeUnit.SECONDS)
                        break
                    } catch (_: TimeoutException) {
                        val now = System.currentTimeMillis()
                        val elapsed = (now - startMs) / 1000
                        val cpu = android.os.Process.getElapsedCpuTime()
                        if (cpu - lastCpu >= MIN_CPU_MS_PER_BEAT) idleSince = now
                        lastCpu = cpu
                        val rt = Runtime.getRuntime()
                        val used = rt.totalMemory() - rt.freeMemory()
                        fullSince = if (used > rt.maxMemory() * HEAP_FULL_FRACTION) fullSince ?: now else null
                        val stopWhy = when {
                            elapsed >= DEX_MAX_SECONDS -> "took over ${DEX_MAX_SECONDS / 60} minutes"
                            now - idleSince >= DEX_STALL_SECONDS * 1000 -> "made no progress for ${DEX_STALL_SECONDS}s"
                            fullSince != null && now - fullSince!! >= HEAP_FULL_SECONDS * 1000 ->
                                "ran with the memory full for ${HEAP_FULL_SECONDS}s"
                            else -> null
                        }
                        if (stopWhy != null) {
                            future.cancel(true)
                            // cancel() can't stop D8's threads: remember it, so the next build waits
                            // for it instead of starting with its memory still taken.
                            abandoned = future
                            val maxMb = rt.maxMemory() / (1024 * 1024)
                            throw RuntimeException(
                                "$toolName stopped: it $stopWhy${if (what.isNotEmpty()) " on $what" else ""}. Not an error in the " +
                                    "code: Bori Code had ${(rt.maxMemory() - used) / (1024 * 1024)} of $maxMb MB free. Close and " +
                                    "reopen Bori Code, then build again.",
                            )
                        }
                        emit("$toolName: still converting... ${elapsed}s")
                    }
                }
            } finally {
                executor.shutdownNow()
                System.setErr(originalErr)
                // Stack frames and repeats (one Kotlin-metadata warning per class) only bury the message.
                tidyToolOutput(captured.toString().lines())
                    .take(MAX_TOOL_LINES)
                    .forEach { emit("$toolName: $it") }
            }
        } catch (e: ClassNotFoundException) {
            throw RuntimeException("$toolName class not found in d8.jar — verify the bundled asset is correct", e)
        } catch (e: Throwable) {
            // D8 wraps an OutOfMemoryError (ExecutionException → CompilationFailedException →
            // suppressed → …), so it read as "see the errors above" and agents hunted for code errors.
            if (e !is OutOfMemory && findOutOfMemory(e)) {
                val maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
                throw OutOfMemory(
                    "$toolName ran out of memory${if (what.isNotEmpty()) " on $what" else ""} (this phone gives " +
                        "Bori Code $maxMb MB). Not an error in the code: build again, or close other apps first.",
                    e,
                )
            }
            if (e is RuntimeException) throw e
            val root = (e as? java.util.concurrent.ExecutionException)?.cause?.let { (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it } ?: e
            val message = when {
                root.javaClass.simpleName == "CompilationFailedException" -> "$toolName failed: see the $toolName errors above"
                else -> "$toolName failed: ${root.message ?: root.javaClass.simpleName}"
            }
            throw RuntimeException(message, e)
        }
    }

    /** Waits a little for a D8 an earlier build gave up on; it holds memory until it ends. */
    private fun waitForAbandoned(toolName: String, emit: (String) -> Unit) {
        val old = abandoned ?: return
        if (!old.isDone) {
            emit("$toolName: waiting for the last build's $toolName to let go of its memory…")
            runCatching { old.get(ABANDONED_WAIT_SECONDS, TimeUnit.SECONDS) }
        }
        if (!old.isDone) {
            throw RuntimeException(
                "The last build's $toolName is still running and using memory. Close and reopen Bori Code, then build again.",
            )
        }
        abandoned = null
    }

    companion object {
        private const val HEARTBEAT_SECONDS = 10L
        private const val DEX_MAX_SECONDS = 600L
        private const val DEX_STALL_SECONDS = 120L
        private const val HEAP_FULL_SECONDS = 90L
        private const val HEAP_FULL_FRACTION = 0.95
        private const val MIN_CPU_MS_PER_BEAT = 200L
        private const val ABANDONED_WAIT_SECONDS = 30L

        /** A D8/R8 run a timeout gave up on; its threads can't be stopped, only waited for. */
        @Volatile private var abandoned: Future<*>? = null
        private const val MAX_TOOL_LINES = 60
        private val KOTLIN_METADATA_WARNING = Regex("Unexpected error during rewriting of Kotlin metadata", RegexOption.IGNORE_CASE)

        /**
         * D8's stderr without stack frames and repeats. Its "rewriting of Kotlin metadata" warning
         * comes once per class (D8 predates the project's Kotlin metadata; harmless): the first stays,
         * the rest become a count.
         */
        fun tidyToolOutput(lines: List<String>): List<String> {
            val kept = lines.filter { it.isNotBlank() && !it.trimStart().startsWith("at ") }.distinct()
            val metadata = kept.count { KOTLIN_METADATA_WARNING.containsMatchIn(it) }
            if (metadata <= 1) return kept
            val first = kept.indexOfFirst { KOTLIN_METADATA_WARNING.containsMatchIn(it) }
            return kept.filterIndexed { i, l -> i == first || !KOTLIN_METADATA_WARNING.containsMatchIn(l) }
                .toMutableList()
                .apply { add(first + 1, "…the same warning for ${metadata - 1} more classes (harmless: the app still works)") }
        }

        /** Whether an OutOfMemoryError is anywhere in [e]'s causes, targets or suppressed exceptions. */
        fun findOutOfMemory(e: Throwable): Boolean {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            val queue = ArrayDeque<Throwable>().apply { add(e) }
            while (queue.isNotEmpty()) {
                val t = queue.removeFirst()
                if (!seen.add(t)) continue
                if (t is OutOfMemoryError) return true
                t.cause?.let(queue::add)
                (t as? java.lang.reflect.InvocationTargetException)?.targetException?.let(queue::add)
                t.suppressed.forEach(queue::add)
            }
            return false
        }

        /** The classes*.dex files in [dir], in order (classes.dex, classes2.dex, …). */
        fun dexFiles(dir: File): List<File> =
            dir.listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }
                ?.sortedBy { it.name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1 }
                .orEmpty()
    }
}
