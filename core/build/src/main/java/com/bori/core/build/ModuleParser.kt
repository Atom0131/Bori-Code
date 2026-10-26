package com.bori.core.build

import com.bori.core.build.model.ModuleInfo
import com.bori.core.build.model.ModuleType
import java.io.File

object ModuleParser {

    fun parseIncludes(settingsFile: File): List<String> =
        Regex("""include\s*\(\s*["'](:[^"']+)["']\s*\)""")
            .findAll(settingsFile.readText())
            .map { it.groupValues[1].trim() }
            .toList()

    fun parseModuleInfo(
        projectRoot: File,
        moduleName: String,
        catalog: Map<String, String> = emptyMap(),
    ): ModuleInfo? {
        val moduleDir = File(projectRoot, moduleName.removePrefix(":").replace(':', '/'))
        if (!moduleDir.exists()) return null

        val gradleFile = listOf(
            File(moduleDir, "build.gradle.kts"),
            File(moduleDir, "build.gradle"),
        ).firstOrNull { it.exists() } ?: return null

        val text = gradleFile.readText()

        val type = if (text.contains("com.android.application")) ModuleType.APPLICATION
                   else ModuleType.LIBRARY

        val namespace = Regex("""namespace\s*[=\s]\s*["']([^"']+)["']""")
            .find(text)?.groupValues?.get(1) ?: ""

        val minSdk = Regex("""minSdk\s*[=\s]\s*(\d+)""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 21

        // Accepts both DSL forms: `targetSdk = 34` and `targetSdkVersion 34`.
        // Falls back to the project's minSdk so older manifests still link.
        val targetSdk = Regex("""targetSdk(?:Version)?\s*[=\s]\s*(\d+)""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: minSdk

        val moduleDeps = Regex("""project\s*\(\s*["'](:[^"']+)["']\s*\)""")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .distinct()
            .toList()

        val depPattern = Regex("""(?:implementation|api|compileOnly|runtimeOnly)\s*\(\s*["']([^"']+:[^"']+:[^"']+)["']\s*\)""")
        val depCatalogPattern = Regex("""(?:implementation|api|compileOnly|runtimeOnly)\s*\(\s*libs\.([a-zA-Z0-9._-]+)\s*\)""")

        val mavenInline = depPattern.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.count { c -> c == ':' } == 2 }
        val mavenCatalog = depCatalogPattern.findAll(text)
            .mapNotNull { VersionCatalogParser.resolve(it.groupValues[1], catalog) }
        val mavenDeps = (mavenInline + mavenCatalog).distinct().toList()

        val apInline = Regex("""annotationProcessor\s*\(\s*["']([^"']+:[^"']+:[^"']+)["']\s*\)""")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.count { c -> c == ':' } == 2 }
        val apCatalog = Regex("""annotationProcessor\s*\(\s*libs\.([a-zA-Z0-9._-]+)\s*\)""")
            .findAll(text)
            .mapNotNull { VersionCatalogParser.resolve(it.groupValues[1], catalog) }
        val annotationProcessors = (apInline + apCatalog).distinct().toList()

        return ModuleInfo(
            name = moduleName,
            dir = moduleDir,
            type = type,
            namespace = namespace,
            minSdk = minSdk,
            targetSdk = targetSdk,
            moduleDeps = moduleDeps,
            mavenDeps = mavenDeps,
            annotationProcessors = annotationProcessors,
        )
    }

    fun topologicalSort(modules: List<ModuleInfo>): List<ModuleInfo> {
        val byName = modules.associateBy { it.name }
        val visited = mutableSetOf<String>()
        val result = mutableListOf<ModuleInfo>()

        fun visit(info: ModuleInfo) {
            if (info.name in visited) return
            visited += info.name
            info.moduleDeps.forEach { dep -> byName[dep]?.let { visit(it) } }
            result += info
        }

        modules.forEach { visit(it) }
        return result
    }
}
