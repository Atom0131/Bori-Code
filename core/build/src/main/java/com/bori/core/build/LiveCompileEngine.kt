package com.bori.core.build

import android.content.Context
import android.util.Log
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.core.build.model.Diagnostic
import com.bori.core.build.steps.JavaCompileStep
import com.bori.core.build.steps.KotlinCompileStep
import com.bori.core.build.tools.ToolsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveCompileEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tools: ToolsManager,
    private val kotlinDaemon: KotlinCompilerDaemon,
) {
    private val javaClassesDir = File(context.cacheDir, "live-compile/java-classes")
    private val ktClassesDir   = File(context.cacheDir, "live-compile/kt-classes")

    /**
     * Compiles all sources under [sourceRoot], returning diagnostics from both the Kotlin and
     * Java passes. [extraClasspath] should contain the AndroidX jar and any resolved user-library
     * jars so that live errors match what the full build would report.
     *
     * Kotlin files are compiled first so Java sources can reference Kotlin-generated classes.
     * Uses [CompilerPerformanceProfile.MEDIUM] to keep the warm daemon alive between runs.
     */
    suspend fun compile(
        sourceRoot: File,
        rJavaDir: File?,
        extraClasspath: List<File> = emptyList(),
    ): List<Diagnostic> = withContext(Dispatchers.IO) {
        tools.prepare()

        val allDiagnostics = mutableListOf<Diagnostic>()

        // ── Kotlin pass ──────────────────────────────────────────────────────
        val ktFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        if (ktFiles.isNotEmpty()) {
            ktClassesDir.mkdirs()
            val ktLogs = mutableListOf<String>()
            Log.d(TAG, "live Kotlin compile: ${ktFiles.size} file(s)")
            KotlinCompileStep(tools).compile(
                sourceRoots = listOf(sourceRoot),
                classesDir = ktClassesDir,
                extraClasspath = extraClasspath,
                timeoutSeconds = LIVE_KT_TIMEOUT_SECONDS,
                daemon = kotlinDaemon,
                profile = CompilerPerformanceProfile.MEDIUM,
                emit = { ktLogs += it },
            )
            val ktDiags = DiagnosticParser.parse(ktLogs)
            Log.d(TAG, "live Kotlin: ${ktDiags.size} diagnostic(s)")
            allDiagnostics += ktDiags
        }

        // ── Java pass ────────────────────────────────────────────────────────
        javaClassesDir.deleteRecursively()
        javaClassesDir.mkdirs()

        val javaSourceRoots = buildList {
            add(sourceRoot)
            if (rJavaDir != null && rJavaDir.isDirectory) add(rJavaDir)
        }
        // Include Kotlin output so Java sources can reference Kotlin classes
        val javaClasspath = extraClasspath + listOfNotNull(ktClassesDir.takeIf { it.exists() })

        val javaLogs = mutableListOf<String>()
        Log.d(TAG, "live Java compile")
        JavaCompileStep(tools).compile(
            sourceRoots = javaSourceRoots,
            classesDir = javaClassesDir,
            extraClasspath = javaClasspath,
        ) { javaLogs += it }
        val javaDiags = DiagnosticParser.parse(javaLogs)
        Log.d(TAG, "live Java: ${javaDiags.size} diagnostic(s)")
        allDiagnostics += javaDiags

        allDiagnostics
    }

    companion object {
        private const val TAG = "LiveCompileEngine"
        private const val LIVE_KT_TIMEOUT_SECONDS = 30L
    }
}
