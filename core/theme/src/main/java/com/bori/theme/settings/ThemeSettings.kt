package com.bori.theme.settings

import org.json.JSONArray
import org.json.JSONObject

// Hand-written for the v2 export. In the habit tracker these enums and constants live in
// `data/preferences/ThemePreferences.kt` next to its DataStore code; here they stand alone so any
// app can keep the settings however it likes. Names, values and defaults are the habit tracker's.

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Dark-mode surface contrast, through materialkolor's contrastLevel: 0.0 / 0.3 / 0.65. */
enum class DarkReadability { STANDARD, COMFORTABLE, HIGH }

/**
 * The visual language. [MATERIAL3] is materialkolor TonalSpot on SPEC_2021; [EXPRESSIVE] is the same
 * engine re-seeded for Material 3 Expressive; [CRYSTAL] is the frosted glass look: a fixed cool
 * palette, an ambient colour field behind everything, and translucent blurred panels.
 */
enum class ThemeStyle { MATERIAL3, EXPRESSIVE, CRYSTAL }

/** Crystal's panels: [SHEER] lets more of the field through, [TINTED] is denser and easier to read. */
enum class CrystalStyle { SHEER, TINTED }

/** What sits behind Crystal's panels: the colour field, or one flat room colour. */
enum class CrystalBackground { MESH, SOLID }

/**
 * Which field [CrystalBackground.MESH] draws. The ten differ only in hue and chroma (Aurora's tones,
 * positions and alphas are shared, see `CrystalColor.kt`). **Appended, never reordered**: the picker
 * lays them out five to a row in this order. [CUSTOM] is solved at runtime from the user's colour(s).
 */
enum class CrystalMesh {
    AURORA, EMBER, VERDANT, MIST, ROSE,
    TIDE, INDIGO, ORCHID, DUNE, GRAPHITE,
    CUSTOM
}

/** Index meaning "no colour, the cool neutral room". Distinct from 0, a real palette entry. */
const val CRYSTAL_BG_NEUTRAL = -1

/** Dead centre of the active Crystal band: the look before the intensity slider existed. */
const val DEFAULT_CRYSTAL_INTENSITY = 0.5f

/** A custom field starts at full chroma. */
const val DEFAULT_CRYSTAL_MESH_CUSTOM_CHROMA = 1.0f

/** The floor for a custom field's chroma, matching Graphite, the quietest shipped field. */
const val MIN_CRYSTAL_MESH_CUSTOM_CHROMA = 0.18f

/**
 * Every theme choice in one value. An app stores it however it likes ([toJson] / [fromJson] are
 * there for a single preference string) and hands it to `BoriThemedRoot`.
 *
 * The `with…` functions are the habit tracker's `ThemePreferences` setters, rule for rule: picking
 * your own accent, background, mesh or room stops Borikén supplying it (`borikenAccent` /
 * `borikenField` go false), and turning Borikén on restores both and records the unlock.
 *
 * Defaults are the habit tracker's, except [themeStyle], which each app chooses.
 */
