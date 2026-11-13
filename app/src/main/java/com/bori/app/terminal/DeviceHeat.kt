package com.bori.app.terminal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * The phone's heat and charge, for the coding agents in Linux (`bori-device`) and Bori Code's own
 * hot-phone alert. Long agent jobs keep the CPU busy for many minutes; the agents are told to check
 * this before and during them.
 */
object DeviceHeat {
    /** Android's thermal states (PowerManager.THERMAL_STATUS_*), in order. */
    private val THERMAL_NAMES = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

    /** PowerManager.THERMAL_STATUS_SEVERE: Android is throttling hard; time to pause a long job. */
    const val SEVERE = 3

    data class Reading(
        val batteryPercent: Int,
        val charging: Boolean,
        val batteryTempC: Float,
        /** PowerManager.THERMAL_STATUS_*, or -1 before Android 10. */
        val thermalStatus: Int,
        /** getThermalHeadroom(30 s): 1.0 is where throttling starts; null before Android 11 or unknown. */
        val headroom: Float?,
    ) {
        val thermalName: String get() = THERMAL_NAMES.getOrNull(thermalStatus) ?: "unknown"

        /** Hot enough to pause a long job: Android's "severe" or worse, or a battery at 45 °C. */
        val hot: Boolean get() = thermalStatus >= SEVERE || batteryTempC >= 45f

        /** The `bori-device` file: plain `key=value` lines the guest script sources. */
        fun toShell(nowSeconds: Long): String = buildString {
            append("battery_percent=").append(batteryPercent).append('\n')
            append("charging=").append(if (charging) "yes" else "no").append('\n')
            append("battery_temp_c=").append("%.1f".format(java.util.Locale.US, batteryTempC)).append('\n')
            append("thermal=").append(thermalName).append('\n')
            append("headroom=").append(headroom?.let { "%.2f".format(java.util.Locale.US, it) } ?: "").append('\n')
            append("updated=").append(nowSeconds).append('\n')
        }
    }

    fun read(context: Context): Reading {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val power = context.getSystemService(PowerManager::class.java)
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus ?: -1 else -1
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { power?.getThermalHeadroom(30) }.getOrNull()?.takeIf { !it.isNaN() }
        } else null
        return Reading(if (level >= 0) level * 100 / scale else -1, plugged, temp, status, headroom)
    }
}
