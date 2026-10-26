package com.bori.core.build.steps

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A downloaded AAR or JAR; [coord] is "group:artifact:version". */
data class ResolvedArtifact(val file: File, val isAar: Boolean, val coord: String = "") {
    val groupArtifact: String get() = coord.substringBeforeLast(':')
}

/**
 * Downloads AARs/JARs from Maven repositories, caching them in [cacheDir], with their transitive
 * compile and runtime dependencies, the way Gradle reads POMs:
 * - parent POMs, `${property}` versions and `<dependencyManagement>` (including imported BOMs);
 * - `platform(...)` BOMs from the build file supply versions for coordinates without one;
 * - `<exclusions>` drop a library below the dependency that excludes it;
 * - when the graph asks for several versions of one library, the highest wins, and only that
 *   version's dependencies count.
 *
 * [repos] are tried in order; Maven Central and Google Maven are always there.
 */
class MavenResolver(private val cacheDir: File, extraRepos: List<String> = emptyList()) {

    private val repos = (DEFAULT_REPOS + extraRepos.map { it.trimEnd('/') }).distinct()

    /** The repository that had each group:artifact, so later files skip the others. */
    private val repoOf = mutableMapOf<String, String>()
    private val poms = mutableMapOf<String, Pom?>()

    private class Dep(
        val group: String,
        val artifact: String,
        val version: String?,
        val scope: String,
        val optional: Boolean,
        val type: String,
        val exclusions: Set<String>,
    ) {
        val ga get() = "$group:$artifact"
    }

    private class Pom(
        val properties: Map<String, String>,
        /** group:artifact → version from <dependencyManagement> (BOM imports included). */
        val managed: Map<String, String>,
        val dependencies: List<Dep>,
        val packaging: String,
    )

    /**
     * Resolves [coordinates] ("g:a:v", or "g:a" when a [platforms] BOM gives the version) and
     * everything they depend on. Returns the artifacts in a stable order.
     */
    fun resolve(
        coordinates: List<String>,
        emit: (String) -> Unit,
        platforms: List<String> = emptyList(),
    ): List<ResolvedArtifact> {
        val constraints = mutableMapOf<String, String>()
        for (bom in platforms) {
            val (g, a, v) = bom.split(":").takeIf { it.size >= 3 } ?: continue
            val pom = loadPom(g, a, v, emit)
            if (pom == null) emit("⚠ BOM not found: $bom") else constraints.putAll(pom.managed)
        }

        val roots = coordinates.mapNotNull { coord ->
            val parts = coord.split(":")
            when {
                parts.size >= 3 -> "${parts[0]}:${parts[1]}:${cleanVersion(parts[2])}"
                parts.size == 2 -> constraints[coord]?.let { "$coord:$it" }
                    ?: run { emit("⚠ No version for $coord (add one, or a platform(...) BOM that has it)"); null }
                else -> null
            }
        }

        // Pick versions first, walking the graph again whenever a higher version turns up.
        val selected = linkedMapOf<String, String>()
        fun select(ga: String, version: String): Boolean {
            val current = selected[ga]
            if (current != null && compareVersions(current, version) >= 0) return false
            selected[ga] = version
            return true
        }
        roots.forEach { select(it.substringBeforeLast(':'), it.substringAfterLast(':')) }
        // Versions a library's POM manages for its own group: AndroidX, Compose and kotlinx keep
        // a family on one version this way (Gradle reads the same constraints from .module files).
        val alignment = mutableMapOf<String, String>()
        // A BOM's or a family's version takes part like a dependency's would, once the library is in the graph.
        fun applyConstraint(ga: String): Boolean {
            var changedHere = constraints[ga]?.let { select(ga, it) } ?: false
            alignment[ga]?.let { if (select(ga, it)) changedHere = true }
            return changedHere
        }
        selected.keys.toList().forEach { applyConstraint(it) }

        val included = mutableSetOf<String>()
        var changed = true
        var rounds = 0
        while (changed && rounds++ < 20) {
            changed = false
            included.clear()
            val visited = mutableSetOf<String>()
            // Each queue entry carries the exclusions inherited along its path.
            val queue = ArrayDeque(roots.map { it.substringBeforeLast(':') to emptySet<String>() })
            while (queue.isNotEmpty()) {
                val (ga, excluded) = queue.removeFirst()
                val version = selected[ga] ?: continue
                if (!visited.add("$ga:$version|${excluded.sorted().joinToString(",")}")) continue
                included += ga
                val (g, a) = ga.split(":")
                val pom = loadPom(g, a, version, emit) ?: continue
                for ((mga, mv) in pom.managed) {
                    if (mga.substringBefore(':') != g) continue
                    val current = alignment[mga]
                    if (current == null || compareVersions(mv, current) > 0) alignment[mga] = mv
                    if (mga in selected && select(mga, mv)) changed = true
                }
                for (dep in pom.dependencies) {
                    if (dep.optional || dep.scope !in RUNTIME_SCOPES || dep.type == "pom") continue
                    if (isExcluded(dep.ga, excluded)) continue
                    val depVersion = dep.version ?: pom.managed[dep.ga] ?: constraints[dep.ga] ?: selected[dep.ga]
                    if (depVersion == null) {
                        emit("⚠ $ga:$version needs ${dep.ga} but gives no version; skipped")
                        continue
                    }
                    if (select(dep.ga, depVersion)) changed = true
                    if (applyConstraint(dep.ga)) changed = true
                    queue += dep.ga to (excluded + dep.exclusions)
                }
            }
        }

        val resolved = mutableListOf<ResolvedArtifact>()
        for ((ga, version) in selected) {
            if (ga !in included) continue
            val coord = "$ga:$version"
            if (poms[coord]?.packaging == "pom") continue
            emit("Resolving $coord…")
            try {
                downloadArtifact(coord, emit)?.let { resolved += it }
            } catch (e: Exception) {
                emit("⚠ Failed to resolve $coord: ${e.message}")
            }
        }
        return resolved
    }

