package com.bori.core.build

import java.io.File

object VersionCatalogParser {

    /**
     * Parses `gradle/libs.versions.toml` and returns a map of alias → "group:artifact:version".
     * Handles both:
     *   - Inline: `retrofit = "com.squareup.retrofit2:retrofit:2.11.0"`
     *   - Rich:   `retrofit = { group = "...", name = "retrofit", version.ref = "retrofit" }`
     * Alias keys are normalised: `-` and `.` in TOML become `.` for `libs.X` lookup.
     */
    fun parse(tomlFile: File): Map<String, String> {
        if (!tomlFile.exists()) return emptyMap()
        val text = tomlFile.readText()

        val versions = parseVersions(text)
        val libraries = parseLibraries(text, versions)
        val bundles = parseBundles(text, libraries)

        return libraries + bundles
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
            if (coord.count { it == ':' } == 2) result[normalise(key)] = coord
        }
        return result
    }

    private fun parseRichLibrary(value: String, versions: Map<String, String>): String? {
        val group = Regex("""group\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1) ?: return null
        val name = Regex("""name\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1) ?: return null
        val inlineVersion = Regex("""version\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1)
        val versionRef = Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1)
        val version = inlineVersion ?: versions[versionRef?.let { normalise(it) }] ?: return null
        return "$group:$name:$version"
    }

    private fun parseBundles(text: String, libraries: Map<String, String>): Map<String, String> {
        // Bundles are alias lists — flatten each bundle alias into the individual library coords.
        // We treat them the same as individual libs: a bundle alias resolves to its first member.
        // This is unusual; mostly users reference individual lib aliases, not bundles.
        // For safety we just re-export each member alias from the bundle under the bundle name too.
        val section = extractSection(text, "bundles") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (line in section.lines()) {
            val (key, value) = parseKV(line) ?: continue
            // value is `["alias1", "alias2", ...]`
            Regex("""["']([^"']+)["']""").findAll(value).forEach { m ->
                val alias = normalise(m.groupValues[1])
                libraries[alias]?.let { result[normalise(key) + "." + alias] = it }
            }
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
        val key = trimmed.substring(0, eqIdx).trim()
        val value = trimmed.substring(eqIdx + 1).trim()
        return key to value
    }

    /** Normalise TOML alias: replace `-` with `.` and lowercase for consistent lookup. */
    private fun normalise(alias: String): String = alias.replace('-', '.').lowercase()

    /**
     * Given a `libs.someAlias` reference from a build file, look up the coordinate
     * in the parsed catalog map.  The alias key part after `libs.` is normalised.
     */
    fun resolve(libsAlias: String, catalog: Map<String, String>): String? {
        return catalog[normalise(libsAlias)]
    }
}
