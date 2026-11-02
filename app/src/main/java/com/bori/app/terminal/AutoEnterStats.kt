package com.bori.app.terminal

import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * How many Enters auto-enter pressed, per day, in `files/auto-enter/stats.json` (never reset; the
 * totals add up from the days). Written to a temp file and renamed, so a crash can't half-write it.
 */
class AutoEnterStats(dir: File) {
    private val file = File(dir.apply { mkdirs() }, "stats.json")
    private val days = HashMap<String, Int>()

    init {
        runCatching {
            val json = JSONObject(file.readText())
            json.keys().forEach { days[it] = json.optInt(it) }
        }
    }

    data class Totals(val today: Int, val week: Int, val month: Int, val year: Int, val all: Int)

    @Synchronized
    fun add() {
        val key = LocalDate.now().toString()
        days[key] = (days[key] ?: 0) + 1
        runCatching {
            val tmp = File(file.parentFile, "stats.json.tmp")
            tmp.writeText(JSONObject(days as Map<*, *>).toString())
            if (!tmp.renameTo(file)) error("rename failed")
        }
    }

    @Synchronized
    fun totals(): Totals {
        val today = LocalDate.now()
        // Weeks run Monday to Sunday, as in the Windows helper.
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun since(from: LocalDate) = days.entries.sumOf { (d, n) ->
            val date = runCatching { LocalDate.parse(d) }.getOrNull()
            if (date != null && !date.isBefore(from) && !date.isAfter(today)) n else 0
        }
        return Totals(
            today = days[today.toString()] ?: 0,
            week = since(monday),
            month = since(today.withDayOfMonth(1)),
            year = since(today.withDayOfYear(1)),
            all = days.values.sum(),
        )
    }
}
