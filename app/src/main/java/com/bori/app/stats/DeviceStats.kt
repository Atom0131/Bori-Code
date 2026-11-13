package com.bori.app.stats

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.bori.app.terminal.DeviceHeat
import java.io.File

/**
 * One reading of the phone's stats. Every field is null when this phone doesn't let an app read it
 * (Android blocks /proc/stat and most of /sys; what's left differs by chip), and a null is shown as
 * "not available", never as 0.
 */
data class StatsSnapshot(
    val cpuTempC: Float? = null,
    val gpuTempC: Float? = null,
    val gpuLoadPercent: Int? = null,
    /** The fastest core's current clock. */
    val cpuGhz: Float? = null,
    /** CPU used by Bori and everything it started (the Linux agents too), as a share of all cores. */
    val boriCpuPercent: Float? = null,
    val ramUsedBytes: Long? = null,
    val ramTotalBytes: Long? = null,
    val boriRamBytes: Long? = null,
    val battery: DeviceHeat.Reading? = null,
    /** Watts going in or out of the battery (see [StatsSnapshot.battery] for which way). */
    val powerWatts: Float? = null,
) {
    fun has(kind: StatKind, tokens: TokenStats.State): Boolean = when (kind) {
        StatKind.CPU_TEMP -> cpuTempC != null
        StatKind.GPU_TEMP -> gpuTempC != null
        StatKind.GPU_LOAD -> gpuLoadPercent != null
        StatKind.CPU_SPEED -> cpuGhz != null
        StatKind.BORI_CPU -> boriCpuPercent != null
        StatKind.RAM -> ramTotalBytes != null
        StatKind.BORI_RAM -> boriRamBytes != null
        StatKind.BATTERY_TEMP, StatKind.BATTERY -> battery != null && battery.batteryPercent >= 0
        StatKind.POWER -> powerWatts != null
        StatKind.THERMAL -> battery != null && battery.thermalStatus >= 0
        StatKind.TOKEN_SPEED, StatKind.TOKENS -> tokens.any
    }
}

/** Reads [StatsSnapshot]s. Keeps the previous CPU times, so [read] gives CPU use since the last call. */
class DeviceStats(private val context: Context) {

    private val zones: List<Pair<String, File>> by lazy {
        File(THERMAL_DIR).listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().mapNotNull { dir ->
            val type = runCatching { File(dir, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            type to File(dir, "temp")
        }
    }
    private val cpuZones by lazy { zones.filter { isCpuZone(it.first) }.map { it.second } }
    private val gpuZones by lazy { zones.filter { isGpuZone(it.first) }.map { it.second } }
    private val freqFiles by lazy {
        File(CPUFREQ_DIR).listFiles { f -> f.name.startsWith("policy") }.orEmpty()
            .map { File(it, "scaling_cur_freq") }
            .ifEmpty {
                File(CPU_DIR).listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
                    .map { File(it, "cpufreq/scaling_cur_freq") }
            }
    }
    private val clockTicks by lazy { runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1) }
    private val pageSize by lazy { runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L) }
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private var lastTicks: Map<Int, Long> = emptyMap()
    private var lastAt = 0L

    fun read(): StatsSnapshot {
        val battery = runCatching { DeviceHeat.read(context) }.getOrNull()
        val mem = runCatching {
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        }.getOrNull()
        val (cpuPercent, rss) = ownProcesses()
        return StatsSnapshot(
            cpuTempC = cpuZones.mapNotNull { readTemp(it) }.maxOrNull(),
            gpuTempC = readTemp(File(KGSL, "temp")) ?: gpuZones.mapNotNull { readTemp(it) }.maxOrNull(),
            gpuLoadPercent = gpuLoad(),
            cpuGhz = freqFiles.mapNotNull { readLong(it) }.maxOrNull()?.let { it / 1_000_000f },
            boriCpuPercent = cpuPercent,
            ramUsedBytes = mem?.let { it.totalMem - it.availMem },
            ramTotalBytes = mem?.totalMem,
            boriRamBytes = rss,
            battery = battery,
            powerWatts = power(),
        )
    }

    private fun gpuLoad(): Int? {
        GPU_LOAD_FILES.forEach { path -> readText(File(path))?.let(::parseGpuLoad)?.let { return it } }
        return readText(File(KGSL, "gpubusy"))?.let(::parseGpuBusy)
    }

