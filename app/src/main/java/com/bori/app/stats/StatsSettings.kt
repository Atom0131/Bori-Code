package com.bori.app.stats

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One stat the user can show in the overlay and the notification. [label] is for Settings. */
enum class StatKind(val label: String) {
    CPU_TEMP("CPU temperature"),
    GPU_TEMP("GPU temperature"),
    GPU_LOAD("GPU load"),
    CPU_SPEED("CPU speed"),
    BORI_CPU("Bori + agents CPU"),
    RAM("Phone RAM"),
    BORI_RAM("Bori + agents RAM"),
    BATTERY_TEMP("Battery temperature"),
    BATTERY("Battery charge"),
    POWER("Power draw"),
    THERMAL("Android heat level"),
    TOKEN_SPEED("Tokens per second"),
    TOKENS("Tokens used"),
}

/** What the user chose to show, and where. */
data class StatsSettings(
    val shown: Set<StatKind> = DEFAULT_SHOWN,
    val inNotification: Boolean = false,
    val overlay: Boolean = false,
    /** Float the overlay over other apps (full build, with "Display over other apps" allowed). */
    val overApps: Boolean = false,
    /** The overlay's top-left corner as a fraction of the window, so it survives rotation and folding. */
    val x: Float = 0.04f,
    val y: Float = 0.12f,
) {
    val anyShown: Boolean get() = (inNotification || overlay) && shown.isNotEmpty()

    companion object {
        val DEFAULT_SHOWN = setOf(StatKind.CPU_TEMP, StatKind.BATTERY_TEMP, StatKind.RAM, StatKind.TOKEN_SPEED)
    }
}

/**
 * The stats settings, in SharedPreferences rather than the DataStore so the status notification and
 * the overlay service (plain objects and services, no injection) can read them synchronously.
 */
object StatsPrefs {
    private const val FILE = "stats"
    private const val SHOWN = "shown"
    private const val IN_NOTIFICATION = "in_notification"
    private const val OVERLAY = "overlay"
    private const val OVER_APPS = "over_apps"
    private const val X = "x"
    private const val Y = "y"

    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(StatsSettings())
    val settings: StateFlow<StatsSettings> = _settings.asStateFlow()

    /** Loads the saved settings once; every entry point calls it, so the order doesn't matter. */
    @Synchronized
    fun init(context: Context): StatsPrefs {
        if (prefs != null) return this
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        _settings.value = StatsSettings(
            shown = p.getStringSet(SHOWN, null)
                ?.mapNotNull { name -> StatKind.entries.firstOrNull { it.name == name } }?.toSet()
                ?: StatsSettings.DEFAULT_SHOWN,
            inNotification = p.getBoolean(IN_NOTIFICATION, false),
            overlay = p.getBoolean(OVERLAY, false),
            overApps = p.getBoolean(OVER_APPS, false),
            x = p.getFloat(X, 0.04f),
            y = p.getFloat(Y, 0.12f),
        )
        return this
    }

    fun update(change: (StatsSettings) -> StatsSettings) {
        val next = synchronized(this) { change(_settings.value).also { _settings.value = it } }
        prefs?.edit()
            ?.putStringSet(SHOWN, next.shown.map { it.name }.toSet())
            ?.putBoolean(IN_NOTIFICATION, next.inNotification)
            ?.putBoolean(OVERLAY, next.overlay)
            ?.putBoolean(OVER_APPS, next.overApps)
            ?.putFloat(X, next.x)
            ?.putFloat(Y, next.y)
            ?.apply()
    }
}
