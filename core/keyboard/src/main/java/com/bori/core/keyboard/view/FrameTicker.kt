package com.bori.core.keyboard.view

import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import com.bori.core.keyboard.ime.DiagLog

/**
 * Smooth animation frames for [view] (LED lighting, background effects).
 *
 * - Redraws on the display's vsync (Choreographer), every vsync by default, so the animation runs
 *   at the screen's own refresh rate (120 fps on the Fold's 120 Hz screens; user chose smoothness
 *   over battery, Session 43). A fixed postInvalidateDelayed timer drifted against the refresh
 *   (Session 42). [minIntervalMs] > 0 caps it (for a battery-saver option later).
 * - [frameTimeMs] is the vsync time of the frame being drawn. Animations must use it instead of
 *   the clock at draw time: the draw starts a little earlier or later each frame, so a wall-clock
 *   step wobbles (8, 9, 7 ms…) and a moving rainbow looks choppy even at 120 fps (Session 44).
 * - While animating it asks for a high frame rate (Android 15+ View.setRequestedFrameRate,
 *   category HIGH, called by reflection so older SDKs still compile). The keyboard window isn't
 *   the app in front, so without a vote the system may run the screen at 60 Hz or lower.
 * - Every 5 s of animation it logs the measured fps, the display's rate and slow frames to the
 *   diagnostics log (Settings → Help → Copy diagnostics), to tell "the screen ran at 60 Hz" from
 *   "frames were dropped".
 */
class FrameTicker(
    private val view: View,
    private val name: String,
    private val minIntervalMs: Long = 0L,
) : Choreographer.FrameCallback {
    private var posted = false
    private var lastNanos = 0L
    private var highRate = false

    /** Vsync time (uptime ms, fractional) of the frame being drawn; the clock when not ticking. */
    var frameTimeMs = 0.0
        private set

    // Measurement.
    private var windowStart = 0L
    private var frames = 0
    private var slow = 0
    private var prevFrame = 0L

    /** The time to animate with: the vsync time if a frame is being drawn, else the clock. */
    fun now(): Double {
        val clock = SystemClock.uptimeMillis().toDouble()
        return if (frameTimeMs > 0.0 && clock - frameTimeMs < 40.0) frameTimeMs else clock
    }

    fun request() {
        if (posted) return
        posted = true
        if (!highRate) setHighRate(true)
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun cancel() {
        if (posted) {
            posted = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
        if (highRate) setHighRate(false)
        frameTimeMs = 0.0
        prevFrame = 0L
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (!view.isAttachedToWindow) {
            if (highRate) setHighRate(false)
            return
        }
        if (minIntervalMs > 0L && frameTimeNanos - lastNanos < (minIntervalMs - 2) * 1_000_000L) {
            request()
            return
        }
        lastNanos = frameTimeNanos
        frameTimeMs = frameTimeNanos / 1_000_000.0
        measure(frameTimeNanos)
        view.invalidate()
    }

    private fun measure(t: Long) {
        val vsyncNs = (1_000_000_000.0 / refreshRate()).toLong()
        if (prevFrame != 0L && t - prevFrame > vsyncNs * 3 / 2) slow++
        if (prevFrame == 0L || t - prevFrame > 500_000_000L) {
            // First frame or after a pause: start a new window.
            windowStart = t
            frames = 0
            slow = 0
        }
        prevFrame = t
        frames++
        if (t - windowStart >= 5_000_000_000L) {
            val fps = frames * 1e9 / (t - windowStart)
            DiagLog.event(
                "anim $name: %.1f fps, display %.0f Hz, slow frames %d of %d, highRate=%b"
                    .format(fps, refreshRate(), slow, frames, highRate),
            )
            windowStart = t
            frames = 0
            slow = 0
        }
    }

    private fun refreshRate(): Float = view.display?.refreshRate?.takeIf { it > 1f } ?: 60f

    /** Android 15+: vote for a high refresh rate for this view while it animates. */
    private fun setHighRate(on: Boolean) {
        highRate = on
        if (Build.VERSION.SDK_INT < 35) return
        runCatching {
            val cls = View::class.java
            val value = if (on) {
                cls.getField("REQUESTED_FRAME_RATE_CATEGORY_HIGH").getFloat(null)
            } else {
                cls.getField("REQUESTED_FRAME_RATE_CATEGORY_DEFAULT").getFloat(null)
            }
            cls.getMethod("setRequestedFrameRate", Float::class.javaPrimitiveType).invoke(view, value)
        }.onFailure { DiagLog.event("frame rate request failed: $it") }
    }
}
