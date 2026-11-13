package com.bori.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.bori.app.terminal.ProcessReaper

/**
 * Keeps Bori Code in the foreground while terminal sessions exist, so Android doesn't kill the
 * shells (and the CLI agents running in them) when the user switches apps. Started and stopped
 * by TerminalSessionManager as the first tab opens and the last one closes. It shows Bori's status
 * notification ([StatusNotifier]): the agents in the tabs and what they're doing.
 */
class TerminalService : Service() {

    companion object {
        private const val TAG = "TerminalService"

        fun start(context: Context) {
            val intent = Intent(context, TerminalService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            keepAwake(context, false)
            context.stopService(Intent(context, TerminalService::class.java))
        }

        private var agentLock: PowerManager.WakeLock? = null

        /** Whether the CPU is being kept awake for a working agent ([keepAwake]). */
        @Volatile var awake = false
            private set

        /**
         * Keeps the CPU running while a terminal agent works, so it doesn't stall when the screen goes off
         * or the user switches apps. Each call while [on] re-arms the [AGENT_LOCK_CAP_MS] cap, so the lock
         * lapses on its own if nothing calls again. The screen stays free to turn off.
         */
        @Synchronized
        fun keepAwake(context: Context, on: Boolean) {
            val lock = agentLock ?: context.applicationContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bori:agent")
                .apply { setReferenceCounted(false) }
                .also { agentLock = it }
            if (on) lock.acquire(AGENT_LOCK_CAP_MS) else if (lock.isHeld) lock.release()
            awake = on
        }

        private const val AGENT_LOCK_CAP_MS = 30 * 60_000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        StatusNotifier.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, StatusNotifier.NOTIFICATION_ID, StatusNotifier.build(this), type)
        StatusNotifier.hold(this, TAG)
        // Programs left on a dead PTY by an earlier run (see ProcessReaper).
        ProcessReaper.sweepOrphans()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keepAwake(this, false)
        StatusNotifier.release(this, TAG)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
