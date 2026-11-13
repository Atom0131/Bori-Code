package com.bori.app.stats

import com.bori.app.terminal.DeviceHeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsParsingTest {

    @Test fun cpuAndGpuZonesFromTheFold() {
        // Zone types as the Fold (SM-F971U) lists them.
        val cpu = listOf("cpu-0-0-0", "cpu-1-1-1", "cpuss-0")
        val notCpu = listOf("cpullc-0-0", "cpu-hw-trip-0", "gpuss-3", "battery", "qmx-0-0", "ddr")
        cpu.forEach { assertTrue(it, DeviceStats.isCpuZone(it)) }
        notCpu.forEach { assertFalse(it, DeviceStats.isCpuZone(it)) }
        assertTrue(DeviceStats.isGpuZone("gpuss-10"))
        assertFalse(DeviceStats.isGpuZone("cpu-0-0-0"))
    }

    @Test fun temperatures() {
        assertEquals(31.2f, DeviceStats.parseTemp("31200\n")!!, 0.01f)
        assertEquals(42f, DeviceStats.parseTemp("42")!!, 0.01f)
        assertNull(DeviceStats.parseTemp("-273000")) // an unused sensor
        assertNull(DeviceStats.parseTemp("105000000"))
        assertNull(DeviceStats.parseTemp("n/a"))
    }

    @Test fun gpuLoad() {
        assertEquals(0, DeviceStats.parseGpuLoad("0 %"))
        assertEquals(37, DeviceStats.parseGpuLoad("37\n"))
        assertNull(DeviceStats.parseGpuLoad("busy"))
        assertEquals(7, DeviceStats.parseGpuBusy("  76187 1002844 "))
        assertNull(DeviceStats.parseGpuBusy("0 0"))
    }

    @Test fun procStatWithSpacesInTheName() {
        val stat = "12581 (Bori code (x)) S 12577 12577 12577 0 -1 4194304 636 0 0 0 150 50 0 0 20 0 1 0 10143704 123456 2048 rest"
        assertEquals(200L to 2048L, DeviceStats.parseProcStat(stat))
        assertNull(DeviceStats.parseProcStat("garbage"))
    }

    @Test fun powerInMicroOrMilliAmps() {
        assertEquals(2.0f, DeviceStats.powerWatts(-500_000, 4000), 0.01f) // µA
        assertEquals(2.0f, DeviceStats.powerWatts(500, 4000), 0.01f) // mA
    }

    @Test fun claudeUsageCountsEachReplyOnce() {
        val u = ClaudeUsage()
        fun usage(out: Int, read: Int) =
            """{"input_tokens":2,"cache_creation_input_tokens":100,"cache_read_input_tokens":$read,"output_tokens":$out}"""
        u.addLines(
            """{"type":"user","timestamp":"2026-11-12T19:10:03.706Z","message":{"role":"user","content":"hi"}}
{"type":"assistant","timestamp":"2026-11-12T19:10:12.639Z","message":{"id":"msg_A","usage":${usage(978, 27479)}}}
{"type":"assistant","timestamp":"2026-11-12T19:10:13.487Z","message":{"id":"msg_A","usage":${usage(978, 27479)}}}
{"type":"user","timestamp":"2026-11-12T19:10:14.000Z","message":{"role":"user","content":[]}}
{"type":"assistant","timestamp":"2026-11-12T19:10:16.000Z","message":{"id":"msg_B","usa""",
        )
        assertEquals(978L, u.outputTokens)
        assertEquals(27581L, u.lastContext)
        // 978 tokens from the prompt (03.706) to the reply's last line (13.487): about 100 tok/s.
        assertEquals(100f, u.lastSpeed!!, 1f)
        // The cut line finishes in the next read.
        u.addLines("""ge":${usage(100, 30000)}}}
""")
        assertEquals(1078L, u.outputTokens)
        assertEquals(30102L, u.lastContext)
        assertEquals(50f, u.lastSpeed!!, 1f)
    }

    @Test fun formatsOnlyChosenStatsThatHaveValues() {
        val heat = DeviceHeat.Reading(80, true, 36.4f, 1, null)
        val snap = StatsSnapshot(cpuTempC = 41.2f, ramUsedBytes = 7L shl 30, ramTotalBytes = 11L shl 30, battery = heat)
        val tokens = TokenStats.State(tokensPerSecond = 42.4f, outputTokens = 12_345, contextTokens = 46_000)
        val shown = setOf(StatKind.CPU_TEMP, StatKind.GPU_TEMP, StatKind.RAM, StatKind.TOKEN_SPEED, StatKind.TOKENS, StatKind.BATTERY)
        val line = StatsFormat.line(StatsFormat.items(shown, snap, tokens))
        assertEquals("CPU 41° · RAM 7.0/11.0 GB · Charge 80% ⚡ · Speed 42 tok/s · Tokens 12.3k out · ctx 46.0k", line)
    }
}
