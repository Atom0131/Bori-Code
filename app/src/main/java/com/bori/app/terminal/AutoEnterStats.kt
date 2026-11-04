package com.bori.app.terminal

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * How many Enters auto-enter pressed, per day, in `files/auto-enter/stats.json` (never reset; the
 * totals add up from the days). Same file format as the PC / Windows agy auto-enter helper
 * (`{"version":1,"days":{"2026-11-03":12}}`), so an export from either one imports into the other.
 *
 * Safety, as in the helper: written to a temp file and renamed; the previous version is kept as
 * `stats.json.bak`, plus one `backups/stats-<day>.json` a day (newest [KEEP_SNAPSHOTS]). A damaged
 * file is renamed, never overwritten, and the valid backup with the highest total is restored.
 */
class AutoEnterStats(private val dir: File) {
    private val file = File(dir.apply { mkdirs() }, "stats.json")
    private val bak = File(dir, "stats.json.bak")
    private val backups = File(dir, "backups")
    private val days = HashMap<String, Int>()

    /** What loading had to do (a restore), for the log; empty when the file was fine. */
    var loadNote = ""
        private set

    init {
        load()
    }

    data class Totals(val today: Int, val week: Int, val month: Int, val year: Int, val all: Int)

    @Synchronized
    fun add() {
        val key = LocalDate.now().toString()
        days[key] = (days[key] ?: 0) + 1
        runCatching { save() }
    }

    /** Writes every day's count to [out]; returns the total. */
    @Synchronized
    fun exportTo(out: OutputStream): Int {
        val json = JSONObject()
            .put("version", 1)
            .put("exported", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .put("days", JSONObject(days as Map<*, *>))
        out.bufferedWriter().use { it.write(json.toString(1)) }
        return days.values.sum()
    }

    /**
     * Merges an exported file in. Per day the higher count wins, so importing the same file twice (or an
     * older one) never double-counts or lowers anything. Returns (days changed, Enters added).
     * @throws IllegalArgumentException when [input] isn't a stats file.
     */
    @Synchronized
    fun importFrom(input: InputStream): Pair<Int, Int> {
        val theirs = parse(input.bufferedReader().use { it.readText() })
            ?: throw IllegalArgumentException("Not an auto-enter stats file")
        val before = days.values.sum()
        if (days.isNotEmpty() && file.isFile) {
            runCatching { file.copyTo(File(backups.apply { mkdirs() }, "preimport-${stamp()}.json"), overwrite = true) }
        }
        var changed = 0
        for ((day, n) in theirs) {
            if (n > (days[day] ?: 0)) {
                days[day] = n
                changed++
            }
        }
        if (changed > 0) save()
        return changed to days.values.sum() - before
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

    private fun load() {
        if (file.exists()) {
            val mine = read(file)
            if (mine != null) {
                days.putAll(mine)
                return
            }
            // Damaged: keep the evidence, then recover from a backup.
            runCatching { file.renameTo(File(dir, "stats.corrupt-${stamp()}.json")) }
            loadNote = "stats.json was damaged; restored"
        }
        // Counts only go up, so the valid backup with the highest total is the newest one.
        val candidates = listOf(bak) + (backups.listFiles()
            ?.filter { it.name.endsWith(".json") && (it.name.startsWith("stats-") || it.name.startsWith("preimport-")) }
            ?.sortedByDescending { it.name }
            .orEmpty())
        val best = candidates.filter { it.isFile }
            .mapNotNull { f -> read(f)?.let { f to it } }
            .maxByOrNull { (_, d) -> d.values.sum() }
        if (best != null) {
            days.putAll(best.second)
            loadNote = (loadNote.ifEmpty { "stats.json missing; restored" }) + " from ${best.first.name}"
            runCatching { save() }
        }
    }

    private fun save() {
        val json = JSONObject()
            .put("version", 1)
            .put("updated", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .put("days", JSONObject(days as Map<*, *>))
        val tmp = File(dir, "stats.json.tmp")
        tmp.writeText(json.toString(1))
        if (file.isFile && read(file) != null) file.copyTo(bak, overwrite = true)
        if (!tmp.renameTo(file)) error("rename failed")
        runCatching {
            backups.mkdirs()
            file.copyTo(File(backups, "stats-${LocalDate.now()}.json"), overwrite = true)
            backups.listFiles()
                ?.filter { it.name.startsWith("stats-") && it.name.endsWith(".json") }
                ?.sortedByDescending { it.name }
                ?.drop(KEEP_SNAPSHOTS)
                ?.forEach { it.delete() }
        }
    }

    private fun read(f: File): Map<String, Int>? = runCatching { parse(f.readText()) }.getOrNull()

    private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

    companion object {
        private const val KEEP_SNAPSHOTS = 90

        /**
         * The per-day counts in [text], or null when it isn't a valid stats file. Takes the helper's
         * `{"days":{…}}` and Bori Code's older flat `{"2026-11-03":12}`.
         */
        internal fun parse(text: String): Map<String, Int>? = runCatching {
            val json = JSONObject(text)
            // An empty object is nothing at all, not an old file with no days in it.
            require(json.has("days") || json.length() > 0) { "empty" }
            val src = if (json.has("days")) json.getJSONObject("days") else json
            val out = HashMap<String, Int>()
            for (key in src.keys()) {
                LocalDate.parse(key)
                val v = src.get(key)
                require(v is Number) { "count for $key isn't a number" }
                out[key] = v.toInt()
            }
            out
        }.getOrNull()
    }
}
