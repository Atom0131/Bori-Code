package com.bori.core.build.steps

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ResolvedArtifact(val file: File, val isAar: Boolean)

/**
 * Downloads AARs/JARs from Maven Central and Google Maven, caching locally in [cacheDir].
 * Resolves transitive compile-scope dependencies up to [MAX_DEPTH] levels deep.
 */
class MavenResolver(private val cacheDir: File) {

    private val repos = listOf(
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    )

    fun resolve(coordinates: List<String>, emit: (String) -> Unit): List<ResolvedArtifact> {
        val resolved = mutableListOf<ResolvedArtifact>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(coordinates.map { it to 0 })

        while (queue.isNotEmpty()) {
            val (coord, depth) = queue.removeFirst()
            if (coord in visited) continue
            visited += coord
            emit("Resolving $coord…")
            try {
                val artifact = downloadArtifact(coord, emit) ?: continue
                resolved += artifact
                if (depth < MAX_DEPTH) {
                    parseTransitiveDeps(coord, emit)
                        .filter { it !in visited }
                        .forEach { queue += it to depth + 1 }
                }
            } catch (e: Exception) {
                emit("⚠ Failed to resolve $coord: ${e.message}")
            }
        }
        return resolved
    }

    private fun downloadArtifact(coord: String, emit: (String) -> Unit): ResolvedArtifact? {
        val parts = coord.split(":")
        if (parts.size != 3) return null
        val (group, artifact, version) = parts
        val groupPath = group.replace('.', '/')

        val cacheAar = File(cacheDir, "$groupPath/$artifact/$version/$artifact-$version.aar")
        val cacheJar = File(cacheDir, "$groupPath/$artifact/$version/$artifact-$version.jar")
        if (cacheAar.exists()) { emit("  ✓ Cached: $artifact-$version.aar"); return ResolvedArtifact(cacheAar, true) }
        if (cacheJar.exists()) { emit("  ✓ Cached: $artifact-$version.jar"); return ResolvedArtifact(cacheJar, false) }

        for (repo in repos) {
            tryDownload("$repo/$groupPath/$artifact/$version/$artifact-$version.aar", cacheAar)
                ?.let { emit("  ↓ Downloaded: ${it.name} (${it.length() / 1024} KB)"); return ResolvedArtifact(it, true) }
            tryDownload("$repo/$groupPath/$artifact/$version/$artifact-$version.jar", cacheJar)
                ?.let { emit("  ↓ Downloaded: ${it.name} (${it.length() / 1024} KB)"); return ResolvedArtifact(it, false) }
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
        private const val MAX_DEPTH = 3
    }
}
