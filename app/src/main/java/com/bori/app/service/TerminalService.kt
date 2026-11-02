package com.bori.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

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
            context.stopService(Intent(context, TerminalService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        StatusNotifier.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, StatusNotifier.NOTIFICATION_ID, StatusNotifier.build(this), type)
        StatusNotifier.hold(TAG)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        StatusNotifier.release(TAG)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
