package com.bori.core.build.model

import java.io.File

/** APPLICATION and LIBRARY are Android modules; JVM_LIBRARY is a plain Kotlin/Java module. */
enum class ModuleType { APPLICATION, LIBRARY, JVM_LIBRARY }

/** A `buildConfigField(type, name, value)` line; [value] is Java source, e.g. `"hola"` with the quotes. */
data class BuildConfigField(val type: String, val name: String, val value: String)

data class ModuleInfo(
    val name: String,           // e.g. ":app", ":core", ":utils"
    val dir: File,              // absolute directory of this module
    val type: ModuleType,
    /** The R and BuildConfig package. */
    val namespace: String,
    /** The installed package name: applicationId for the app module, the namespace for libraries. */
    val applicationId: String,
    val versionCode: Int?,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    /** Whether to generate BuildConfig (always for the app, `buildFeatures { buildConfig = true }` for libraries). */
    val buildConfig: Boolean,
    val buildConfigFields: List<BuildConfigField>,
    val moduleDeps: List<String>,           // project(":core") refs
    val mavenDeps: List<String>,            // implementation/api/runtimeOnly coords
    val compileOnlyDeps: List<String>,      // compileOnly coords: on the classpath, not in the APK
    val annotationProcessors: List<String>, // annotationProcessor(...) coords
    val kaptProcessors: List<String>,       // kapt(...) coords
    val kspProcessors: List<String>,        // ksp(...) coords (not supported yet)
    val sourceDirs: List<File>,             // src/main/java and src/main/kotlin that exist
    val resDir: File?,
    val assetsDir: File?,
    val jniLibsDir: File?,
    val manifest: File?,
    /** proguardFiles(...) of the app module and consumerProguardFiles(...) of libraries, that exist. */
    val proguardFiles: List<File>,
)
