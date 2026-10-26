package com.bori.core.build

import java.io.File

object VersionCatalogParser {

    /**
     * A parsed `gradle/libs.versions.toml`. Keys are normalised aliases (see [normalise]):
     * [libraries] maps alias → "group:artifact:version", [bundles] maps bundle alias → its
     * coordinates, and [versions] maps version alias → version.
     */
    data class Catalog(
        val libraries: Map<String, String>,
        val bundles: Map<String, List<String>>,
        val versions: Map<String, String>,
        /** Plugin alias → plugin id, from [plugins]. */
        val plugins: Map<String, String> = emptyMap(),
    ) {
        /** Coordinates for a `libs.X` reference (X without "libs."): one library, or every member of `bundles.X`. */
        fun resolve(ref: String): List<String> {
            val key = normalise(ref)
            if (key.startsWith("bundles.")) return bundles[key.removePrefix("bundles.")].orEmpty()
            return listOfNotNull(libraries[key])
        }

        /** The version for a `libs.versions.X` reference (X without "libs.versions."). */
        fun version(ref: String): String? = versions[normalise(ref)]

        /** The plugin id for a `libs.plugins.X` reference (X without "libs.plugins."). */
        fun plugin(ref: String): String? = plugins[normalise(ref)]

        companion object {
            val EMPTY = Catalog(emptyMap(), emptyMap(), emptyMap())
        }
    }

    fun parseCatalog(tomlFile: File): Catalog {
        if (!tomlFile.exists()) return Catalog.EMPTY
        val text = tomlFile.readText()
        val versions = parseVersions(text)
        val libraries = parseLibraries(text, versions)
        return Catalog(libraries, parseBundles(text, libraries), versions, parsePlugins(text))
    }

    /**
     * Parses `gradle/libs.versions.toml` and returns a map of alias → "group:artifact:version".
     * Handles both:
     *   - Inline: `retrofit = "com.squareup.retrofit2:retrofit:2.11.0"`
     *   - Rich:   `retrofit = { group = "...", name = "retrofit", version.ref = "retrofit" }`
     *             or `{ module = "group:name", version = "..." }`
     * Bundle members are also listed as "bundlealias.memberalias".
     */
    fun parse(tomlFile: File): Map<String, String> {
        val catalog = parseCatalog(tomlFile)
        val bundleMembers = catalog.bundles.flatMap { (bundle, coords) ->
            coords.mapNotNull { coord ->
                catalog.libraries.entries.firstOrNull { it.value == coord }?.let { "$bundle.${it.key}" to coord }
            }
        }
        return catalog.libraries + bundleMembers
    }

    // ── Section extraction ─────────────────────────────────────────────────

    private fun parseVersions(text: String): Map<String, String> {
        val section = extractSection(text, "versions") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (line in section.lines()) {
            val (key, value) = parseKV(line) ?: continue
            // value can be `"1.0"` or `{ require = "1.0" }` — extract the string literal
            val version = Regex("""["']([^"']+)["']""").find(value)?.groupValues?.get(1) ?: continue
            result[normalise(key)] = version
        }
        return result
    }

    private fun parseLibraries(text: String, versions: Map<String, String>): Map<String, String> {
        val section = extractSection(text, "libraries") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (line in section.lines()) {
            val (key, value) = parseKV(line) ?: continue
            val coord = if (value.startsWith("{")) {
                parseRichLibrary(value, versions)
            } else {
                Regex("""["']([^"']+)["']""").find(value)?.groupValues?.get(1)
            } ?: continue
            // Two parts ("group:name") when a platform(...) BOM supplies the version.
            if (coord.count { it == ':' } in 1..2) result[normalise(key)] = coord
        }
        return result
    }

    private fun parseRichLibrary(value: String, versions: Map<String, String>): String? {
        val module = Regex("""module\s*=\s*["']([^"':]+):([^"']+)["']""").find(value)
        val group = module?.groupValues?.get(1)
            ?: Regex("""group\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1) ?: return null
        val name = module?.groupValues?.get(2)
            ?: Regex("""name\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1) ?: return null
        val inlineVersion = Regex("""version\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1)
            ?: Regex("""(?:strictly|require|prefer)\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1)
        val versionRef = Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1)
        val version = inlineVersion ?: versions[versionRef?.let { normalise(it) }] ?: return "$group:$name"
        return "$group:$name:$version"
    }

    private fun parsePlugins(text: String): Map<String, String> {
        val section = extractSection(text, "plugins") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (line in section.lines()) {
            val (key, value) = parseKV(line) ?: continue
            val id = Regex("""id\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1)
                ?: Regex("""^["']([^"':]+)""").find(value)?.groupValues?.get(1)
                ?: continue
            result[normalise(key)] = id
        }
        return result
    }

    private fun parseBundles(text: String, libraries: Map<String, String>): Map<String, List<String>> {
        val section = extractSection(text, "bundles") ?: return emptyMap()
        val result = mutableMapOf<String, List<String>>()
        for (line in section.lines()) {
            val (key, value) = parseKV(line) ?: continue
            // value is `["alias1", "alias2", ...]`
            result[normalise(key)] = Regex("""["']([^"']+)["']""").findAll(value)
                .mapNotNull { libraries[normalise(it.groupValues[1])] }
                .toList()
        }
        return result
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun extractSection(text: String, name: String): String? {
        val headerRe = Regex("""^\[${Regex.escape(name)}]""", RegexOption.MULTILINE)
        val start = headerRe.find(text)?.range?.last?.plus(1) ?: return null
        val nextHeader = Regex("""^\[""", RegexOption.MULTILINE).find(text, start)
        val end = nextHeader?.range?.first ?: text.length
        return text.substring(start, end)
    }

    /** Parses `key = value` (ignoring comment lines). Returns null for blank / comment lines. */
    private fun parseKV(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return null
        val eqIdx = trimmed.indexOf('=').takeIf { it > 0 } ?: return null
        val key = trimmed.substring(0, eqIdx).trim().trim('"')
        val value = trimmed.substring(eqIdx + 1).trim()
        return key to value
    }

    /**
     * Normalise an alias the way Gradle's generated accessors do: `-`, `_` and `.` separate
     * words (`androidx-core` and `androidx_core` are both `libs.androidx.core`), case-insensitive.
     */
    private fun normalise(alias: String): String = alias.replace('-', '.').replace('_', '.').lowercase()

    /**
     * Given a `libs.someAlias` reference from a build file, look up the coordinate
     * in the parsed catalog map.  The alias key part after `libs.` is normalised.
     */
    fun resolve(libsAlias: String, catalog: Map<String, String>): String? {
        return catalog[normalise(libsAlias)]
    }
}
