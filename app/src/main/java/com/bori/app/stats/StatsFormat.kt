package com.bori.app.stats

import java.util.Locale

/** How each stat reads in the overlay and the notification. */
object StatsFormat {

    /** One shown stat: a short [label] ("CPU") and its [value] ("41°"). */
    data class Item(val kind: StatKind, val label: String, val value: String)

    /** The chosen stats that have a value, in [StatKind] order. */
    fun items(shown: Set<StatKind>, s: StatsSnapshot, t: TokenStats.State): List<Item> =
        StatKind.entries.filter { it in shown }.mapNotNull { kind -> item(kind, s, t) }

    /** The notification's one line, e.g. "CPU 41° · RAM 7.1/10.8 GB · 42 tok/s". */
    fun line(items: List<Item>): String = items.joinToString(" · ") { "${it.label} ${it.value}" }

    fun item(kind: StatKind, s: StatsSnapshot, t: TokenStats.State): Item? {
        val b = s.battery
        return when (kind) {
            StatKind.CPU_TEMP -> s.cpuTempC?.let { Item(kind, "CPU", degrees(it)) }
            StatKind.GPU_TEMP -> s.gpuTempC?.let { Item(kind, "GPU", degrees(it)) }
            StatKind.GPU_LOAD -> s.gpuLoadPercent?.let { Item(kind, "GPU", "$it%") }
            StatKind.CPU_SPEED -> s.cpuGhz?.let { Item(kind, "Clock", "%.1f GHz".format(Locale.US, it)) }
            StatKind.BORI_CPU -> s.boriCpuPercent?.let { Item(kind, "Bori CPU", "%.0f%%".format(Locale.US, it)) }
            StatKind.RAM -> if (s.ramUsedBytes != null && s.ramTotalBytes != null) {
                Item(kind, "RAM", "%.1f/%.1f GB".format(Locale.US, s.ramUsedBytes / GB, s.ramTotalBytes / GB))
            } else null
            StatKind.BORI_RAM -> s.boriRamBytes?.let { Item(kind, "Bori RAM", bytes(it)) }
            StatKind.BATTERY_TEMP -> b?.takeIf { it.batteryPercent >= 0 }?.let { Item(kind, "Battery", degrees(it.batteryTempC)) }
            StatKind.BATTERY -> b?.takeIf { it.batteryPercent >= 0 }
                ?.let { Item(kind, "Charge", "${it.batteryPercent}%" + if (it.charging) " ⚡" else "") }
            StatKind.POWER -> s.powerWatts?.let { w ->
                Item(kind, if (b?.charging == true) "In" else "Draw", "%.1f W".format(Locale.US, w))
            }
            StatKind.THERMAL -> b?.takeIf { it.thermalStatus >= 0 }?.let { Item(kind, "Heat", it.thermalName) }
            StatKind.TOKEN_SPEED -> t.tokensPerSecond?.let {
                Item(kind, if (t.live) "≈" else "Speed", "%.0f tok/s".format(Locale.US, it))
            }
            StatKind.TOKENS -> if (t.any) {
                Item(kind, "Tokens", buildString {
                    append(count(t.outputTokens)).append(" out")
                    t.contextTokens?.let { append(" · ctx ").append(count(it)) }
                })
            } else null
        }
    }

    private const val GB = 1024.0 * 1024 * 1024

    fun degrees(c: Float): String = "%.0f°".format(Locale.US, c)

    fun bytes(b: Long): String = if (b >= GB) "%.1f GB".format(Locale.US, b / GB) else "%.0f MB".format(Locale.US, b / (1024.0 * 1024))

    fun count(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(Locale.US, n / 1e6)
        n >= 1_000 -> "%.1fk".format(Locale.US, n / 1e3)
        else -> n.toString()
    }
}
