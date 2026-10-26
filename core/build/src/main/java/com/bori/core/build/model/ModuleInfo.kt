package com.bori.core.build.model

import java.io.File

enum class ModuleType { APPLICATION, LIBRARY }

data class ModuleInfo(
    val name: String,           // e.g. ":app", ":core", ":utils"
    val dir: File,              // absolute directory of this module
    val type: ModuleType,
    val namespace: String,
    val minSdk: Int,
    val targetSdk: Int,
    val moduleDeps: List<String>,           // project(":core") refs
    val mavenDeps: List<String>,            // external Maven coords
    val annotationProcessors: List<String>, // annotationProcessor(...) coords
)