data class BoriThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val darkReadability: DarkReadability = DarkReadability.STANDARD,
    val themeStyle: ThemeStyle = ThemeStyle.MATERIAL3,
    /** Index into [com.bori.theme.util.boriColorPalette]; meaningless while [accentIsDefault]. */
    val accentColorIndex: Int = 0,
    val accentColorArgb: Int? = null,
    /** Never picked: Crystal + Mesh then takes its accent from the field. */
    val accentIsDefault: Boolean = true,
    val crystalStyle: CrystalStyle = CrystalStyle.TINTED,
    val crystalIntensity: Float = DEFAULT_CRYSTAL_INTENSITY,
    val crystalBackground: CrystalBackground = CrystalBackground.MESH,
    val crystalMesh: CrystalMesh = CrystalMesh.AURORA,
    val crystalMeshCustomArgb: Int? = null,
    val crystalMeshCustomChroma: Float = DEFAULT_CRYSTAL_MESH_CUSTOM_CHROMA,
    /** 2–4 colours for a combo field, or empty for a one-colour custom field. */
    val crystalMeshCustomColors: List<Int> = emptyList(),
    val crystalRoomIndex: Int = CRYSTAL_BG_NEUTRAL,
    val crystalRoomArgb: Int? = null,
    val borikenUnlocked: Boolean = false,
    val borikenMode: Boolean = false,
    val borikenField: Boolean = true,
    val borikenAccent: Boolean = true,
) {
    fun withThemeMode(mode: ThemeMode) = copy(themeMode = mode)
    fun withAmoled(enabled: Boolean) = copy(amoled = enabled)
    fun withDarkReadability(value: DarkReadability) = copy(darkReadability = value)
    fun withThemeStyle(style: ThemeStyle) = copy(themeStyle = style)
    fun withCrystalStyle(style: CrystalStyle) = copy(crystalStyle = style)
    fun withCrystalIntensity(value: Float) = copy(crystalIntensity = value.coerceIn(0f, 1f))

    fun withAccent(index: Int, argb: Int? = null) =
        copy(accentColorIndex = index, accentColorArgb = argb, accentIsDefault = false, borikenAccent = false)

    fun withCrystalBackground(background: CrystalBackground) =
        copy(crystalBackground = background, borikenField = false)

    fun withCrystalMesh(mesh: CrystalMesh) = copy(crystalMesh = mesh, borikenField = false)

    /** One to four colours; the first is also the one-colour seed. */
    fun withCrystalMeshCustom(argbs: List<Int>, chromaScale: Float) = copy(
        borikenField = false,
        crystalMeshCustomArgb = argbs.firstOrNull() ?: crystalMeshCustomArgb,
        crystalMeshCustomColors = if (argbs.size >= 2) argbs.take(4) else emptyList(),
        crystalMeshCustomChroma = chromaScale.coerceIn(MIN_CRYSTAL_MESH_CUSTOM_CHROMA, 1f),
    )

    fun withCrystalRoom(index: Int, argb: Int? = null) =
        copy(crystalRoomIndex = index, crystalRoomArgb = argb, borikenField = false)

    fun withBorikenUnlocked() = copy(borikenUnlocked = true)

    /** Turning it on starts with the full look (the flag field and the celeste accent). */
    fun withBorikenMode(enabled: Boolean) = if (enabled) {
        copy(borikenMode = true, borikenUnlocked = true, borikenField = true, borikenAccent = true)
    } else {
        copy(borikenMode = false)
    }

    /** Back to Borikén's field: the Borikén swatch in Crystal Background. */
    fun withBorikenFieldSelected() = copy(crystalBackground = CrystalBackground.MESH, borikenField = true)

    /** materialkolor contrast level for [darkReadability]. */
    val darkContrastLevel: Double
        get() = when (darkReadability) {
            DarkReadability.STANDARD -> 0.0
            DarkReadability.COMFORTABLE -> 0.3
            DarkReadability.HIGH -> 0.65
        }

    fun toJson(): String = JSONObject()
        .put("themeMode", themeMode.name)
        .put("amoled", amoled)
        .put("darkReadability", darkReadability.name)
        .put("themeStyle", themeStyle.name)
        .put("accentColorIndex", accentColorIndex)
        .putOpt("accentColorArgb", accentColorArgb)
        .put("accentIsDefault", accentIsDefault)
        .put("crystalStyle", crystalStyle.name)
        .put("crystalIntensity", crystalIntensity.toDouble())
        .put("crystalBackground", crystalBackground.name)
        .put("crystalMesh", crystalMesh.name)
        .putOpt("crystalMeshCustomArgb", crystalMeshCustomArgb)
        .put("crystalMeshCustomChroma", crystalMeshCustomChroma.toDouble())
        .put("crystalMeshCustomColors", JSONArray(crystalMeshCustomColors))
        .put("crystalRoomIndex", crystalRoomIndex)
        .putOpt("crystalRoomArgb", crystalRoomArgb)
        .put("borikenUnlocked", borikenUnlocked)
        .put("borikenMode", borikenMode)
        .put("borikenField", borikenField)
        .put("borikenAccent", borikenAccent)
        .toString()

    companion object {
        /**
         * Reads [toJson]'s output. Anything missing or unknown falls back to [defaults], so a value
         * written by an older or newer build never throws (this feeds the root theme on every launch).
         */
        fun fromJson(json: String?, defaults: BoriThemeSettings = BoriThemeSettings()): BoriThemeSettings {
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return defaults
            fun int(k: String): Int? = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
            return BoriThemeSettings(
                themeMode = enumOr(o.optString("themeMode"), defaults.themeMode),
                amoled = o.optBoolean("amoled", defaults.amoled),
                darkReadability = enumOr(o.optString("darkReadability"), defaults.darkReadability),
                themeStyle = enumOr(o.optString("themeStyle"), defaults.themeStyle),
                accentColorIndex = o.optInt("accentColorIndex", defaults.accentColorIndex),
                accentColorArgb = int("accentColorArgb"),
                accentIsDefault = o.optBoolean("accentIsDefault", defaults.accentIsDefault),
                crystalStyle = enumOr(o.optString("crystalStyle"), defaults.crystalStyle),
                crystalIntensity = o.optDouble("crystalIntensity", defaults.crystalIntensity.toDouble())
                    .toFloat().coerceIn(0f, 1f),
                crystalBackground = enumOr(o.optString("crystalBackground"), defaults.crystalBackground),
                crystalMesh = enumOr(o.optString("crystalMesh"), defaults.crystalMesh),
                crystalMeshCustomArgb = int("crystalMeshCustomArgb"),
                crystalMeshCustomChroma = o.optDouble(
                    "crystalMeshCustomChroma", defaults.crystalMeshCustomChroma.toDouble(),
                ).toFloat().coerceIn(MIN_CRYSTAL_MESH_CUSTOM_CHROMA, 1f),
                crystalMeshCustomColors = o.optJSONArray("crystalMeshCustomColors")
                    ?.let { a -> (0 until a.length()).map { a.optInt(it) } }
                    ?.take(4)?.takeIf { it.size >= 2 }.orEmpty(),
                crystalRoomIndex = o.optInt("crystalRoomIndex", defaults.crystalRoomIndex),
                crystalRoomArgb = int("crystalRoomArgb"),
                borikenUnlocked = o.optBoolean("borikenUnlocked", defaults.borikenUnlocked),
                borikenMode = o.optBoolean("borikenMode", defaults.borikenMode),
                borikenField = o.optBoolean("borikenField", defaults.borikenField),
                borikenAccent = o.optBoolean("borikenAccent", defaults.borikenAccent),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
