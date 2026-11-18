package com.bori.app.terminal

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Device & heat: how Bori Code watches the phone's heat while coding agents work. */
data class HeatSettings(
    /** Tell the agents in Linux the phone's heat and battery (`bori-device`), so they pace long jobs. */
    val tellAgents: Boolean = true,
    /** A notification when the phone is hot while an agent works. */
    val alerts: Boolean = true,
    /** Battery temperature (°C) that counts as hot. Android's own "severe" heat level always does. */
    val batteryC: Int = 45,
    /** Minutes between two hot-phone notifications. */
    val alertMinutes: Int = 15,
    /** Turn auto-enter off in every tab when the phone gets hot, so an agent waits for you. */
    val pauseAutoEnter: Boolean = false,
) {
    companion object {
        val BATTERY_CHOICES = listOf(40, 42, 45, 48)
        val MINUTE_CHOICES = listOf(5, 15, 30, 60)
    }
}

/** The heat settings in SharedPreferences, so the terminal manager (no injection here) reads them at once. */
object HeatPrefs {
    private const val FILE = "heat"
    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(HeatSettings())
    val settings: StateFlow<HeatSettings> = _settings.asStateFlow()

    @Synchronized
    fun init(context: Context): HeatPrefs {
        if (prefs != null) return this
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val d = HeatSettings()
        _settings.value = HeatSettings(
            tellAgents = p.getBoolean("tell_agents", d.tellAgents),
            alerts = p.getBoolean("alerts", d.alerts),
            batteryC = p.getInt("battery_c", d.batteryC),
            alertMinutes = p.getInt("alert_minutes", d.alertMinutes),
            pauseAutoEnter = p.getBoolean("pause_auto_enter", d.pauseAutoEnter),
        )
        return this
    }

    fun update(change: (HeatSettings) -> HeatSettings) {
        val next = synchronized(this) { change(_settings.value).also { _settings.value = it } }
        prefs?.edit()
            ?.putBoolean("tell_agents", next.tellAgents)
            ?.putBoolean("alerts", next.alerts)
            ?.putInt("battery_c", next.batteryC)
            ?.putInt("alert_minutes", next.alertMinutes)
            ?.putBoolean("pause_auto_enter", next.pauseAutoEnter)
            ?.apply()
    }
}