    private fun isExcluded(ga: String, exclusions: Set<String>): Boolean {
        if (exclusions.isEmpty()) return false
        val group = ga.substringBefore(':')
        return ga in exclusions || "$group:*" in exclusions || "*:*" in exclusions
    }

    private fun downloadArtifact(coord: String, emit: (String) -> Unit): ResolvedArtifact? {
        val (group, artifact, version) = coord.split(":")
        val base = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version"
        val cacheAar = File(cacheDir, "$base.aar")
        val cacheJar = File(cacheDir, "$base.jar")
        if (cacheAar.exists()) { emit("  ✓ Cached: ${cacheAar.name}"); return ResolvedArtifact(cacheAar, true, coord) }
        if (cacheJar.exists()) { emit("  ✓ Cached: ${cacheJar.name}"); return ResolvedArtifact(cacheJar, false, coord) }

        for (repo in reposFor("$group:$artifact")) {
            tryDownload("$repo/$base.aar", cacheAar)
                ?.let { emit("  ↓ Downloaded: ${it.name} (${it.length() / 1024} KB)"); return ResolvedArtifact(it, true, coord) }
            tryDownload("$repo/$base.jar", cacheJar)
                ?.let { emit("  ↓ Downloaded: ${it.name} (${it.length() / 1024} KB)"); return ResolvedArtifact(it, false, coord) }
        }
        // A POM-only module (a Kotlin Multiplatform root or a relocation) has nothing to package.
        if (File(cacheDir, "$base.pom").exists()) return null
        emit("  ✗ Not found in any repository: $coord")
        return null
    }

    private fun reposFor(ga: String): List<String> =
        repoOf[ga]?.let { listOf(it) + (repos - it) } ?: repos

