package com.bori.core.build.steps

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A downloaded AAR or JAR; [coord] is "group:artifact:version". */
data class ResolvedArtifact(val file: File, val isAar: Boolean, val coord: String = "") {
    val groupArtifact: String get() = coord.substringBeforeLast(':')
}

/**
 * Downloads AARs/JARs from Maven Central and Google Maven, caching locally in [cacheDir], with
 * their transitive compile and runtime dependencies. When the graph asks for several versions
 * of one library, the highest wins (as in Gradle), and only that version's dependencies count.
 */
class MavenResolver(private val cacheDir: File) {

    private val repos = listOf(
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    )

    fun resolve(coordinates: List<String>, emit: (String) -> Unit): List<ResolvedArtifact> {
        // Pick versions first, walking the graph again whenever a higher version turns up.
        val selected = linkedMapOf<String, String>()
        coordinates.forEach { select(selected, it) }
        var changed = true
        var rounds = 0
        while (changed && rounds++ < 10) {
            changed = false
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque(selected.map { "${it.key}:${it.value}" })
            while (queue.isNotEmpty()) {
                val coord = queue.removeFirst()
                if (!visited.add(coord)) continue
                for (dep in parseTransitiveDeps(coord, emit)) {
                    if (select(selected, dep)) changed = true
                    val ga = dep.substringBeforeLast(':')
                    queue += "$ga:${selected.getValue(ga)}"
                }
            }
        }

        val resolved = mutableListOf<ResolvedArtifact>()
        for ((ga, version) in selected) {
            val coord = "$ga:$version"
            emit("Resolving $coord…")
            try {
                downloadArtifact(coord, emit)?.let { resolved += it }
            } catch (e: Exception) {
                emit("⚠ Failed to resolve $coord: ${e.message}")
            }
        }
        return resolved
    }

    /** Records [coord]'s version for its library if it's higher than the one chosen so far; true if it changed. */
    private fun select(selected: MutableMap<String, String>, coord: String): Boolean {
        val parts = coord.split(":")
        if (parts.size != 3) return false
        val ga = "${parts[0]}:${parts[1]}"
        val current = selected[ga]
        if (current != null && compareVersions(current, parts[2]) >= 0) return false
        selected[ga] = parts[2]
        return true
    }

    private fun downloadArtifact(coord: String, emit: (String) -> Unit): ResolvedArtifact? {
        val parts = coord.split(":")
        if (parts.size != 3) return null
        val (group, artifact, version) = parts
        val groupPath = group.replace('.', '/')

        val cacheAar = File(cacheDir, "$groupPath/$artifact/$version/$artifact-$version.aar")
        val cacheJar = File(cacheDir, "$groupPath/$artifact/$version/$artifact-$version.jar")
        if (cacheAar.exists()) { emit("  ✓ Cached: $artifact-$version.aar"); return ResolvedArtifact(cacheAar, true, coord) }
        if (cacheJar.exists()) { emit("  ✓ Cached: $artifact-$version.jar"); return ResolvedArtifact(cacheJar, false, coord) }

        for (repo in repos) {
            tryDownload("$repo/$groupPath/$artifact/$version/$artifact-$version.aar", cacheAar)
                ?.let { emit("  ↓ Downloaded: ${it.name} (${it.length() / 1024} KB)"); return ResolvedArtifact(it, true, coord) }
            tryDownload("$repo/$groupPath/$artifact/$version/$artifact-$version.jar", cacheJar)
                ?.let { emit("  ↓ Downloaded: ${it.name} (${it.length() / 1024} KB)"); return ResolvedArtifact(it, false, coord) }
        }
        emit("  ✗ Not found in any repository: $coord")
        return null
    }

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
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            dest
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }

    private fun parseTransitiveDeps(coord: String, emit: (String) -> Unit): List<String> {
        val parts = coord.split(":")
        if (parts.size != 3) return emptyList()
        val (group, artifact, version) = parts
        val groupPath = group.replace('.', '/')
        val pomDest = File(cacheDir, "$groupPath/$artifact/$version/$artifact-$version.pom")

        if (!pomDest.exists()) {
            for (repo in repos) {
                if (tryDownload("$repo/$groupPath/$artifact/$version/$artifact-$version.pom", pomDest) != null) break
            }
        }
        if (!pomDest.exists()) return emptyList()

        return try {
            parsePomDependencies(pomDest.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePomDependencies(pomXml: String): List<String> {
        // Strip comments and <dependencyManagement> (those are version constraints, not actual deps)
        val cleaned = pomXml
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<dependencyManagement>.*?</dependencyManagement>", RegexOption.DOT_MATCHES_ALL), "")

        val deps = mutableListOf<String>()
        val depBlock = Regex("<dependency>(.*?)</dependency>", RegexOption.DOT_MATCHES_ALL)
        val groupOf = Regex("<groupId>\\s*([^<]+)\\s*</groupId>")
        val artifactOf = Regex("<artifactId>\\s*([^<]+)\\s*</artifactId>")
        val versionOf = Regex("<version>\\s*([^<]+)\\s*</version>")
        val scopeOf = Regex("<scope>\\s*([^<]+)\\s*</scope>")
        val optionalOf = Regex("<optional>\\s*true\\s*</optional>")

        for (m in depBlock.findAll(cleaned)) {
            val block = m.groupValues[1]
            if (optionalOf.containsMatchIn(block)) continue
            val scope = scopeOf.find(block)?.groupValues?.get(1)?.trim() ?: "compile"
            if (scope != "compile" && scope != "runtime") continue

            val group = groupOf.find(block)?.groupValues?.get(1)?.trim() ?: continue
            val artifact = artifactOf.find(block)?.groupValues?.get(1)?.trim() ?: continue
            val version = versionOf.find(block)?.groupValues?.get(1)?.trim() ?: continue
            if (version.contains('$')) continue // skip property-interpolated versions

            deps += "$group:$artifact:$version"
        }
        return deps
    }

    companion object {
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
