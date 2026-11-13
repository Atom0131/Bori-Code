package com.bori.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bori.app.MainActivity
import com.bori.app.flavor.FloatingStats
import com.bori.app.stats.StatsPrefs
import com.bori.app.stats.StatsSampler
import com.bori.app.ui.common.StatsPanel
import com.bori.app.ui.common.rememberStatsItems
import kotlin.math.roundToInt

/**
 * The stats panel floating over other apps (full build, with "Display over other apps"). A
 * foreground service, so Android keeps it while Bori Code is in the background; it shows Bori's one
 * status notification, which then carries the stats too and a "Hide stats" action.
 */
class StatsOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "StatsOverlay"
        const val ACTION_HIDE = "com.bori.app.stats.HIDE"
        private const val FLOATING = "floating"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, StatsOverlayService::class.java))
            }.onFailure { Log.w(TAG, "Couldn't start the floating stats: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StatsOverlayService::class.java))
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        StatsPrefs.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        StatusNotifier.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, StatusNotifier.NOTIFICATION_ID, StatusNotifier.build(this), type)
        if (intent?.action == ACTION_HIDE) {
            StatsPrefs.update { it.copy(overApps = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!FloatingStats.canDraw(this)) {
            // The permission was taken back in Android's settings.
            StatsPrefs.update { it.copy(overApps = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) show()
        FloatingStats._running.value = true
        StatusNotifier.hold(this, TAG)
        StatsSampler.need(this, FLOATING, true)
        StatusNotifier.refresh(this)
        return START_NOT_STICKY
    }

    private fun show() {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = screen()
        val s = StatsPrefs.settings.value
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (s.x * bounds.width()).roundToInt()
            y = (s.y * bounds.height()).roundToInt()
        }
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@StatsOverlayService)
            setViewTreeSavedStateRegistryOwner(this@StatsOverlayService)
            setContent {
                // Over another app there's no Crystal field behind it: plain glass-coloured Material.
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                    var expanded by remember { mutableStateOf(false) }
                    Box(
                        Modifier.pointerInput(Unit) {
                            detectDragGestures(onDragEnd = ::savePosition) { change, drag ->
                                change.consume()
                                moveBy(drag.x, drag.y)
                            }
                        },
                    ) {
                        StatsPanel(
                            rememberStatsItems(),
                            expanded,
                            onTap = { expanded = !expanded },
                            onLongPress = ::openApp,
                        )
                    }
                }
            }
        }
        runCatching { wm.addView(composeView, lp) }
            .onFailure {
                Log.w(TAG, "Couldn't add the floating stats", it)
                stopSelf()
                return
            }
        view = composeView
        params = lp
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        val lp = params ?: return
        val bounds = screen()
        lp.x = (lp.x + dx.roundToInt()).coerceIn(0, (bounds.width() - v.width).coerceAtLeast(0))
        lp.y = (lp.y + dy.roundToInt()).coerceIn(0, (bounds.height() - v.height).coerceAtLeast(0))
        runCatching { getSystemService(WindowManager::class.java).updateViewLayout(v, lp) }
    }

    private fun savePosition() {
        val lp = params ?: return
        val bounds = screen()
        StatsPrefs.update {
            it.copy(
                x = (lp.x.toFloat() / bounds.width()).coerceIn(0f, 1f),
                y = (lp.y.toFloat() / bounds.height()).coerceIn(0f, 1f),
            )
        }
    }

    /** The screen's size: window metrics on Android 11+, display metrics before. */
    private fun screen(): android.graphics.Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        } else {
            resources.displayMetrics.let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }
        }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        view?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        view = null
        FloatingStats._running.value = false
        StatsSampler.need(this, FLOATING, false)
        StatusNotifier.release(this, TAG)
        // Another service may still show the notification; it drops the stats line on its next refresh.
        StatusNotifier.refresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
