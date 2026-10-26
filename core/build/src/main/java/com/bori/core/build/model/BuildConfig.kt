package com.bori.core.build.model

import java.io.File

data class BuildConfig(
    val projectName: String,
    val packageName: String,
    val minSdk: Int,
    val targetSdk: Int,
    /** Root of Java source files, e.g. /sdcard/Mimir/<name>/app/src/main/java/ */
    val sourceRoot: File,
    /** Resource directory, e.g. /sdcard/Mimir/<name>/app/src/main/res/ */
    val resRoot: File,
    /** AndroidManifest.xml path */
    val manifestFile: File,
    /** Writable build output directory, e.g. context.filesDir/builds/<name>/ */
    val buildRoot: File,
    // --- AndroidX library bundle paths (populated from ToolsManager after prepare()) ---
    /** Fat JAR of all bundled AndroidX library classes — added to Kotlin/Java compile classpath. */
    val androidxClassesJar: File,
    /** Pre-dexed library DEX — included in APK as classes2.dex (no on-device D8 for libraries). */
    val androidxClasses2Dex: File,
    /** Directory of AAPT2-pre-compiled .flat files from all bundled library res/ directories. */
    val androidxResFlatDir: File,
    /** Newline-separated library namespace package names for aapt2 --extra-packages. */
    val androidxPackagesTxt: File,
    /** XML fragment of <uses-permission> / <uses-feature> elements from library manifests. */
    val androidxManifestEntriesXml: File,
    // --- User-declared dependency outputs (null when no dependencies declared) ---
    /** Fat JAR of all user-declared library classes — merged into Kotlin/Java compile classpath. */
    val userLibClassesJar: File? = null,
    /** Directory of AAPT2-pre-compiled .flat files from user library res/ directories. */
    val userLibResFlatDir: File? = null,
    /** Package names from user library manifests for aapt2 --extra-packages. */
    val userLibPackages: List<String> = emptyList(),
)
