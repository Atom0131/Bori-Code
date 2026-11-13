package com.bori.app.stats

import android.content.Context
import com.bori.app.service.StatusNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reads the stats every [INTERVAL_MS] while something shows them (an overlay, or the notification
 * with stats on) and stops when nothing does, so hidden stats cost no battery.
 */
object StatsSampler {
    const val INTERVAL_MS = 2_000L
    private const val NOTIFY_MS = 5_000L
    const val NOTIFICATION = "notification"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val demand = mutableSetOf<String>()
    private var job: Job? = null
    private var reader: DeviceStats? = null

    private val _snapshot = MutableStateFlow(StatsSnapshot())
    val snapshot: StateFlow<StatsSnapshot> = _snapshot.asStateFlow()

    /** [who] starts or stops needing stats ("overlay", "floating", [NOTIFICATION]). */
    fun need(context: Context, who: String, on: Boolean) {
        val app = context.applicationContext
        StatsPrefs.init(app)
        synchronized(demand) {
            if (on) demand += who else demand -= who
            if (demand.isEmpty()) {
                job?.cancel()
                job = null
            } else if (job?.isActive != true) {
                val stats = reader ?: DeviceStats(app).also { reader = it }
                job = scope.launch { loop(app, stats) }
            }
        }
    }

    /** The notification shows stats while a service shows it and the user turned them on. */
    fun syncNotification(context: Context) {
        val on = StatsPrefs.init(context).settings.value.let { it.inNotification && it.shown.isNotEmpty() } &&
            StatusNotifier.isShowing()
        need(context, NOTIFICATION, on)
    }

    /** The notification's stats line now, or null when stats are off or none can be read. */
    fun notificationLine(context: Context): String? {
        val settings = StatsPrefs.init(context).settings.value
        if (!settings.inNotification) return null
        return StatsFormat.items(settings.shown, _snapshot.value, TokenStats.state.value)
            .takeIf { it.isNotEmpty() }?.let(StatsFormat::line)
    }

    /** A one-off reading, for Settings' "available on this phone" check. */
    fun readOnce(context: Context): StatsSnapshot {
        val stats = synchronized(demand) { reader ?: DeviceStats(context.applicationContext).also { reader = it } }
        return synchronized(stats) { stats.read() }.also { _snapshot.value = it }
    }

    private suspend fun loop(context: Context, stats: DeviceStats) {
        var lastNotify = 0L
        while (true) {
            _snapshot.value = synchronized(stats) { stats.read() }
            TokenStats.pollClaude()
            val now = System.currentTimeMillis()
            if (NOTIFICATION in synchronized(demand) { demand.toSet() } && now - lastNotify >= NOTIFY_MS) {
                lastNotify = now
                StatusNotifier.refresh(context)
            }
            delay(INTERVAL_MS)
        }
    }
}
