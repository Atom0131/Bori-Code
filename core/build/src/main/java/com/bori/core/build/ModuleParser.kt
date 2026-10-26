package com.bori.core.build

import com.bori.core.build.model.BuildConfigField
import com.bori.core.build.model.BuildVariant
import com.bori.core.build.model.ModuleInfo
import com.bori.core.build.model.ModuleType
import java.io.File

/**
 * Reads what the on-device builder needs from a Gradle project, without running Gradle: the
 * modules listed in settings.gradle(.kts), and each module's `android {}` settings, source
 * folders and dependencies.
 *
 * It matches common Kotlin and Groovy DSL forms in the build files (with comments removed),
 * so it understands declarative build files, not arbitrary build logic.
 */
object ModuleParser {

    /**
     * The project's modules, dependencies first (so the app module comes last). A project
     * without settings.gradle, or with no includes, is the single module `:app`.
     */
    fun readProject(projectRoot: File, variant: BuildVariant, defaultMinSdk: Int): List<ModuleInfo> {
        val settings = listOf(File(projectRoot, "settings.gradle.kts"), File(projectRoot, "settings.gradle"))
            .firstOrNull { it.exists() }
        val includes = settings?.let { parseIncludes(it) }.orEmpty().ifEmpty { listOf(":app") }
        val catalog = VersionCatalogParser.parseCatalog(File(projectRoot, "gradle/libs.versions.toml"))
        val modules = includes.mapNotNull { parseModuleInfo(projectRoot, it, catalog, variant, defaultMinSdk) }
        return topologicalSort(modules)
    }

    /** Module paths from `include(":a", ":b")`, `include ':a', ':b'` and `include("a")`, as ":a". */
    fun parseIncludes(settingsFile: File): List<String> {
        val text = stripComments(settingsFile.readText())
        return Regex("""\binclude\b\s*\(?((?:\s*["'][^"']+["']\s*,?)+)""")
            .findAll(text)
            .flatMap { m -> Regex("""["']([^"']+)["']""").findAll(m.groupValues[1]).map { it.groupValues[1].trim() } }
            .map { if (it.startsWith(":")) it else ":$it" }
            .distinct()
            .toList()
    }

