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
     * Links compiled resources into an APK shell and generates R.java for the app package and
     * each library package. [flatDirs] go lowest priority first (bundled libraries, then
     * dependencies, library modules, the app), and each is an overlay, so a resource in a later
     * directory overrides the same one in an earlier directory, as Gradle's resource merge does.
     *
     * [renameManifestPackage] sets the installed package (applicationId) when it differs from the
     * manifest's package (the namespace, which R keeps); AAPT2 makes the component class names
     * absolute first. [proguardRulesOut] gets keep rules for the classes the manifest and layouts use.
     */
    fun link(
        manifestFile: File,
        flatDirs: List<File>,
        rJavaDir: File,
        apkOut: File,
        emit: (String) -> Unit,
        minSdk: Int = 21,
        targetSdk: Int = 35,
        extraPackages: List<String> = emptyList(),
        versionCode: Int? = null,
        versionName: String? = null,
        renameManifestPackage: String? = null,
        proguardRulesOut: File? = null,
    ) {
        val flatFiles = flatDirs
            .filter { it.exists() }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "flat" }
                    .map { it.absolutePath }
                    .sorted()
                    .toList()
            }

        emit("AAPT2 link: ${flatFiles.size} compiled resource(s) from ${flatDirs.size} source(s) → ${apkOut.name}")

        val args = buildList {
            add(tools.aapt2.absolutePath)
            add("link")
            add("-I"); add(tools.androidStubs.absolutePath)
            add("--manifest"); add(manifestFile.absolutePath)
            add("--min-sdk-version"); add(minSdk.toString())
            add("--target-sdk-version"); add(targetSdk.toString())
            versionCode?.let { add("--version-code"); add(it.toString()) }
            versionName?.let { add("--version-name"); add(it) }
            if (versionCode != null || versionName != null) add("--replace-version")
            renameManifestPackage?.let { add("--rename-manifest-package"); add(it) }
            add("--auto-add-overlay")
            flatFiles.forEach { add("-R"); add(it) }
            add("-o"); add(apkOut.absolutePath)
            add("--java"); add(rJavaDir.absolutePath)
            proguardRulesOut?.let { add("--proguard"); add(it.absolutePath) }
            if (extraPackages.isNotEmpty()) {
                add("--extra-packages"); add(extraPackages.joinToString(":"))
            }
        }
        // Increased timeout: library resources add significant link work
        runProcess(args, emit, timeoutSeconds = 120L)
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
