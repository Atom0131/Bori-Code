package com.bori.core.keyboard.theme

import android.graphics.Color
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Theme 3.10 "living themes", part 1 (offline): holidays and seasons. Dates come from the phone's
 * calendar only. A holiday brings a background effect and, if the user wants, its own colours
 * (built here with the 4.5:1 contrast rule). Weather is part 2.
 */
object Living {

    class Holiday(
        val id: String,
        val name: String,
        /** AmbientView effect id shown on the day. */
        val effect: String,
        /** Main, second and third colour: keys, accent and outline gradient. */
        val colors: IntArray,
        /** Date ranges (inclusive) in a given year; later entries in [ALL] lose to earlier ones. */
        val dates: (Int) -> List<ClosedRange<LocalDate>>,
    )

    private fun rgb(hex: String) = Color.parseColor(hex)
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)
    private fun one(y: Int, m: Int, d: Int) = listOf(day(y, m, d)..day(y, m, d))

    /** The [nth] [dow] of month [m] (1-based), e.g. Mother's Day = 2nd Sunday of May. */
    private fun nth(y: Int, m: Int, dow: DayOfWeek, nth: Int): LocalDate =
        LocalDate.of(y, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(nth, dow))

    /** Easter Sunday (anonymous Gregorian computus). */
    fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val dayOf = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(y, month, dayOf)
    }

    /** Fiestas de la Calle San Sebastián: Thursday to Sunday, the Sunday being the 3rd of January. */
    private fun sanSebastian(y: Int): ClosedRange<LocalDate> {
        val sunday = nth(y, 1, DayOfWeek.SUNDAY, 3)
        return sunday.minusDays(3)..sunday
    }

    private val PR = intArrayOf(rgb("#E4002B"), rgb("#41A6E0"), rgb("#FFFFFF"))

    /** Most specific first: the first match on a date wins (Reyes beats Navidad). */
    val ALL: List<Holiday> = listOf(
        Holiday("new_year", "Año Nuevo", "fireworks", intArrayOf(rgb("#FFC83D"), rgb("#C0C6D0"), rgb("#FFE9A8"))) { y ->
            listOf(day(y, 1, 1)..day(y, 1, 1), day(y, 12, 31)..day(y, 12, 31))
        },
        Holiday("reyes", "Día de Reyes", "stars", intArrayOf(rgb("#6A2C91"), rgb("#F2B705"), rgb("#1E7D4F"))) { y ->
            listOf(day(y, 1, 5)..day(y, 1, 6))
        },
        Holiday("san_sebastian", "Fiestas de la Calle San Sebastián", "confetti", PR) { y -> listOf(sanSebastian(y)) },
        Holiday("valentine", "San Valentín", "hearts", intArrayOf(rgb("#E63950"), rgb("#FF8FAB"), rgb("#FFD1DC"))) { y -> one(y, 2, 14) },
        Holiday("st_patrick", "St. Patrick's Day", "confetti", intArrayOf(rgb("#1E8C45"), rgb("#7BC96F"), rgb("#F2C14E"))) { y -> one(y, 3, 17) },
        Holiday("easter", "Pascua", "petals", intArrayOf(rgb("#B39DDB"), rgb("#F8BBD0"), rgb("#FFF59D"))) { y ->
            val e = easter(y); listOf(e.minusDays(1)..e)
        },
        Holiday("mothers_day", "Día de las Madres", "petals", intArrayOf(rgb("#D81B60"), rgb("#F48FB1"), rgb("#FCE4EC"))) { y ->
            val d = nth(y, 5, DayOfWeek.SUNDAY, 2); listOf(d..d)
        },
        Holiday("fathers_day", "Día de los Padres", "confetti", intArrayOf(rgb("#1565C0"), rgb("#90CAF9"), rgb("#FFB300"))) { y ->
            val d = nth(y, 6, DayOfWeek.SUNDAY, 3); listOf(d..d)
        },
        Holiday("san_juan", "Noche de San Juan", "stars", intArrayOf(rgb("#0077B6"), rgb("#48CAE4"), rgb("#F9E79F"))) { y ->
            listOf(day(y, 6, 23)..day(y, 6, 24))
        },
        Holiday("july4", "4th of July", "fireworks", intArrayOf(rgb("#B22234"), rgb("#3C3B6E"), rgb("#FFFFFF"))) { y -> one(y, 7, 4) },
        Holiday("constitucion", "Día de la Constitución de Puerto Rico", "fireworks", PR) { y -> one(y, 7, 25) },
        Holiday("halloween", "Halloween", "fireflies", intArrayOf(rgb("#FF7518"), rgb("#6A1B9A"), rgb("#B2FF59"))) { y ->
            listOf(day(y, 10, 25)..day(y, 10, 31))
        },
        Holiday("descubrimiento", "Descubrimiento de Puerto Rico", "confetti", PR) { y -> one(y, 11, 19) },
        Holiday("thanksgiving", "Acción de Gracias", "leaves", intArrayOf(rgb("#C1502E"), rgb("#E9A23B"), rgb("#7A5230"))) { y ->
            val d = nth(y, 11, DayOfWeek.THURSDAY, 4); listOf(d..d)
        },
        // Puerto Rico's long Christmas: mid-December through San Sebastián.
        Holiday("navidad", "Navidad", "snow", intArrayOf(rgb("#C62828"), rgb("#2E7D32"), rgb("#F9A825"))) { y ->
            listOf(day(y, 12, 15)..day(y, 12, 31), day(y, 1, 1)..sanSebastian(y).endInclusive)
        },
    )

    fun byId(id: String?): Holiday? = ALL.firstOrNull { it.id == id }

    fun holidayOn(date: LocalDate): Holiday? =
        ALL.firstOrNull { h -> h.dates(date.year).any { date in it } }

    /** The next holidays starting after today (today's, if any, first), soonest first. */
    fun upcoming(from: LocalDate, count: Int): List<Pair<Holiday, LocalDate>> {
        val out = ArrayList<Pair<Holiday, LocalDate>>()
        for (h in ALL) {
            val starts = (h.dates(from.year) + h.dates(from.year + 1)).map { it.start to it.endInclusive }
            val next = starts.filter { it.second >= from }.minByOrNull { it.first } ?: continue
            out.add(h to maxOf(next.first, from))
        }
        return out.sortedBy { it.second }.take(count)
    }

    // Seasons: meteorological (Dec–Feb winter in the north), flipped south of the equator.
    enum class Season(val title: String, val effect: String) {
        WINTER("Winter", "snow"),
        SPRING("Spring", "petals"),
        SUMMER("Summer", "sunrays"),
        FALL("Fall", "leaves"),
    }

    fun season(date: LocalDate, south: Boolean = SunTimes.place().lat < 0): Season {
        val north = when (date.monthValue) {
            12, 1, 2 -> Season.WINTER
            3, 4, 5 -> Season.SPRING
            6, 7, 8 -> Season.SUMMER
            else -> Season.FALL
        }
        return if (!south) north else Season.entries[(north.ordinal + 2) % 4]
    }

    /** A readable theme in the holiday's colours, with day and night looks. */
    fun themeFor(h: Holiday): ThemeSpec {
        val (main, second, third) = Triple(h.colors[0], h.colors[1], h.colors[2])
        return ThemeSpec(
            id = "holiday_" + h.id,
            name = h.name,
            category = "Holidays",
            light = ThemeMaker.variant(main, second, third, dark = false, keys = KeyStyle(borderWidth = 1.5f)),
            dark = ThemeMaker.variant(main, second, third, dark = true, keys = KeyStyle(borderWidth = 1.5f, glow = 4f)),
        )
    }

}