    fun parseModuleInfo(
        projectRoot: File,
        moduleName: String,
        catalog: VersionCatalogParser.Catalog,
        variant: BuildVariant,
        defaultMinSdk: Int,
    ): ModuleInfo? {
        val moduleDir = File(projectRoot, moduleName.removePrefix(":").replace(':', '/'))
        if (!moduleDir.exists()) return null

        val gradleFile = listOf(
            File(moduleDir, "build.gradle.kts"),
            File(moduleDir, "build.gradle"),
        ).firstOrNull { it.exists() } ?: return null

        val vars = stringVariables(projectRoot, gradleFile)
        val text = substitute(stripComments(gradleFile.readText()), vars)
        val android = block(text, "android").orEmpty()
        val defaultConfig = block(android, "defaultConfig").orEmpty()
        val buildType = buildTypeBlock(block(android, "buildTypes").orEmpty(), variant)

        val type = when {
            text.contains("com.android.application") || Regex("""plugins\.android\.application|androidApplication""").containsMatchIn(text) ->
                ModuleType.APPLICATION
            text.contains("com.android.library") || Regex("""plugins\.android\.library|androidLibrary""").containsMatchIn(text) ->
                ModuleType.LIBRARY
            android.isNotEmpty() -> ModuleType.LIBRARY
            else -> ModuleType.JVM_LIBRARY
        }

        val namespace = string(android, "namespace") ?: manifestPackage(moduleDir) ?: ""
        val minSdk = int(defaultConfig, catalog, "minSdk", "minSdkVersion")
            ?: int(android, catalog, "minSdk", "minSdkVersion") ?: defaultMinSdk
        // Older projects only set compileSdk; targetSdk then defaults to it, as in AGP.
        val targetSdk = int(defaultConfig, catalog, "targetSdk", "targetSdkVersion")
            ?: int(android, catalog, "compileSdk", "compileSdkVersion") ?: minSdk
        val applicationId = if (type == ModuleType.APPLICATION) {
            (string(defaultConfig, "applicationId") ?: namespace) + (string(buildType, "applicationIdSuffix") ?: "")
        } else namespace

        val buildFeatures = block(android, "buildFeatures").orEmpty()
        val buildConfig = type == ModuleType.APPLICATION ||
            Regex("""\bbuildConfig\s*(?:=\s*|\s+)true""").containsMatchIn(buildFeatures)
        val viewBinding = Regex("""\bviewBinding\s*(?:=\s*|\s+)true""").containsMatchIn(buildFeatures) ||
            Regex("""\bviewBinding\s*\{[^}]*\b(?:is)?[eE]nabled\s*(?:=\s*|\s+)true""").containsMatchIn(android) ||
            Regex("""\bviewBinding\.(?:is)?[eE]nabled\s*=\s*true""").containsMatchIn(android)
        val composeFeature = Regex("""\bcompose\s*(?:=\s*|\s+)true""").containsMatchIn(buildFeatures)
        val desugaring = Regex("""\b(?:is)?[cC]oreLibraryDesugaringEnabled\s*(?:=\s*|\s+)true""").containsMatchIn(android)
        val minify = Regex("""\b(?:is)?[mM]inifyEnabled\s*(?:=\s*|\s+|\(\s*)true""").containsMatchIn(buildType)

        val deps = parseDependencies(text, catalog, variant, moduleDir)
        val main = File(moduleDir, "src/main")
        val proguardFiles = buildList {
            val names = if (type == ModuleType.APPLICATION) {
                quotedArgs(buildType, "proguardFiles").ifEmpty { listOf("proguard-rules.pro") }
            } else {
                quotedArgs(defaultConfig, "consumerProguardFiles")
            }
            names.filter { it.endsWith(".pro") || it.endsWith(".txt") }
                .map { File(moduleDir, it) }
                .filter { it.isFile }
                .forEach { add(it) }
        }

        return ModuleInfo(
            name = moduleName,
            dir = moduleDir,
            type = type,
            namespace = namespace,
            applicationId = applicationId,
            versionCode = int(defaultConfig, catalog, "versionCode"),
            versionName = string(defaultConfig, "versionName")?.let { it + (string(buildType, "versionNameSuffix") ?: "") },
            minSdk = minSdk,
            targetSdk = targetSdk,
            buildConfig = buildConfig,
            buildConfigFields = buildConfigFields(defaultConfig) + buildConfigFields(buildType),
            moduleDeps = deps.modules,
            mavenDeps = deps.coords["implementation"].orEmpty(),
            compileOnlyDeps = deps.coords["compileOnly"].orEmpty(),
            annotationProcessors = deps.coords["annotationProcessor"].orEmpty(),
            kaptProcessors = deps.coords["kapt"].orEmpty(),
            kspProcessors = deps.coords["ksp"].orEmpty(),
            sourceDirs = listOf(File(main, "java"), File(main, "kotlin")).filter { it.isDirectory },
            resDir = File(main, "res").takeIf { it.isDirectory },
            assetsDir = File(main, "assets").takeIf { it.isDirectory },
            jniLibsDir = File(main, "jniLibs").takeIf { it.isDirectory },
            manifest = File(main, "AndroidManifest.xml").takeIf { it.isFile }
                ?: File(moduleDir, "AndroidManifest.xml").takeIf { it.isFile },
            proguardFiles = proguardFiles,
            platformDeps = deps.platforms,
            localLibs = deps.localLibs,
            coreLibraryDesugaring = desugaring,
            desugarLibs = deps.coords["coreLibraryDesugaring"].orEmpty(),
            viewBinding = viewBinding,
            minifyEnabled = minify,
            pluginIds = pluginIds(text, catalog),
            composeFeature = composeFeature,
            kspArgs = block(text, "ksp").orEmpty().let { ksp ->
                Regex("""\barg\s*\(\s*["']([^"']+)["']\s*,\s*["']([^"']*)["']""").findAll(ksp)
                    .associate { it.groupValues[1] to it.groupValues[2].replace("\$projectDir", moduleDir.absolutePath).replace("\${projectDir}", moduleDir.absolutePath) }
            },
        )
    }

