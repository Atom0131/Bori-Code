package com.bori.core.keyboard.theme

import android.content.Context
import android.graphics.Color
import java.text.Normalizer

/**
 * Theme 3.10 part 3, "City look": a whole keyboard theme for the kind of place the weather city
 * is (New York, Viejo San Juan, El Yunque, farmland, desert, forest & lake, mountains, beach,
 * city), with the drawn scene's sky following the weather. Detection uses only what the
 * Open-Meteo place search returns (country, state, population, elevation, coordinates).
 */
object Places {

    enum class Look(
        val id: String,
        val title: String,
        val scene: String,
        /** A built-in theme whose colours this look borrows, or null to build them from [colors]. */
        val baseTheme: String?,
        val colors: List<String>,
    ) {
        NYC("nyc", "New York", "nyc", null, listOf("#3E6A9A", "#F2A541", "#9DB4C0")),
        SAN_JUAN("sanjuan", "Viejo San Juan", "sanjuan", "borinquen", emptyList()),
        YUNQUE("yunque", "El Yunque", "yunque", "coqui_night", emptyList()),
        FARM("farm", "Farmland", "farm", null, listOf("#B23A2E", "#C8A04A", "#6FA8DC")),
        DESERT("desert", "Desert", "desert", null, listOf("#C1502E", "#D9A066", "#3A8A5A")),
        FOREST("forest", "Forest & lake", "forest", null, listOf("#2E6B3F", "#3A7CA5", "#C9B78A")),
        MOUNTAINS("mountains", "Mountains", "mountains", "mountain_dawn", emptyList()),
        BEACH("beach", "Beach", "beach", "beach_day", emptyList()),
        CITY("city", "City", "skyline", "city_night", emptyList());

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id }
        }
    }

    /** The user's pick, or the detected look for [city]. */
    fun look(pick: String, city: Weather.City): Look = Look.of(pick) ?: detect(city)

    private fun plain(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

    /** Puerto Rico's rainforest and mountain towns (El Yunque look); the rest get Viejo San Juan. */
    private val PR_MOUNTAIN = setOf(
        "rio grande", "naguabo", "utuado", "jayuya", "adjuntas", "orocovis", "barranquitas", "aibonito",
        "cayey", "ciales", "lares", "maricao", "las marias", "comerio", "villalba", "morovis", "corozal",
        "aguas buenas", "cidra", "san sebastian", "las piedras", "yauco", "luquillo",
    )
    private val FARM_STATES = setOf(
        "Illinois", "Iowa", "Indiana", "Ohio", "Kansas", "Nebraska", "Missouri", "North Dakota", "South Dakota",
        "Kentucky", "Arkansas", "Oklahoma",
    )
    private val NORTH_WOODS = setOf("Wisconsin", "Minnesota", "Michigan")
    private val FOREST_STATES = setOf("Maine", "Vermont", "New Hampshire", "Washington", "Oregon", "Alaska")
    private val DESERT_STATES = setOf("Arizona", "Nevada", "New Mexico")
    private val MOUNTAIN_STATES = setOf("Colorado", "Montana", "Wyoming", "Idaho", "Utah")
    private val BEACH_STATES = setOf("Florida", "Hawaii")
    private val CARIBBEAN = setOf(
        "DO", "JM", "BS", "BB", "TT", "AW", "CW", "VI", "VG", "KY", "TC", "AG", "LC", "GD", "VC", "KN", "DM",
        "MQ", "GP", "BZ", "MV", "FJ", "PF",
    )
    private val DESERT_COUNTRIES = setOf("AE", "SA", "QA", "KW", "OM", "BH", "EG", "JO", "LY", "DZ", "MA", "TN")
    private val FOREST_COUNTRIES = setOf("CA", "NO", "SE", "FI", "EE", "LV")
    private val MOUNTAIN_COUNTRIES = setOf("CH", "AT", "NP", "BT", "AD", "LI")
    private val MX_DESERT = setOf("Sonora", "Chihuahua", "Baja California", "Baja California Sur", "Coahuila")
    private val MX_BEACH = setOf("Quintana Roo", "Yucatán", "Yucatan")

    fun detect(c: Weather.City): Look {
        val cc = c.countryCode.uppercase()
        val big = c.population >= 500_000
        if (c.lat in 40.49..40.92 && c.lon in -74.27..-73.68) return Look.NYC
        if (cc == "PR" || (cc.isEmpty() && c.lat in 17.8..18.6 && c.lon in -67.3..-65.2)) {
            return if (plain(c.name) in PR_MOUNTAIN || c.elevation >= 300) Look.YUNQUE else Look.SAN_JUAN
        }
        if (c.elevation >= 1200 && !big) return Look.MOUNTAINS
        when (cc) {
            "US" -> {
                val a = c.admin1
                return when {
                    a in DESERT_STATES -> if (big) Look.CITY else Look.DESERT
                    a == "Utah" && c.lat < 38.5 -> Look.DESERT
                    a == "Texas" && c.lon < -101.0 -> Look.DESERT
                    a in BEACH_STATES -> Look.BEACH
                    big -> Look.CITY
                    a in MOUNTAIN_STATES -> Look.MOUNTAINS
                    a in FOREST_STATES -> Look.FOREST
                    a in NORTH_WOODS -> if (c.lat >= 45.0) Look.FOREST else Look.FARM
                    a in FARM_STATES -> if (c.population < 50_000) Look.FARM else Look.CITY
                    c.population in 1 until 50_000 -> Look.FARM
                    else -> Look.CITY
                }
            }
            "MX" -> when {
                c.admin1 in MX_DESERT -> return if (big) Look.CITY else Look.DESERT
                c.admin1 in MX_BEACH -> return Look.BEACH
            }
        }
        return when {
            big -> Look.CITY
            cc in CARIBBEAN -> Look.BEACH
            cc in DESERT_COUNTRIES -> Look.DESERT
            cc in FOREST_COUNTRIES -> Look.FOREST
            cc in MOUNTAIN_COUNTRIES -> Look.MOUNTAINS
            c.population in 1 until 50_000 -> Look.FARM
            else -> Look.CITY
        }
    }

    /**
     * The look as a theme with day and night variants; the scene's sky shows [weather] (a
     * Weather.mood). Puerto Rico and the older scene looks borrow a built-in theme's colours.
     */
    fun themeFor(context: Context, look: Look, weather: String?): ThemeSpec {
        val bg = BackgroundSpec(type = "scene", scene = look.scene, weather = weather)
        val id = "city_" + look.id
        val baseId = look.baseTheme
        if (baseId != null) {
            val store = ThemeStore(context)
            val base = store.get(baseId)
            fun fix(v: ThemeVariant) = v.copy(
                backdrop = bg,
                // Borinquen has solid keys; let the scene show through a little.
                keys = if (v.backdrop == null) v.keys.copy(opacity = minOf(v.keys.opacity, 0.85f)) else v.keys,
            )
            return base.copy(id = id, name = look.title, light = fix(base.light), dark = base.dark?.let { fix(it) })
        }
        val (a, b, c) = look.colors.map { Color.parseColor(it) }
        return ThemeSpec(
            id = id,
            name = look.title,
            category = "Places",
            light = ThemeMaker.sceneVariant(a, b, c, dark = false, scene = look.scene, weather = weather),
            dark = ThemeMaker.sceneVariant(a, b, c, dark = true, scene = look.scene, weather = weather),
        )
    }
}
