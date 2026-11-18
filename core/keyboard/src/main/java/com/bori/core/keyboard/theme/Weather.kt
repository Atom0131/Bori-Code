package com.bori.core.keyboard.theme

import android.content.Context
import com.bori.core.keyboard.ime.DiagLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Theme 3.10 part 2: "Follows the weather" background effect. Opt-in (off by default). The user
 * types a city once (Open-Meteo geocoding); the keyboard then asks Open-Meteo for the current
 * weather at most once an hour, with the coordinates rounded to 0.1° (about 10 km). No location
 * permission, no key, nothing typed is ever sent. Weather data by Open-Meteo.com (CC BY 4.0).
 */
object Weather {

    class City(
        val name: String,
        val detail: String,
        val lat: Double,
        val lon: Double,
        /** From the geocoding result, for the City look (Places.detect); empty/0 when unknown. */
        val countryCode: String = "",
        val admin1: String = "",
        val population: Int = 0,
        val elevation: Double = 0.0,
    ) {
        val label get() = if (detail.isEmpty()) name else "$name, $detail"
    }

    class Now(val code: Int, val isDay: Boolean, val at: Long)

    private const val CACHE = "bori_weather"
    private const val REFRESH_MS = 60L * 60 * 1000
    /** Older than this, the weather isn't shown (season effect instead). */
    private const val STALE_MS = 3L * 60 * 60 * 1000
    private val fetching = AtomicBoolean(false)

    /** City search for Settings (call off the main thread). */
    fun search(query: String): List<City> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val lang = Locale.getDefault().language.takeIf { it == "es" } ?: "en"
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=6&language=$lang&format=json"))
        val arr = json.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val detail = listOf(o.optString("admin1"), o.optString("country"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            City(
                o.optString("name"), detail, o.optDouble("latitude"), o.optDouble("longitude"),
                o.optString("country_code"), o.optString("admin1"), o.optInt("population"),
                o.optDouble("elevation").takeIf { !it.isNaN() } ?: 0.0,
            )
        }
    }

    private fun round(v: Double) = (v * 10).roundToInt() / 10.0

    private fun placeKey(lat: Double, lon: Double) = "${round(lat)},${round(lon)}"

    /** The saved reading for this place, if it is fresh enough to show. */
    fun current(context: Context, lat: Double, lon: Double): Now? {
        val sp = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        if (sp.getString("place", null) != placeKey(lat, lon)) return null
        val at = sp.getLong("at", 0L)
        if (System.currentTimeMillis() - at > STALE_MS) return null
        return Now(sp.getInt("code", 0), sp.getBoolean("day", true), at)
    }

    /**
     * Fetches the weather in the background when the saved reading is over an hour old (or for
     * another place). [changed] runs on that background thread when a new reading arrives.
     */
    fun refreshIfNeeded(context: Context, lat: Double, lon: Double, changed: () -> Unit) {
        val app = context.applicationContext
        val sp = app.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        val key = placeKey(lat, lon)
        val now = System.currentTimeMillis()
        val last = maxOf(sp.getLong("at", 0L), sp.getLong("tried", 0L))
        if (sp.getString("place", null) == key && now - last < REFRESH_MS) return
        if (sp.getString("place", null) != key && now - sp.getLong("tried", 0L) < 60_000L) return
        if (!fetching.compareAndSet(false, true)) return
        sp.edit().putLong("tried", now).apply()
        Thread({
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${round(lat)}&longitude=${round(lon)}" +
                    "&current=weather_code,is_day"
                val cur = JSONObject(get(url)).getJSONObject("current")
                val old = current(app, lat, lon)
                val code = cur.getInt("weather_code")
                val day = cur.optInt("is_day", 1) == 1
                sp.edit().putString("place", key).putLong("at", System.currentTimeMillis())
                    .putInt("code", code).putBoolean("day", day).apply()
                if (old == null || effectFor(old.code, old.isDay) != effectFor(code, day)) changed()
            } catch (t: Throwable) {
                DiagLog.event("weather failed: $t")
            } finally {
                fetching.set(false)
            }
        }, "bori-weather").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** WMO weather code → AmbientView effect id. */
    fun effectFor(code: Int, isDay: Boolean): String = when (code) {
        0, 1 -> if (isDay) "sunrays" else "stars"
        2, 3, 45, 48 -> "clouds"
        in 51..67, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        in 95..99 -> "storm"
        else -> "clouds"
    }

    /** WMO weather code → sky mood for drawn scenes (BackgroundSpec.weather). */
    fun mood(code: Int): String = when (code) {
        0, 1 -> "clear"
        2, 3, 45, 48 -> "cloudy"
        in 51..67, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        in 95..99 -> "storm"
        else -> "clear"
    }

    /** A word for the WMO code, for status lines. */
    fun describe(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "Clear" else "Clear night"
        1 -> if (isDay) "Mostly clear" else "Mostly clear night"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> "Weather"
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "BoriKeyboard")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