    private fun tryDownload(urlStr: String, dest: File): File? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.requestMethod = "GET"
            conn.connect()
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (!tmp.renameTo(dest)) { tmp.delete(); return null }
            dest
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }

    // ── POMs ───────────────────────────────────────────────────────────────

    /** The effective POM of g:a:v: its parents merged in and properties substituted. */
    private fun loadPom(group: String, artifact: String, version: String, emit: (String) -> Unit, depth: Int = 0): Pom? {
        val key = "$group:$artifact:$version"
        if (key in poms) return poms[key]
        if (depth > 12) return null
        poms[key] = null // guards against cycles while loading
        val text = pomText(group, artifact, version) ?: return null
        val xml = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        val parentBlock = tag(xml, "parent")
        val parent = parentBlock?.let { p ->
            val pg = tag(p, "groupId")?.trim()
            val pa = tag(p, "artifactId")?.trim()
            val pv = tag(p, "version")?.trim()
            if (pg != null && pa != null && pv != null) loadPom(pg, pa, pv, emit, depth + 1) else null
        }

        val withoutParent = parentBlock?.let { xml.replaceFirst(Regex("<parent>.*?</parent>", RegexOption.DOT_MATCHES_ALL), "") } ?: xml
        val props = linkedMapOf<String, String>()
        parent?.properties?.let { props.putAll(it) }
        tag(withoutParent, "properties")?.let { body ->
            Regex("<([\\w.\\-]+)>\\s*([^<]*?)\\s*</\\1>").findAll(body).forEach { props[it.groupValues[1]] = it.groupValues[2] }
        }
        val projectVersion = topLevel(withoutParent, "version") ?: parentBlock?.let { tag(it, "version")?.trim() } ?: version
        val projectGroup = topLevel(withoutParent, "groupId") ?: parentBlock?.let { tag(it, "groupId")?.trim() } ?: group
        props["project.version"] = projectVersion
        props["pom.version"] = projectVersion
        props["version"] = projectVersion
        props["project.groupId"] = projectGroup
        props["project.artifactId"] = artifact
        parentBlock?.let { tag(it, "version")?.trim() }?.let { props["project.parent.version"] = it; props["parent.version"] = it }
        fun interpolate(s: String): String {
            var out = s
            repeat(5) {
                val next = Regex("\\$\\{([^}]+)\\}").replace(out) { m -> props[m.groupValues[1]] ?: m.value }
                if (next == out) return out
                out = next
            }
            return out
        }

        val managed = linkedMapOf<String, String>()
        parent?.managed?.let { managed.putAll(it) }
        tag(withoutParent, "dependencyManagement")?.let { dm ->
            for (dep in parseDeps(dm, ::interpolate)) {
                if (dep.scope == "import" && dep.type == "pom") {
                    val v = dep.version?.let(::cleanVersion) ?: continue
                    loadPom(dep.group, dep.artifact, v, emit, depth + 1)?.managed?.forEach { (ga, mv) -> managed.putIfAbsent(ga, mv) }
                } else {
                    dep.version?.let { managed[dep.ga] = cleanVersion(it) }
                }
            }
        }

        val ownDeps = withoutParent
            .replace(Regex("<dependencyManagement>.*?</dependencyManagement>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<build>.*?</build>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<profiles>.*?</profiles>", RegexOption.DOT_MATCHES_ALL), "")
            .let { tag(it, "dependencies") }
            ?.let { parseDeps(it, ::interpolate) }
            .orEmpty()
        val deps = (parent?.dependencies.orEmpty() + ownDeps).map { d ->
            Dep(d.group, d.artifact, d.version?.let(::cleanVersion), d.scope, d.optional, d.type, d.exclusions)
        }
        val packaging = topLevel(withoutParent, "packaging") ?: "jar"
        return Pom(props, managed, deps, packaging).also { poms[key] = it }
    }

    private fun pomText(group: String, artifact: String, version: String): String? {
        val path = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom"
        val dest = File(cacheDir, path)
        if (!dest.exists()) {
            for (repo in reposFor("$group:$artifact")) {
                if (tryDownload("$repo/$path", dest) != null) { repoOf["$group:$artifact"] = repo; break }
            }
        }
        return runCatching { dest.takeIf { it.exists() }?.readText() }.getOrNull()
    }

    private fun parseDeps(body: String, interpolate: (String) -> String): List<Dep> =
        Regex("<dependency>(.*?)</dependency>", RegexOption.DOT_MATCHES_ALL).findAll(body).mapNotNull { m ->
            val block = m.groupValues[1]
            val exclusions = tag(block, "exclusions")?.let { ex ->
                Regex("<exclusion>(.*?)</exclusion>", RegexOption.DOT_MATCHES_ALL).findAll(ex).mapNotNull { e ->
                    val g = tag(e.groupValues[1], "groupId")?.trim() ?: return@mapNotNull null
                    val a = tag(e.groupValues[1], "artifactId")?.trim() ?: "*"
                    "$g:$a"
                }.toSet()
            }.orEmpty()
            val inner = block.replace(Regex("<exclusions>.*?</exclusions>", RegexOption.DOT_MATCHES_ALL), "")
            Dep(
                group = tag(inner, "groupId")?.trim()?.let(interpolate) ?: return@mapNotNull null,
                artifact = tag(inner, "artifactId")?.trim()?.let(interpolate) ?: return@mapNotNull null,
                version = tag(inner, "version")?.trim()?.let(interpolate)?.takeIf { !it.contains("\${") },
                scope = tag(inner, "scope")?.trim() ?: "compile",
                optional = tag(inner, "optional")?.trim() == "true",
                type = tag(inner, "type")?.trim() ?: "jar",
                exclusions = exclusions,
            )
        }.toList()

    private fun tag(xml: String, name: String): String? =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    /** A child of <project> itself, not of <dependencies> or <parent>. */
    private fun topLevel(xml: String, name: String): String? {
        val stripped = xml
            .replace(Regex("<dependencies>.*?</dependencies>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<dependencyManagement>.*?</dependencyManagement>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<build>.*?</build>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<profiles>.*?</profiles>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<properties>.*?</properties>", RegexOption.DOT_MATCHES_ALL), "")
        return tag(stripped, name)?.trim()?.takeIf { it.isNotEmpty() && !it.contains('<') }
    }

    companion object {
        val DEFAULT_REPOS = listOf(
            "https://repo1.maven.org/maven2",
            "https://maven.google.com",
        )

        private val RUNTIME_SCOPES = setOf("compile", "runtime")

        /** A Maven range keeps its lower bound ("[1.2,2.0)" → "1.2"); Gradle's dynamic "1.+" isn't supported. */
        fun cleanVersion(v: String): String {
            val t = v.trim()
            if (t.startsWith("[") || t.startsWith("(")) {
                val bounds = t.trim('[', ']', '(', ')').split(',')
                return bounds.firstOrNull { it.isNotBlank() }?.trim() ?: bounds.last().trim()
            }
            return t
        }

        /**
         * Compares Maven versions by their numeric parts, then qualifiers: 1.10 > 1.9, and a
         * release beats its pre-releases (1.0 > 1.0-rc01 > 1.0-beta01 > 1.0-alpha01).
         */
        fun compareVersions(a: String, b: String): Int {
            fun split(v: String): Pair<List<Int>, String> {
                val numeric = v.takeWhile { it.isDigit() || it == '.' }.trimEnd('.')
                val nums = numeric.split('.').mapNotNull { it.toIntOrNull() }
                return nums to v.substring(numeric.length).trimStart('-', '.').lowercase()
            }
            val (na, qa) = split(a)
            val (nb, qb) = split(b)
            for (i in 0 until maxOf(na.size, nb.size)) {
                val c = (na.getOrNull(i) ?: 0).compareTo(nb.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            fun rank(q: String) = when {
                q.isEmpty() || q == "final" || q == "release" -> 5
                q.startsWith("rc") || q.startsWith("cr") -> 4
                q.startsWith("beta") || q.startsWith("b") -> 3
                q.startsWith("alpha") || q.startsWith("a") -> 2
                else -> 1 // snapshots, dev builds
            }
            val c = rank(qa).compareTo(rank(qb))
            return if (c != 0) c else qa.compareTo(qb)
        }
    }
}
