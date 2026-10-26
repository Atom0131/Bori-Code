package com.bori.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * A lightweight Foreground Service that keeps the process alive while the AI is generating code.
 *
 * Tying the generation to a Foreground Service prevents Android from killing the network
 * connection or suspending the coroutines when the user backgrounds the app (e.g. to reply
 * to a notification).
 */
class ChatForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "chat_generation_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.bori.app.ACTION_STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, ChatForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            // stopService, not startService(ACTION_STOP): starting a service from the background
            // throws BackgroundServiceStartNotAllowedException, and BoriApp calls this on every
            // cold start (Gemma Idle) — including starts with no activity, which crashed the app.
            context.stopService(Intent(context, ChatForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bori - Code")
            .setContentText("Gemma model is active")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "AI Generation"
            val descriptionText = "Keeps AI process alive in background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
