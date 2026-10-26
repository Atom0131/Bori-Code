package com.bori.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bori.app.MainActivity
import com.bori.core.linux.CodingCli
import com.bori.core.linux.DistroCatalog
import com.bori.core.linux.DistroInstaller
import com.bori.core.linux.InstallState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps Bori Code in the foreground while [DistroInstaller] downloads, unpacks or sets up a distro
 * or a coding CLI, so leaving the app doesn't stop the job. Shows the job's progress and stops
 * itself when the job ends. BoriApp starts it when a job starts.
 */
@AndroidEntryPoint
class LinuxInstallService : Service() {

    @Inject lateinit var installer: DistroInstaller

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watching = false

    companion object {
        private const val CHANNEL_ID = "linux_install_channel"
        private const val NOTIFICATION_ID = 1003

        fun start(context: Context) {
            val intent = Intent(context, LinuxInstallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(installer.state.value), type)
        if (!watching) {
            watching = true
            scope.launch {
                installer.state.collect { state ->
                    if (state is InstallState.Running) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    } else {
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(state: InstallState): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val running = state as? InstallState.Running
        val subject = running?.cliId?.let { CodingCli.get(it)?.title }
            ?: running?.distroId?.let { id -> DistroCatalog.all.firstOrNull { it.id == id }?.name }
            ?: "Linux"
        val step = running?.step
        val text = when (step) {
            is InstallState.Step.Downloading -> "Downloading $subject"
            InstallState.Step.Verifying -> "Checking the download"
            is InstallState.Step.Extracting -> "Unpacking $subject (${step.files} files)"
            InstallState.Step.Packages -> "Installing $subject's packages"
            InstallState.Step.Deleting -> "Removing $subject"
            InstallState.Step.CliInstalling -> "Installing $subject"
            InstallState.Step.CliRemoving -> "Removing $subject"
            null -> "Working…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bori - Code · Linux environment")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (step is InstallState.Step.Downloading && step.total > 0) {
                    setProgress(1000, (step.bytes * 1000 / step.total).toInt(), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Linux setup", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress while a Linux distro or coding CLI installs" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
