package com.bori.core.keyboard.theme

import android.content.Context
import android.graphics.Typeface
import com.bori.core.keyboard.ime.DiagLog

/**
 * Key-label fonts bundled in assets/fonts (made by tools/build_fonts.py; OFL / Apache,
 * licences in assets/fonts/licenses and ATTRIBUTION.md). "system" is the phone's own font.
 * Characters a font lacks (arrows, emoji) fall back to the phone's fonts automatically.
 */
object Fonts {
    const val SYSTEM = "system"

    class Font(val id: String, val name: String, val crazy: Boolean)

    val ALL = listOf(
        Font(SYSTEM, "Phone's font", false),
        Font("inter", "Inter", false),
        Font("nunito", "Nunito", false),
        Font("atkinson", "Atkinson Hyperlegible", false),
        Font("jetbrains_mono", "JetBrains Mono", false),
        Font("comic_neue", "Comic Neue", false),
        Font("doto", "Doto (dot matrix)", true),
        Font("press_start", "Press Start (8-bit)", true),
        Font("bungee", "Bungee", true),
        Font("monoton", "Monoton (neon)", true),
        Font("orbitron", "Orbitron", true),
        Font("pacifico", "Pacifico", true),
        Font("permanent_marker", "Permanent Marker", true),
        Font("special_elite", "Special Elite (typewriter)", true),
        Font("creepster", "Creepster", true),
    )

    private val cache = HashMap<String, Typeface>()

    /** The font for [id], or null for the phone's font (also when the file can't load). */
    fun typeface(context: Context, id: String?): Typeface? {
        if (id == null || id == SYSTEM || ALL.none { it.id == id }) return null
        synchronized(cache) {
            cache[id]?.let { return it }
            return runCatching { Typeface.createFromAsset(context.applicationContext.assets, "fonts/$id.ttf") }
                .onFailure { DiagLog.event("font $id failed: $it") }
                .getOrNull()
                ?.also { cache[id] = it }
        }
    }
}
