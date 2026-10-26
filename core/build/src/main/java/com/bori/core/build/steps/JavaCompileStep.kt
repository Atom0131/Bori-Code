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
            add("-source"); add("8")
            add("-target"); add("8")
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
            val compiler = EclipseMain(pw, pw, false, null, null)
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

    companion object {
        private const val COMPILE_TIMEOUT_SECONDS = 90L
    }
}
