package com.bori.app.ui.terminal

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * A short buzz per key press, from Bori Keyboard's Feedback. The system's KEYBOARD_TAP plays as a
 * 120 ms buzz on Samsung phones (Fold, 2026-10-27: 464 presses came to 38 minutes of vibration),
 * which feels mushy; this is a 6–32 ms pulse whose strength the user picks.
 */
class KeyHaptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var effectStrength = -1
    private var effect: VibrationEffect? = null

    /** One buzz at [strength] (1–100); 0 is off. */
    fun tap(strength: Int) {
        if (strength <= 0) return
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        if (strength != effectStrength) {
            val s = strength.coerceIn(1, 100) / 100f
            val ms = (MIN_MS + s * (MAX_MS - MIN_MS)).toLong()
            effect = if (v.hasAmplitudeControl()) {
                VibrationEffect.createOneShot(ms, (MIN_AMPLITUDE + s * (255 - MIN_AMPLITUDE)).toInt().coerceIn(1, 255))
            } else {
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            effectStrength = strength
        }
        effect?.let { v.vibrate(it) }
    }

    private companion object {
        const val MIN_MS = 6f
        const val MAX_MS = 32f
        const val MIN_AMPLITUDE = 30f
    }
}
