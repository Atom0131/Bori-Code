package com.bori.core.keyboard.theme

import android.content.Context
import com.bori.core.keyboard.ime.DiagLog
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where themes live: built-ins in assets/themes/<id>.json, the user's own in
 * filesDir/themes/<id>/theme.json (a folder, so a photo background can sit next to it).
 * Saving bumps Prefs.themeRevision so an open keyboard re-applies the theme at once.
 */
class ThemeStore(context: Context) {
    private val app = context.applicationContext
    private val userDir = File(app.filesDir, "themes")

    fun builtIns(): List<ThemeSpec> = builtInCache ?: synchronized(ThemeStore::class.java) {
        builtInCache ?: loadBuiltIns().also { builtInCache = it }
    }

    fun userThemes(): List<ThemeSpec> =
        userDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?.mapNotNull { dir -> read(File(dir, "theme.json")) } ?: emptyList()

    fun all(): List<ThemeSpec> = builtIns() + userThemes()

    /** The theme with [id]; the classic theme if it's missing or broken. */
    fun get(id: String): ThemeSpec =
        builtIns().firstOrNull { it.id == id }
            ?: read(File(File(userDir, safeId(id)), "theme.json"))
            ?: builtIns().firstOrNull { it.id == CLASSIC }
            ?: FALLBACK

    fun isBuiltIn(id: String) = builtIns().any { it.id == id }

    /** Saves a user theme (never over a built-in id). */
    fun save(spec: ThemeSpec) {
        require(!isBuiltIn(spec.id)) { "built-in theme ${spec.id}" }
        val dir = File(userDir, safeId(spec.id)).apply { mkdirs() }
        val tmp = File(dir, "theme.json.tmp")
        tmp.writeText(json.encodeToString(ThemeSpec.serializer(), spec))
        tmp.renameTo(File(dir, "theme.json"))
    }

    /** The folder a user theme's files (theme.json, photo) live in. */
    fun dirOf(id: String) = File(userDir, safeId(id))

    fun delete(id: String) {
        if (!isBuiltIn(id)) File(userDir, safeId(id)).deleteRecursively()
    }

    private fun loadBuiltIns(): List<ThemeSpec> {
        val names = runCatching { app.assets.list(ASSET_DIR)?.toList() }.getOrNull().orEmpty()
        val specs = names.filter { it.endsWith(".json") }.sorted().mapNotNull { name ->
            runCatching {
                app.assets.open("$ASSET_DIR/$name").bufferedReader().use { decode(it.readText()) }
            }.onFailure { DiagLog.event("theme asset $name failed: $it") }.getOrNull()
        }
        // Classic first, then the rest in file-name order (files are numbered: 05-neon.json).
        return specs.sortedBy { if (it.id == CLASSIC) 0 else 1 }.ifEmpty { listOf(FALLBACK) }
    }

    private fun read(f: File): ThemeSpec? {
        if (!f.isFile) return null
        return runCatching { decode(f.readText()) }
            .onFailure { DiagLog.event("theme file ${f.parentFile?.name} failed: $it") }.getOrNull()
    }

    companion object {
        const val CLASSIC = "classic"
        private const val ASSET_DIR = "themes"

        /** Used only if the asset is missing: the hard-coded light/dark palettes. */
        private val FALLBACK = ThemeSpec(id = CLASSIC, name = "Classic")

        @Volatile private var builtInCache: List<ThemeSpec>? = null

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            encodeDefaults = false
        }

        fun decode(text: String): ThemeSpec = json.decodeFromString(ThemeSpec.serializer(), text)

        /** File-safe id: letters, digits, - and _. */
        fun safeId(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "theme" }
    }
}
