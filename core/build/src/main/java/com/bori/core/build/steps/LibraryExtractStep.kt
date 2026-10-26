package com.bori.core.build.steps

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Extracts pre-processed AndroidX library assets from the APK's asset store into
 * a sub-directory of toolsDir so the build pipeline can use them.
 *
 * Assets managed:
 *   androidx-classes.jar       — compile-time classpath JAR (all library classes, no R/BuildConfig)
 *   androidx-classes2.dex      — pre-dexed runtime DEX (becomes classes2.dex in the output APK)
 *   androidx-res-compiled.zip  — AAPT2-pre-compiled .flat resource files
 *   androidx-packages.txt      — library namespace package names for aapt2 --extra-packages
 *   androidx-manifest-entries.xml — <uses-permission>/<uses-feature> to merge into user manifest
 *
 * Extraction is skipped if the file already exists (invalidation is handled by ToolsManager
 * which deletes toolsDir when the APK update time changes).
 */
class LibraryExtractStep(private val context: Context, private val toolsDir: File) {

    val androidxClassesJar: File get() = File(toolsDir, "androidx-classes.jar")
    val androidxClasses2Dex: File get() = File(toolsDir, "androidx-classes2.dex")
    val androidxResCompiledZip: File get() = File(toolsDir, "androidx-res-compiled.zip")
    val androidxPackagesTxt: File get() = File(toolsDir, "androidx-packages.txt")
    val androidxManifestEntriesXml: File get() = File(toolsDir, "androidx-manifest-entries.xml")
    val androidxVersionsTxt: File get() = File(toolsDir, "androidx-versions.txt")

    /** Directory containing all pre-compiled .flat resource files extracted from the ZIP. */
    val androidxResFlatDir: File get() = File(toolsDir, "androidx-res-flat")

    fun extractIfNeeded() {
        ASSET_NAMES.forEach { assetName ->
            val dest = File(toolsDir, assetName)
            if (!dest.exists()) extractAsset(assetName, dest)
        }
        unzipFlatsIfNeeded()
    }

    private fun unzipFlatsIfNeeded() {
        val flatDir = androidxResFlatDir
        if (flatDir.exists() && flatDir.list()?.isNotEmpty() == true) return
        flatDir.mkdirs()
        if (!androidxResCompiledZip.exists()) return
        ZipInputStream(androidxResCompiledZip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    // Entry names already use _ as separator (set by copyBuildTools), so the
                    // file name is already collision-safe. Place everything flat in the dir.
                    val dest = File(flatDir, File(entry.name).name)
                    dest.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    private fun extractAsset(assetName: String, dest: File) {
        try {
            context.assets.open(assetName).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            dest.delete()
            throw IllegalStateException(
                "Asset '$assetName' not bundled. Run ./gradlew :core:build:copyBuildTools.",
                e,
            )
        }
    }

    companion object {
        val ASSET_NAMES = listOf(
            "androidx-classes.jar",
            "androidx-classes2.dex",
            "androidx-res-compiled.zip",
            "androidx-packages.txt",
            "androidx-manifest-entries.xml",
            "androidx-versions.txt",
        )
    }
}
