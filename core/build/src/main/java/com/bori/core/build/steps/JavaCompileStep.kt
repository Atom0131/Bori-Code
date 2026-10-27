package com.bori.core.build.steps

import com.bori.core.build.tools.ToolsManager
import org.eclipse.jdt.internal.compiler.batch.Main as EclipseMain
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class JavaCompileStep(private val tools: ToolsManager) {

    /**
     * Compiles all .java files found under [sourceRoots] into [classesDir].
     * [extraClasspath] is appended to the -classpath argument (e.g. AndroidX library JAR).
     * Returns true on success, false on compilation error (errors are forwarded to [emit]).
     */
    fun compile(
        sourceRoots: List<File>,
        classesDir: File,
        extraClasspath: List<File> = emptyList(),
        changedOnly: List<File>? = null,
        processorJars: List<File> = emptyList(),
        generatedSourcesDir: File? = null,
        applyHiddenApiBypass: () -> Unit = {},
        javaLevel: Int = DEFAULT_JAVA_LEVEL,
        emit: (String) -> Unit,
    ): Boolean {
        val allJavaFiles = sourceRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .toList()
        }

        if (allJavaFiles.isEmpty()) {
            emit("Java: no .java source files found — skipping")
            return true
        }

        // If a changed-only list is provided, only compile those files.
        // Always include all files on the classpath so cross-file references resolve.
        val javaFiles = if (changedOnly != null && changedOnly.isNotEmpty()) {
            changedOnly.map { it.absolutePath }
        } else {
            allJavaFiles.map { it.absolutePath }
        }

        val logSink = StringWriter()

        val bootclasspath = listOf(
            tools.androidStubs.absolutePath,
            tools.coreLambdaStubs.absolutePath,
        ).joinToString(":")

        val classpath = (listOf(tools.androidStubs, classesDir) + extraClasspath)
            .joinToString(":") { it.absolutePath }

        val validProcessorJars = processorJars.filter { it.exists() }
        val args = buildList {
            val level = javaLevel.coerceIn(8, MAX_JAVA_LEVEL).toString()
            add("-source"); add(level)
            add("-target"); add(level)
            add("-Xemacs") // "File.java:12: error: …", the form DiagnosticParser reads
            add("-bootclasspath"); add(bootclasspath)
            add("-classpath"); add(classpath)
            if (validProcessorJars.isEmpty()) {
                add("-proc:none")
            } else {
                add("-processorpath"); add(validProcessorJars.joinToString(":") { it.absolutePath })
                if (generatedSourcesDir != null) {
                    generatedSourcesDir.mkdirs()
                    add("-s"); add(generatedSourcesDir.absolutePath)
                }
            }
            add("-d"); add(classesDir.absolutePath)
            add("-encoding"); add("UTF-8")
            addAll(javaFiles)
        }.toTypedArray()

        val pw = PrintWriter(logSink, true)

        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<Boolean> {
            applyHiddenApiBypass()
            val compiler = AndroidEcj(pw)
            compiler.compile(args)
        }

        val startMs = System.currentTimeMillis()
        val heartbeatEx = Executors.newSingleThreadScheduledExecutor()
        val heartbeat: ScheduledFuture<*> = heartbeatEx.scheduleAtFixedRate({
            val elapsed = (System.currentTimeMillis() - startMs) / 1000
            emit("Java: still compiling... ${elapsed}s / ${COMPILE_TIMEOUT_SECONDS}s")
        }, 10, 10, TimeUnit.SECONDS)

        val success = try {
            future.get(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            emit("Java compiler (ECJ): Timed out after ${COMPILE_TIMEOUT_SECONDS}s. The file(s) may be too complex for on-device compilation.")
            logSink.toString().lines().filter { it.isNotBlank() }.forEach { emit(it) }
            return false
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            val msg = when (cause) {
                is ExceptionInInitializerError -> {
                    val inner = cause.cause?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""
                    "Java compiler failed to initialize$inner. Force-stop the app, reopen it, and try again."
                }
                is NoClassDefFoundError ->
                    "Java compiler class missing: ${cause.message}. Reinstall the app."
                else ->
                    "Java compiler crashed: ${cause.javaClass.simpleName}: ${cause.message ?: "(no details)"}"
            }
            emit(msg)
            return false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            emit("Java compiler interrupted")
            return false
        } finally {
            heartbeat.cancel(false)
            heartbeatEx.shutdownNow()
            executor.shutdownNow()
        }
        logSink.toString().lines().filter { it.isNotBlank() }.forEach { emit(it) }
        return success
    }

    /**
     * ECJ refuses -bootclasspath from Java 9 on, because it wants the running JDK's modules,
     * which Android doesn't have. Skipping that check makes it compile against android.jar the
     * way it does at Java 8.
     */
    private class AndroidEcj(pw: PrintWriter) : EclipseMain(pw, pw, false, null, null) {
        override fun validateClasspathOptions(
            bootclasspaths: ArrayList<String>?,
            endorsedDirClasspaths: ArrayList<String>?,
            extdirsClasspaths: ArrayList<String>?,
        ): Long = 0L // only used with --release, which isn't passed
    }

    companion object {
        private const val COMPILE_TIMEOUT_SECONDS = 90L
        /** Java level when the build file doesn't set one; Java 17 also accepts Java 8 code. */
        const val DEFAULT_JAVA_LEVEL = 17
        /** ECJ 3.32 goes up to 19; 17 is what Android Studio projects use. */
        const val MAX_JAVA_LEVEL = 17
    }
}
