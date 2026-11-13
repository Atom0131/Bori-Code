package com.bori.app.flavor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.bori.app.service.StatsOverlayService
import com.bori.app.stats.StatsPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Full build: the stats panel can float over other apps (StatsOverlayService), with Android's
 * "Display over other apps" permission. The Play build's copy does nothing.
 */
object FloatingStats {
    const val AVAILABLE = true

    internal val _running = MutableStateFlow(false)
    /** The panel is floating over other apps now, so the in-app one stays hidden. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Android's page for allowing Bori Code to display over other apps. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The notification's "Hide stats" action: turns the floating panel off. */
    fun hideIntent(context: Context): android.app.PendingIntent? =
        if (!_running.value) null else android.app.PendingIntent.getService(
            context, 0,
            Intent(context, StatsOverlayService::class.java).setAction(StatsOverlayService.ACTION_HIDE),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )

    /** Starts or stops the floating panel to match Settings and the permission. */
    fun sync(context: Context) {
        val s = StatsPrefs.init(context).settings.value
        val want = s.overlay && s.overApps && s.shown.isNotEmpty() && canDraw(context)
        if (want && !_running.value) StatsOverlayService.start(context)
        else if (!want && _running.value) StatsOverlayService.stop(context)
    }
}