    /** Plugin ids from `plugins {}` (`id(...)`, `kotlin(...)`, `alias(libs.plugins.x)`) and `apply plugin:`. */
    private fun pluginIds(text: String, catalog: VersionCatalogParser.Catalog): Set<String> {
        val ids = mutableSetOf<String>()
        val plugins = block(text, "plugins").orEmpty()
        Regex("""\bid\s*\(?\s*["']([^"']+)["']""").findAll(plugins).forEach { ids += it.groupValues[1] }
        Regex("""\bkotlin\s*\(\s*["']([^"']+)["']""").findAll(plugins).forEach { ids += "org.jetbrains.kotlin." + it.groupValues[1] }
        Regex("""\balias\s*\(\s*libs\.plugins\.([\w.]+)""").findAll(plugins).forEach { m ->
            catalog.plugin(m.groupValues[1])?.let { ids += it }
        }
        Regex("""\bapply\s*\(?\s*plugin\s*[:=]\s*["']([^"']+)["']""").findAll(text).forEach { ids += it.groupValues[1] }
        return ids
    }

    /**
     * Repositories the project adds besides Maven Central and Google Maven (JitPack, a company
     * repo…), from settings.gradle(.kts) and the root build file: `maven("url")`,
     * `maven { url = uri("url") }`, `maven { url "url" }`, `maven { setUrl("url") }`.
     */
    fun repositories(projectRoot: File): List<String> {
        val files = listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle").map { File(projectRoot, it) }
        return files.filter { it.isFile }.flatMap { f ->
            val text = stripComments(f.readText())
            Regex("""\bmaven\s*(?:\(\s*(?:url\s*=\s*)?(?:uri\s*\(\s*)?["'](https?://[^"']+)["']|\{([^}]*)\})""").findAll(text).mapNotNull { m ->
                m.groupValues[1].ifEmpty { null }
                    ?: Regex("""["'](https?://[^"']+)["']""").find(m.groupValues[2])?.groupValues?.get(1)
            }.toList()
        }.map { it.trimEnd('/') }.distinct()
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

    // ── Dependencies ───────────────────────────────────────────────────────

    private class Dependencies(
        val modules: List<String>,
        val coords: Map<String, List<String>>,
        val platforms: List<String>,
        val localLibs: List<File>,
    )

    /**
     * Groups dependency declarations by kind: "implementation" (also api, runtimeOnly and this
     * variant's debugImplementation / releaseImplementation), "compileOnly", "annotationProcessor",
     * "kapt" and "ksp". Test configurations are ignored.
     */
    private fun parseDependencies(text: String, catalog: VersionCatalogParser.Catalog, variant: BuildVariant, moduleDir: File): Dependencies {
        val variantPrefix = if (variant == BuildVariant.RELEASE) "release" else "debug"
        val modules = mutableListOf<String>()
        val coords = mutableMapOf<String, MutableList<String>>()
        val platforms = mutableListOf<String>()
        val localLibs = mutableListOf<File>()
        fun coordOf(arg: String): List<String>? =
            Regex("""^["']([^"':]+:[^"':]+(?::[^"':@]+)?)(?::[^"'@]*)?(?:@\w+)?["']""").find(arg)?.groupValues?.get(1)?.let(::listOf)
                ?: Regex("""^libs\.([\w.]+)""").find(arg)?.groupValues?.get(1)?.let { catalog.resolve(it.removeSuffix(".get")) }
                ?: mapNotation(arg)?.let(::listOf)
        val statement = Regex("""(?m)^\s*([a-zA-Z]+)\s*(\(?)\s*(.+)$""")
        for (m in statement.findAll(block(text, "dependencies") ?: text)) {
            val config = m.groupValues[1]
            val kind = when (config) {
                "implementation", "api", "runtimeOnly",
                "${variantPrefix}Implementation", "${variantPrefix}Api", "${variantPrefix}RuntimeOnly" -> "implementation"
                "compileOnly", "${variantPrefix}CompileOnly" -> "compileOnly"
                "annotationProcessor", "kapt", "ksp", "coreLibraryDesugaring" -> config
                else -> continue
            }
            val arg = m.groupValues[3].trim()
            val module = Regex("""^project\s*\(\s*(?:path\s*[:=]\s*)?["']([^"']+)["']""").find(arg)?.groupValues?.get(1)
                ?: Regex("""^projects\.([\w.]+)""").find(arg)?.groupValues?.get(1)?.let { ":" + it.replace('.', ':') }
            if (module != null) {
                modules += if (module.startsWith(":")) module else ":$module"
                continue
            }
            val platform = Regex("""^(?:platform|enforcedPlatform)\s*\(\s*(.+)""").find(arg)
            if (platform != null) {
                if (kind == "implementation") coordOf(platform.groupValues[1])?.let { platforms += it }
                continue
            }
            if (Regex("""^(?:files|fileTree)\s*\(""").containsMatchIn(arg)) {
                if (kind == "implementation") localLibs += localFiles(arg, moduleDir)
                continue
            }
            if (Regex("""^kotlin\s*\(""").containsMatchIn(arg)) continue
            val found = coordOf(arg) ?: continue
            coords.getOrPut(kind) { mutableListOf() } += found
        }
        return Dependencies(modules.distinct(), coords.mapValues { it.value.distinct() }, platforms.distinct(), localLibs.distinct())
    }

    /**
     * Jars and AARs named by `files("libs/a.jar")`, or under the folder of
     * `fileTree("libs")` / `fileTree(dir: 'libs', include: ['*.jar'])` /
     * `fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar")))`.
     */
    private fun localFiles(arg: String, moduleDir: File): List<File> {
        val quoted = Regex("""["']([^"']+)["']""").findAll(arg).map { it.groupValues[1] }.toList()
        if (arg.trimStart().startsWith("files")) {
            return quoted.map { File(moduleDir, it) }.filter { it.isFile && it.extension in setOf("jar", "aar") }
        }
        val dir = Regex("""\bdir["']?\s*(?:to|:|=)\s*["']([^"']+)["']""").find(arg)?.groupValues?.get(1)
            ?: quoted.firstOrNull { !it.contains('*') && it != "dir" && it != "include" } ?: return emptyList()
        val extensions = quoted.filter { it.contains('*') }.map { it.substringAfterLast('.') }.toSet().ifEmpty { setOf("jar", "aar") }
        return File(moduleDir, dir).walkTopDown().filter { it.isFile && it.extension in extensions }.sortedBy { it.path }.toList()
    }

    /** `group: 'g', name: 'a', version: 'v'` (Groovy) or `group = "g", name = "a", version = "v"` (Kotlin). */
    private fun mapNotation(arg: String): String? {
        fun part(key: String) = Regex("""\b$key\s*[:=]\s*["']([^"']+)["']""").find(arg)?.groupValues?.get(1)
        val group = part("group") ?: return null
        val name = part("name") ?: return null
        val version = part("version") ?: return null
        return "$group:$name:$version"
    }

    // ── android {} values ──────────────────────────────────────────────────

    /** The body of `release {}` / `getByName("release") {}` (or debug) inside buildTypes. */
    private fun buildTypeBlock(buildTypes: String, variant: BuildVariant): String {
        val name = if (variant == BuildVariant.RELEASE) "release" else "debug"
        return block(buildTypes, """(?:(?:getByName|named|maybeCreate|create)\s*\(\s*["']$name["']\s*\)|$name)""", isRegex = true).orEmpty()
    }

    /** `key = "v"`, `key "v"`, `key 'v'` or `key("v")`. */
    private fun string(body: String, key: String): String? =
        Regex("""\b$key\s*(?:=\s*|\(\s*|\s+)["']([^"']*)["']""").find(body)?.groupValues?.get(1)

    /** `key = 24`, `key 24`, `key(24)`, or a catalog version such as `libs.versions.minSdk.get().toInt()`. */
    private fun int(body: String, catalog: VersionCatalogParser.Catalog, vararg keys: String): Int? {
        for (key in keys) {
            Regex("""\b$key\s*(?:=\s*|\(\s*|\s+)(\d+)\b""").find(body)?.let { return it.groupValues[1].toInt() }
            Regex("""\b$key\s*(?:=\s*|\(\s*|\s+)libs\.versions\.([\w.]+?)(?:\.get\(\))?(?:\.toInt\(\))?\b""").find(body)
                ?.let { m -> catalog.version(m.groupValues[1])?.toIntOrNull()?.let { return it } }
        }
        return null
    }

    /** Quoted arguments of `name(...)` / `name ...`, e.g. proguardFiles(getDefaultProguardFile(...), "proguard-rules.pro"). */
    private fun quotedArgs(body: String, name: String): List<String> =
        Regex("""\b$name\b\s*\(?([^\n]*)""").findAll(body)
            .flatMap { m -> Regex("""["']([^"']+)["']""").findAll(m.groupValues[1]).map { it.groupValues[1] } }
            .filter { !it.startsWith("proguard-android") }
            .toList()

    private fun buildConfigFields(body: String): List<BuildConfigField> =
        Regex("""\bbuildConfigField\s*\(?\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')\s*,\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')\s*,\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')""")
            .findAll(body)
            .map { m -> BuildConfigField(unquote(m.groupValues[1]), unquote(m.groupValues[2]), unquote(m.groupValues[3])) }
            .toList()

    /** A Kotlin or Groovy string literal's value: `"\"hola\""` and `'"hola"'` are both `"hola"`. */
    private fun unquote(literal: String): String =
        literal.substring(1, literal.length - 1).replace(Regex("""\\(.)""")) { it.groupValues[1] }

    private fun manifestPackage(moduleDir: File): String? =
        listOf(File(moduleDir, "src/main/AndroidManifest.xml"), File(moduleDir, "AndroidManifest.xml"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.let { Regex("""<manifest[^>]*\bpackage\s*=\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }

    // ── Text helpers ───────────────────────────────────────────────────────

    /**
     * Simple string variables a build file can use in coordinates: `val x = "1.0"` (Kotlin),
     * `def x = '1.0'` (Groovy), and `x = '1.0'` inside the root build file's `ext {}`.
     */
    private fun stringVariables(projectRoot: File, gradleFile: File): Map<String, String> {
        val vars = mutableMapOf<String, String>()
        val rootFile = listOf(File(projectRoot, "build.gradle.kts"), File(projectRoot, "build.gradle")).firstOrNull { it.exists() }
        for (file in listOfNotNull(rootFile, gradleFile)) {
            val text = stripComments(file.readText())
            Regex("""\b(?:val|var|def)\s+(\w+)\s*=\s*["']([^"'$]*)["']""").findAll(text).forEach { vars[it.groupValues[1]] = it.groupValues[2] }
            block(text, "ext")?.let { ext ->
                Regex("""(?m)^\s*(\w+)\s*=\s*["']([^"'$]*)["']""").findAll(ext).forEach { vars[it.groupValues[1]] = it.groupValues[2] }
            }
        }
        return vars
    }

    private fun substitute(text: String, vars: Map<String, String>): String =
        if (vars.isEmpty()) text
        else Regex("""\$\{?(\w+)\}?""").replace(text) { m -> vars[m.groupValues[1]] ?: m.value }

    /**
     * The text inside the first `header { ... }` block, matching braces and skipping strings.
     * [header] is a plain word unless [isRegex].
     */
    internal fun block(text: String, header: String, isRegex: Boolean = false): String? {
        val pattern = if (isRegex) header else """\b${Regex.escape(header)}"""
        val open = Regex("""$pattern\s*\{""").find(text) ?: return null
        var depth = 0
        var i = open.range.last
        var quote: Char? = null
        val start = i + 1
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i) }
            }
            i++
        }
        return text.substring(start)
    }

    /** Removes `//` and `/* */` comments, leaving strings (and the `//` in URLs inside them) alone. */
    internal fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                quote != null -> {
                    out.append(c)
                    if (c == '\\' && next != null) { out.append(next); i++ }
                    else if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> { quote = c; out.append(c) }
                c == '/' && next == '/' -> { while (i < text.length && text[i] != '\n') i++; continue }
                c == '/' && next == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
