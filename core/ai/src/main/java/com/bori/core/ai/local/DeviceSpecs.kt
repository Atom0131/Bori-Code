package com.bori.core.ai.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import kotlin.math.ceil

/**
 * The phone facts that decide which on-device model will run well. Either read from
 * the phone by [scan] or typed in by the user ([manual] = true), e.g. to plan for
 * another phone or correct a scan.
 */
data class DeviceSpecs(
    val name: String,
    val ramGb: Double,
    val freeStorageGb: Double,
    val chip: String? = null,
    val cpuCores: Int? = null,
    val isLowRam: Boolean = false,
    val manual: Boolean = false,
) {
    companion object {
        /** Marketed RAM sizes; the OS reports a bit less than the box says (11.2 GB on a 12 GB phone). */
        private val MARKETED_RAM_GB = listOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32)

        private const val GB = 1024.0 * 1024 * 1024

        /** Reads this phone's specs. Needs no permissions. */
        fun scan(context: Context): DeviceSpecs {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val rawRam = mem.totalMem / GB
            val ramGb = MARKETED_RAM_GB.firstOrNull { it >= rawRam - 0.05 }?.toDouble() ?: ceil(rawRam)

            // Models download into the app's external files dir, so measure that volume
            val storageDir = context.getExternalFilesDir(null) ?: context.filesDir
            val freeGb = StatFs(storageDir.path).availableBytes / GB

            val chip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                    .filter { it.isNotBlank() && it != Build.UNKNOWN }
                    .joinToString(" ")
                    .ifBlank { null }
            } else null

            return DeviceSpecs(
                name = friendlyName(context),
                ramGb = ramGb,
                freeStorageGb = freeGb,
                chip = chip,
                cpuCores = Runtime.getRuntime().availableProcessors(),
                isLowRam = am.isLowRamDevice,
            )
        }

        /** The name the user gave the phone (e.g. "Galaxy Z Fold7"), else maker + model code. */
        private fun friendlyName(context: Context): String {
            val deviceName = runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()
            val model = Build.MODEL.orEmpty()
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val fallback = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
            return deviceName?.takeIf { it.isNotBlank() } ?: fallback
        }
    }
}
