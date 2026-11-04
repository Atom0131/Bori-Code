package com.bori.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

class AutoEnterStatsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.now().toString()

    @Test fun readsOldFlatFileAndRewritesNewFormat() {
        val dir = tmp.newFolder()
        File(dir, "stats.json").writeText("""{"$today":4,"2026-01-02":7}""")
        val stats = AutoEnterStats(dir)
        assertEquals(4, stats.totals().today)
        assertEquals(11, stats.totals().all)
        stats.add()
        val text = File(dir, "stats.json").readText()
        assertTrue(text.contains("\"days\""))
        assertTrue(File(dir, "stats.json.bak").isFile)
        assertTrue(File(dir, "backups/stats-$today.json").isFile)
        assertEquals(12, AutoEnterStats(dir).totals().all)
    }

    @Test fun damagedFileIsKeptAndRestoredFromBackup() {
        val dir = tmp.newFolder()
        val stats = AutoEnterStats(dir)
        repeat(3) { stats.add() }
        File(dir, "stats.json").writeText("{broken")
        val again = AutoEnterStats(dir)
        assertTrue(again.totals().all >= 2)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("stats.corrupt-") })
        assertTrue(again.loadNote.isNotEmpty())
    }

    @Test fun importHigherWinsAndTwiceIsHarmless() {
        val dir = tmp.newFolder()
        val stats = AutoEnterStats(dir)
        repeat(5) { stats.add() }
        val file = """{"version":1,"days":{"$today":3,"2025-12-25":9}}"""
        assertEquals(1 to 9, stats.importFrom(file.byteInputStream()))
        assertEquals(0 to 0, stats.importFrom(file.byteInputStream()))
        assertEquals(5, stats.totals().today)
        assertEquals(1 to 5, stats.importFrom("""{"days":{"$today":10}}""".byteInputStream()))
        assertEquals(10, stats.totals().today)
        assertTrue(File(dir, "backups").listFiles()!!.any { it.name.startsWith("preimport-") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun junkImportThrows() {
        AutoEnterStats(tmp.newFolder()).importFrom("""{"days":{"not-a-date":1}}""".byteInputStream())
    }

    @Test fun exportRoundTrips() {
        val a = AutoEnterStats(tmp.newFolder())
        repeat(2) { a.add() }
        val out = ByteArrayOutputStream()
        assertEquals(2, a.exportTo(out))
        val b = AutoEnterStats(tmp.newFolder())
        assertEquals(1 to 2, b.importFrom(out.toByteArray().inputStream()))
        assertNull(AutoEnterStats.parse("[]"))
        assertNull(AutoEnterStats.parse("{}"))
        assertNull(AutoEnterStats.parse("""{"hello":"world"}"""))
        assertEquals(emptyMap<String, Int>(), AutoEnterStats.parse("""{"version":1,"days":{}}"""))
    }
}
