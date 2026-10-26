package com.bori.core.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Scans JARs (AndroidX bundle, user-lib bundle) for public class names and
 * makes them available for completion and import quick-fix.
 *
 * Thread-safe: [rebuild] replaces the index atomically; readers see either the
 * old or new snapshot but never a partially updated one.
 */
object ClasspathIndex {

    @Volatile
    private var index: Map<String, List<String>> = emptyMap()

    /** Returns all FQNs whose simple name matches [simpleName] exactly. */
    fun lookup(simpleName: String): List<String> = index[simpleName] ?: emptyList()

    /**
     * Returns simple class names that start with [prefix] (case-insensitive),
     * limited to [limit] results. Used by the completion provider.
     */
    fun filterByPrefix(prefix: String, limit: Int = 8): List<String> =
        index.keys.asSequence()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .take(limit)
            .toList()

    /**
     * Scans each JAR in [jars] for public top-level class names and rebuilds
     * the index. Safe to call from any coroutine — switches to IO internally.
     * Inner/anonymous classes (those containing `$`) are excluded.
     */
    suspend fun rebuild(jars: List<File>) {
        val newIndex = withContext(Dispatchers.IO) {
            val map = mutableMapOf<String, MutableList<String>>()
            for (jar in jars) {
                if (!jar.exists() || !jar.canRead()) continue
                runCatching {
                    ZipFile(jar).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            if (entry.isDirectory || !entry.name.endsWith(".class")) return@forEach
                            if (entry.name.contains('$')) return@forEach
                            val fqn = entry.name.removeSuffix(".class").replace('/', '.')
                            val simple = fqn.substringAfterLast('.')
                            if (simple.isEmpty() || !simple[0].isUpperCase()) return@forEach
                            map.getOrPut(simple) { mutableListOf() }.add(fqn)
                        }
                    }
                }
            }
            map.mapValues { it.value.toList() }
        }
        index = newIndex
    }
}
