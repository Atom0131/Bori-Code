package com.bori.core.keyboard.theme

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Theme 3.10: sunrise and sunset worked out on the phone (the standard sunrise equation, within a
 * few minutes), for "Dark mode: Sunset to sunrise". No location permission: the place comes from
 * the phone's time zone (a city table, else the zone's offset), which is close enough for a
 * keyboard turning dark.
 */
object SunTimes {

    class Place(val lat: Double, val lon: Double)

    /** Sunrise and sunset as epoch ms, or null on polar days/nights. */
    class Day(val rise: Long, val set: Long)

    fun place(zone: TimeZone = TimeZone.getDefault()): Place {
        CITIES[zone.id]?.let { return Place(it.first, it.second) }
        // Unknown zone: longitude from the UTC offset, a mid latitude.
        return Place(30.0, zone.rawOffset / 3_600_000.0 * 15.0)
    }

    fun day(date: LocalDate, p: Place = place()): Day? {
        val rad = PI / 180
        val jd = date.toEpochDay() + 2440587.5
        val n = ceil(jd - 2451545.0 + 0.0008)
        val jStar = n - p.lon / 360.0
        val m = ((357.5291 + 0.98560028 * jStar) % 360 + 360) % 360
        val c = 1.9148 * sin(m * rad) + 0.02 * sin(2 * m * rad) + 0.0003 * sin(3 * m * rad)
        val lambda = (m + c + 180 + 102.9372) % 360
        val transit = 2451545.0 + jStar + 0.0053 * sin(m * rad) - 0.0069 * sin(2 * lambda * rad)
        val decl = asin(sin(lambda * rad) * sin(23.44 * rad))
        val cosW = (sin(-0.833 * rad) - sin(p.lat * rad) * sin(decl)) / (cos(p.lat * rad) * cos(decl))
        if (cosW < -1 || cosW > 1) return null
        val w = acos(cosW) / rad / 360.0
        fun ms(j: Double) = ((j - 2440587.5) * 86_400_000.0).toLong()
        return Day(ms(transit - w), ms(transit + w))
    }

    /** Is it between sunset and sunrise now? Falls back to 7 pm – 7 am on polar days/nights. */
    fun isDark(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val d = day(date, place(TimeZone.getTimeZone(zone)))
            ?: return Instant.ofEpochMilli(now).atZone(zone).hour.let { it >= 19 || it < 7 }
        return now < d.rise || now >= d.set
    }

    /** Zone id → (latitude, longitude) of its main city. */
    private val CITIES = mapOf(
        "America/Puerto_Rico" to (18.40 to -66.06),
        "America/Santo_Domingo" to (18.49 to -69.93),
        "America/Havana" to (23.11 to -82.37),
        "America/New_York" to (40.71 to -74.01),
        "America/Detroit" to (42.33 to -83.05),
        "America/Indiana/Indianapolis" to (39.77 to -86.16),
        "America/Kentucky/Louisville" to (38.25 to -85.76),
        "America/Chicago" to (41.88 to -87.63),
        "America/Denver" to (39.74 to -104.99),
        "America/Phoenix" to (33.45 to -112.07),
        "America/Boise" to (43.62 to -116.20),
        "America/Los_Angeles" to (34.05 to -118.24),
        "America/Anchorage" to (61.22 to -149.90),
        "Pacific/Honolulu" to (21.31 to -157.86),
        "America/Toronto" to (43.65 to -79.38),
        "America/Vancouver" to (49.28 to -123.12),
        "America/Edmonton" to (53.55 to -113.49),
        "America/Winnipeg" to (49.90 to -97.14),
        "America/Halifax" to (44.65 to -63.57),
        "America/Mexico_City" to (19.43 to -99.13),
        "America/Monterrey" to (25.69 to -100.32),
        "America/Tijuana" to (32.51 to -117.04),
        "America/Cancun" to (21.16 to -86.85),
        "America/Guatemala" to (14.63 to -90.51),
        "America/El_Salvador" to (13.69 to -89.22),
        "America/Tegucigalpa" to (14.07 to -87.19),
        "America/Managua" to (12.11 to -86.24),
        "America/Costa_Rica" to (9.93 to -84.08),
        "America/Panama" to (8.98 to -79.52),
        "America/Bogota" to (4.71 to -74.07),
        "America/Caracas" to (10.48 to -66.90),
        "America/Lima" to (-12.05 to -77.04),
        "America/Guayaquil" to (-2.19 to -79.89),
        "America/La_Paz" to (-16.49 to -68.12),
        "America/Santiago" to (-33.45 to -70.67),
        "America/Argentina/Buenos_Aires" to (-34.60 to -58.38),
        "America/Montevideo" to (-34.90 to -56.16),
        "America/Asuncion" to (-25.26 to -57.58),
        "America/Sao_Paulo" to (-23.55 to -46.63),
        "America/Jamaica" to (18.02 to -76.80),
        "America/Port-au-Prince" to (18.59 to -72.31),
        "America/St_Thomas" to (18.34 to -64.93),
        "Europe/London" to (51.51 to -0.13),
        "Europe/Dublin" to (53.35 to -6.26),
        "Europe/Lisbon" to (38.72 to -9.14),
        "Europe/Madrid" to (40.42 to -3.70),
        "Atlantic/Canary" to (28.12 to -15.43),
        "Europe/Paris" to (48.86 to 2.35),
        "Europe/Berlin" to (52.52 to 13.40),
        "Europe/Rome" to (41.90 to 12.50),
        "Europe/Amsterdam" to (52.37 to 4.90),
        "Europe/Brussels" to (50.85 to 4.35),
        "Europe/Zurich" to (47.38 to 8.54),
        "Europe/Vienna" to (48.21 to 16.37),
        "Europe/Stockholm" to (59.33 to 18.07),
        "Europe/Oslo" to (59.91 to 10.75),
        "Europe/Warsaw" to (52.23 to 21.01),
        "Europe/Athens" to (37.98 to 23.73),
        "Europe/Istanbul" to (41.01 to 28.98),
        "Europe/Moscow" to (55.76 to 37.62),
        "Africa/Cairo" to (30.04 to 31.24),
        "Africa/Lagos" to (6.52 to 3.38),
        "Africa/Johannesburg" to (-26.20 to 28.05),
        "Asia/Dubai" to (25.20 to 55.27),
        "Asia/Kolkata" to (28.61 to 77.21),
        "Asia/Shanghai" to (31.23 to 121.47),
        "Asia/Tokyo" to (35.68 to 139.69),
        "Asia/Seoul" to (37.57 to 126.98),
        "Asia/Manila" to (14.60 to 120.98),
        "Asia/Singapore" to (1.35 to 103.82),
        "Australia/Sydney" to (-33.87 to 151.21),
        "Australia/Melbourne" to (-37.81 to 144.96),
        "Pacific/Auckland" to (-36.85 to 174.76),
    )
}
