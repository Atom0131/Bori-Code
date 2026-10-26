package com.bori.core.build.steps

import com.bori.core.build.tools.ToolsManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class AaptStep(private val tools: ToolsManager) {

    /**
     * Compiles all resources in [resRoot] into .flat files in [outputDir].
     * Enumerates files individually (avoids --dir output-directory ambiguity in some aapt2 builds).
     */
    fun compile(resRoot: File, outputDir: File, emit: (String) -> Unit) {
        val resourceFiles = resRoot.walkTopDown()
            .filter { it.isFile }
            .toList()

        emit("AAPT2 compile: ${resourceFiles.size} resource file(s) in ${resRoot.absolutePath}")
        if (resourceFiles.isEmpty()) return

        val args = buildList {
            add(tools.aapt2.absolutePath)
            add("compile")
            add("-o"); add(outputDir.absolutePath)
            resourceFiles.forEach { add(it.absolutePath) }
        }
        runProcess(args, emit, timeoutSeconds = 30L)

        val flatCount = outputDir.walkTopDown().filter { it.isFile }.count()
        emit("AAPT2 compile: $flatCount flat file(s) written")
    }

    /**
     * Links user compiled resources + pre-compiled library flat files into an APK shell and
     * generates R.java for the app package and each library namespace package.
     */
    fun link(
        manifestFile: File,
        compiledResDir: File,
        libraryResFlatDirs: List<File> = emptyList(),
        rJavaDir: File,
        apkOut: File,
        emit: (String) -> Unit,
        minSdk: Int = 21,
        targetSdk: Int = 35,
        extraPackages: List<String> = emptyList(),
    ) {
        val userFlatFiles = compiledResDir.walkTopDown()
            .filter { it.isFile && it.extension == "flat" }
            .map { it.absolutePath }
            .toList()

        val libFlatFiles = libraryResFlatDirs
            .filter { it.exists() }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "flat" }
                    .map { it.absolutePath }
                    .toList()
            }

        emit("AAPT2 link: ${userFlatFiles.size} user flat(s) + ${libFlatFiles.size} library flat(s) → ${apkOut.name}")

        val args = buildList {
            add(tools.aapt2.absolutePath)
            add("link")
            add("-I"); add(tools.androidStubs.absolutePath)
            add("--manifest"); add(manifestFile.absolutePath)
            add("--min-sdk-version"); add(minSdk.toString())
            add("--target-sdk-version"); add(targetSdk.toString())
            add("--auto-add-overlay")
            // User flat files first (higher priority — app resources override library resources)
            userFlatFiles.forEach { add(it) }
            // Library flat files after
            libFlatFiles.forEach { add(it) }
            add("-o"); add(apkOut.absolutePath)
            add("--java"); add(rJavaDir.absolutePath)
            if (extraPackages.isNotEmpty()) {
                add("--extra-packages"); add(extraPackages.joinToString(":"))
            }
        }
        // Increased timeout: library resources add significant link work
        runProcess(args, emit, timeoutSeconds = 60L)
    }

    private fun runProcess(args: List<String>, emit: (String) -> Unit, timeoutSeconds: Long) {
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()

        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.forEachLine { emit(it) }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("aapt2 timed out after ${timeoutSeconds}s")
        }
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw RuntimeException("aapt2 exited with code $exitCode: ${args.first { it != tools.aapt2.absolutePath }}")
        }
    }
}
