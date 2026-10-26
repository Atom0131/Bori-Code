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
     * Converts .class files in [classesDir] to classes.dex in [dexOutputDir] using D8 (debug)
     * or R8 (release). R8 is activated automatically when [variant] is RELEASE and
     * [proguardRulesFiles] is non-empty; it performs shrinking, obfuscation, and optimisation
     * in addition to dexing. A mapping.txt file is written alongside the DEX output so
     * release stack traces can be de-obfuscated.
     */
    fun convert(
        classesDir: File,
        dexOutputDir: File,
        minSdk: Int,
        emit: (String) -> Unit,
        extraJars: List<File> = emptyList(),
        variant: BuildVariant = BuildVariant.DEBUG,
        proguardRulesFiles: List<File> = emptyList(),
    ) {
        val classFiles = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.absolutePath }
            .toList()

        if (classFiles.isEmpty()) {
            emit("WARNING: No .class files found in ${classesDir.absolutePath}")
            return
        }

        val useR8 = variant == BuildVariant.RELEASE && proguardRulesFiles.isNotEmpty()
        val toolName = if (useR8) "R8" else "D8"
        emit("$toolName: converting ${classFiles.size} class file(s)${if (useR8) " with shrinking + obfuscation" else ""}")

        try {
            val loader = PathClassLoader(tools.d8Jar.absolutePath, ClassLoader.getSystemClassLoader())
            val entryClass = if (useR8) "com.android.tools.r8.R8" else "com.android.tools.r8.D8"
            val toolClass = loader.loadClass(entryClass)
            val mainMethod = toolClass.getMethod("main", Array<String>::class.java)

            val validExtraJars = extraJars.filter { it.exists() }
            if (validExtraJars.isNotEmpty()) {
                emit("$toolName: including ${validExtraJars.size} user library JAR(s)")
            }

            val args = buildList {
                add("--output"); add(dexOutputDir.absolutePath)
                add("--min-api"); add(minSdk.toString())
                add("--release")   // always for R8; safe for D8 release builds too
                add("--lib"); add(tools.androidStubs.absolutePath)
                if (tools.kotlinStdlibJar.exists()) add(tools.kotlinStdlibJar.absolutePath)
                validExtraJars.forEach { add(it.absolutePath) }
                if (useR8) {
                    proguardRulesFiles.filter { it.exists() }.forEach { rules ->
                        add("--pg-conf"); add(rules.absolutePath)
                        emit("$toolName: applying rules from ${rules.name}")
                    }
                    val mappingFile = File(dexOutputDir, "mapping.txt")
                    add("--pg-map-output"); add(mappingFile.absolutePath)
                    emit("$toolName: mapping file → ${mappingFile.absolutePath}")
                }
                addAll(classFiles)
            }.toTypedArray()

            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit<Unit> { mainMethod.invoke(null, args as Any) }

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
            }
        } catch (e: ClassNotFoundException) {
            throw RuntimeException("$toolName class not found in d8.jar — verify the bundled asset is correct", e)
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Throwable) {
            val message = when (e) {
                is OutOfMemoryError -> "$toolName: Out of Memory. The project is too large for on-device dexing."
                else -> "$toolName failed: ${e.cause?.message ?: e.message}"
            }
            throw RuntimeException(message, e)
        }
    }

    companion object {
        private const val DEX_TIMEOUT_SECONDS = 60L
    }
}
