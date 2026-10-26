package com.bori.core.build.tools

import android.content.Context
import com.bori.core.build.steps.LibraryExtractStep
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolsManager @Inject constructor(
    @ApplicationContext internal val context: Context,
) {
    private val toolsDir = File(context.filesDir, "tools")
    private val libraryExtractStep = LibraryExtractStep(context, toolsDir)

    val d8Jar: File get() = File(toolsDir, "d8.jar")
    val androidStubs: File get() = File(toolsDir, "android-stubs.jar")
    val kotlincJar: File get() = File(toolsDir, "kotlinc.jar")
    val kotlinStdlibJar: File get() = File(toolsDir, "kotlin-stdlib.jar")
    val kotlinStdlibDexJar: File get() = File(toolsDir, "kotlin-stdlib-dex.jar")
    val kotlinReflectDexJar: File get() = File(toolsDir, "kotlin-reflect-dex.jar")
    val trove4jDexJar: File get() = File(toolsDir, "trove4j-dex.jar")
    val swingStubsDexJar: File get() = File(toolsDir, "swing-stubs-dex.jar")
    val jetbrainsAnnotationsDexJar: File get() = File(toolsDir, "jetbrains-annotations-dex.jar")
    val coreLambdaStubs: File get() = File(toolsDir, "core-lambda-stubs.jar")
    val kotlinxCoroutinesDexJar: File get() = File(toolsDir, "kotlinx-coroutines-dex.jar")

    // Kotlin compiler plugins and KSP, pre-dexed with their original entries (see copyBuildTools)
    val composePluginJar: File get() = File(toolsDir, "compose-plugin.jar")
    val serializationPluginJar: File get() = File(toolsDir, "serialization-plugin.jar")
    val parcelizePluginJar: File get() = File(toolsDir, "parcelize-plugin.jar")
    val kspPluginJar: File get() = File(toolsDir, "ksp-plugin.jar")
    val kspApiJar: File get() = File(toolsDir, "ksp-api.jar")
    val kspDepsDexJar: File get() = File(toolsDir, "ksp-deps-dex.jar")
    /** javax.lang.model & co., which symbol processors' JavaPoet needs and Android lacks. */
    val javaCompilerApiDexJar: File get() = File(toolsDir, "java-compiler-api-dex.jar")
    /** Stand-ins processors load ahead of their own dependencies (sqlite-jdbc for Room). */
    val processorStubsDexJar: File get() = File(toolsDir, "processor-stubs-dex.jar")

    /** Everything the compiler's class loader loads besides kotlinc.jar itself. */
    val compilerRuntimeJars: List<File> get() = listOf(
        kotlinStdlibDexJar, kotlinReflectDexJar, trove4jDexJar, swingStubsDexJar, jetbrainsAnnotationsDexJar,
        kotlinxCoroutinesDexJar, composePluginJar, serializationPluginJar, parcelizePluginJar,
        kspPluginJar, kspApiJar, kspDepsDexJar, javaCompilerApiDexJar, processorStubsDexJar,
    )
    val aapt2: File get() {
        val system = File("/system/bin/aapt2")
        if (system.exists() && system.canExecute()) return system
        return File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")
    }

    // AndroidX library bundle paths (populated after prepare() calls extractIfNeeded)
    val androidxClassesJar: File get() = libraryExtractStep.androidxClassesJar
    val androidxClasses2Dex: File get() = libraryExtractStep.androidxClasses2Dex
    val androidxResFlatDir: File get() = libraryExtractStep.androidxResFlatDir
    val androidxPackagesTxt: File get() = libraryExtractStep.androidxPackagesTxt
    val androidxManifestEntriesXml: File get() = libraryExtractStep.androidxManifestEntriesXml
    val androidxVersionsTxt: File get() = libraryExtractStep.androidxVersionsTxt

    /**
     * Extracts all tool assets, re-extracting if the APK has been updated since last install.
     * Returns true if tools were re-extracted (so callers can clear downstream caches).
     */
    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        toolsDir.mkdirs()
        val versionFile = File(toolsDir, "apk_update_time")
        val currentTime = context.packageManager
            .getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val reExtracted = !versionFile.exists() || versionFile.readText().trim() != currentTime
        if (reExtracted) {
            toolsDir.listFiles()?.forEach { it.deleteRecursively() }
            toolsDir.mkdirs()
        }
        extractIfNeeded("d8.jar", "d8.jar")
        extractIfNeeded("android-stubs.jar", "android-stubs.jar")
        extractIfNeeded("kotlinc.jar", "kotlinc.jar")
        extractIfNeeded("kotlin-stdlib.jar", "kotlin-stdlib.jar")
        extractIfNeeded("core-lambda-stubs.jar", "core-lambda-stubs.jar")
        extractIfNeeded("kotlin-stdlib-dex.jar", "kotlin-stdlib-dex.jar")
        extractIfNeeded("kotlin-reflect-dex.jar", "kotlin-reflect-dex.jar")
        extractIfNeeded("trove4j-dex.jar", "trove4j-dex.jar")
        extractIfNeeded("swing-stubs-dex.jar", "swing-stubs-dex.jar")
        extractIfNeeded("jetbrains-annotations-dex.jar", "jetbrains-annotations-dex.jar")
        for (name in listOf(
            "kotlinx-coroutines-dex.jar", "compose-plugin.jar", "serialization-plugin.jar", "parcelize-plugin.jar",
            "ksp-plugin.jar", "ksp-api.jar", "ksp-deps-dex.jar", "java-compiler-api-dex.jar", "processor-stubs-dex.jar",
        )) extractIfNeeded(name, name)
        // Extract AndroidX library bundle assets (classes.jar, classes2.dex, res .flat files, etc.)
        libraryExtractStep.extractIfNeeded()
        versionFile.writeText(currentTime)
        reExtracted
    }

    private fun extractIfNeeded(assetName: String, fileName: String) {
        val outFile = File(toolsDir, fileName)
        if (outFile.exists() && !outFile.canWrite()) return
        try {
            context.assets.open(assetName).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            outFile.setReadOnly()
        } catch (e: Exception) {
            outFile.delete()
            throw IllegalStateException(
                "Asset '$assetName' not bundled. Run ./gradlew copyBuildTools.",
                e,
            )
        }
    }
}
