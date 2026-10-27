package com.bori.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Keeps the process running while Bori is working: a build, an AI reply, or a loaded Gemma model.
 *
 * Android 14+ freezes a backgrounded app with no foreground service, which stalls a build mid-step
 * (and its wall-clock timeouts then fail it). Each user [acquire]s its own [Reason] and [release]s it;
 * the service stops only when none are left, so a chat reply that ends can't drop a running build.
 * While a build or reply runs it also holds a partial wake lock so the CPU keeps going with the
 * screen off.
 */
class ChatForegroundService : Service() {

    enum class Reason { GEMMA, CHAT, BUILD }

    companion object {
        private const val TAG = "KeepAlive"
        const val CHANNEL_ID = "chat_generation_channel"
        const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_CAP_MS = 60 * 60 * 1000L

        private val active = mutableSetOf<Reason>()
        private var buildDetail: String? = null
        /** startForegroundService was called and onStartCommand hasn't run yet. */
        private var starting = false
        @Volatile private var instance: ChatForegroundService? = null

        /** [detail] names what's being built; it shows in the notification. */
        fun acquire(context: Context, reason: Reason, detail: String? = null) {
            synchronized(active) {
                active += reason
                if (reason == Reason.BUILD) buildDetail = detail
            }
            val running = instance
            if (running != null) {
                running.refresh()
                return
            }
            if (synchronized(active) { starting.also { starting = true } }) return
            try {
                context.startForegroundService(Intent(context, ChatForegroundService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException when started from the background.
                Log.w(TAG, "Couldn't start keep-alive for $reason: ${e.message}")
                synchronized(active) { starting = false }
            }
        }

        fun release(context: Context, reason: Reason) {
            val (empty, pending) = synchronized(active) {
                active -= reason
                if (reason == Reason.BUILD) buildDetail = null
                active.isEmpty() to starting
            }
            // Stopping it before onStartCommand calls startForeground crashes the app
            // (ForegroundServiceDidNotStartInTimeException); onStartCommand stops it itself instead.
            if (empty && pending) return
            // stopService, never startService: starting a service from the background throws
            // BackgroundServiceStartNotAllowedException (BoriApp releases GEMMA on every cold start).
            if (empty) {
                // A later acquire must start a new service, not update this one as it's destroyed.
                instance = null
                context.stopService(Intent(context, ChatForegroundService::class.java))
            } else {
                instance?.refresh()
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (synchronized(active) { starting = false; active.isEmpty() }) {
            // Every reason was released before the service got here.
            instance = null
            stopSelf()
        } else {
            updateWakeLock()
        }
        return START_NOT_STICKY
    }

    /** dataSync services are capped at 6 h a day on Android 15+; stop in time instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground time limit reached; stopping keep-alive")
        synchronized(active) { active.clear() }
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        updateWakeLock()
    }

    private fun updateWakeLock() {
        val needed = synchronized(active) { Reason.BUILD in active || Reason.CHAT in active }
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bori:build")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (needed && !lock.isHeld) lock.acquire(WAKE_LOCK_CAP_MS)
        else if (!needed && lock.isHeld) lock.release()
    }

    private fun buildNotification(): Notification {
        val text = synchronized(active) {
            when {
                Reason.BUILD in active -> buildDetail?.let { "Building $it…" } ?: "Building…"
                Reason.CHAT in active -> "AI is working…"
                else -> "Gemma model is active"
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bori - Code")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "AI Generation", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps builds and AI replies running in the background"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