    /** CPU % since the last call and resident memory, over this app's own processes. */
    private fun ownProcesses(): Pair<Float?, Long?> {
        val uid = Process.myUid()
        val ticks = HashMap<Int, Long>()
        var rssPages = 0L
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            runCatching {
                if (Os.stat("/proc/$pid").st_uid != uid) return@forEach
                val stat = parseProcStat(File("/proc/$pid/stat").readText()) ?: return@forEach
                ticks[pid] = stat.first
                rssPages += stat.second
            }
        }
        if (ticks.isEmpty()) return null to null
        val now = System.nanoTime()
        val percent = if (lastAt == 0L) null else {
            // A process that's new since the last reading counts from 0; one that ended drops out.
            val used = ticks.entries.sumOf { (pid, t) -> (t - (lastTicks[pid] ?: 0L)).coerceAtLeast(0) }
            val seconds = (now - lastAt) / 1e9
            if (seconds <= 0) null else (used.toDouble() / clockTicks / seconds / cores * 100).toFloat().coerceIn(0f, 100f)
        }
        lastTicks = ticks
        lastAt = now
        return percent to rssPages * pageSize
    }

    private fun power(): Float? = runCatching {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current == Int.MIN_VALUE || current == 0) return null
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val millivolts = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        if (millivolts <= 0) return null
        powerWatts(current, millivolts)
    }.getOrNull()

    companion object {
        private const val THERMAL_DIR = "/sys/class/thermal"
        private const val CPU_DIR = "/sys/devices/system/cpu"
        private const val CPUFREQ_DIR = "$CPU_DIR/cpufreq"
        private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        /** Adreno, then the paths Mali and Pixel kernels use. */
        private val GPU_LOAD_FILES = listOf(
            "$KGSL/gpu_busy_percentage",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/mali0/device/utilization",
        )

        /** CPU core sensors ("cpu-0-1-0", "cpuss-1", "cpu_big0"), not the hardware trip points or the cache. */
        fun isCpuZone(type: String): Boolean {
            val t = type.lowercase()
            return t.startsWith("cpu") && "trip" !in t && "llc" !in t
        }

        fun isGpuZone(type: String): Boolean {
            val t = type.lowercase()
            return ("gpu" in t) && "trip" !in t
        }

        /** Thermal files hold millidegrees on most phones and whole degrees on a few; junk readings are dropped. */
        fun parseTemp(text: String): Float? {
            val raw = text.trim().toLongOrNull() ?: return null
            val c = if (raw > 1000 || raw < -1000) raw / 1000f else raw.toFloat()
            return c.takeIf { it > -30f && it < 150f }
        }

        /** "37 %" or "37". */
        fun parseGpuLoad(text: String): Int? =
            Regex("^\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

        /** kgsl's "busy total" counters. */
        fun parseGpuBusy(text: String): Int? {
            val n = text.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (n.size < 2 || n[1] <= 0) return null
            return (n[0] * 100 / n[1]).toInt().coerceIn(0, 100)
        }

        /** `/proc/<pid>/stat`: utime+stime ticks and resident pages. The name may hold spaces, so split after ')'. */
        fun parseProcStat(stat: String): Pair<Long, Long>? {
            val close = stat.lastIndexOf(')')
            if (close < 0 || close + 2 > stat.length) return null
            val f = stat.substring(close + 2).split(' ')
            // After the name: state(0) ppid(1) … utime(11) stime(12) … rss(21).
            val utime = f.getOrNull(11)?.toLongOrNull() ?: return null
            val stime = f.getOrNull(12)?.toLongOrNull() ?: return null
            val rss = f.getOrNull(21)?.toLongOrNull() ?: 0L
            return (utime + stime) to rss
        }

        /**
         * BatteryManager's CURRENT_NOW is µA on most phones but mA on some (older Samsungs), and its
         * sign differs by maker. A value under 10 000 is read as mA (10 mA would be an implausibly idle phone).
         */
        fun powerWatts(current: Int, millivolts: Int): Float {
            val abs = kotlin.math.abs(current.toLong())
            val microAmps = if (abs < 10_000) abs * 1000 else abs
            return (microAmps / 1e6 * millivolts / 1000).toFloat()
        }

        private fun readText(f: File): String? = runCatching { f.readText() }.getOrNull()
        private fun readLong(f: File): Long? = readText(f)?.trim()?.toLongOrNull()
        private fun readTemp(f: File): Float? = readText(f)?.let(::parseTemp)
    }
}
