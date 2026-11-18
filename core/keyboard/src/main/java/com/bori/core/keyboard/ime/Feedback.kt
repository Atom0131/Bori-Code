package com.bori.core.keyboard.ime

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.bori.core.keyboard.layout.Key
import com.bori.core.keyboard.layout.KeyCode
import com.bori.core.keyboard.settings.Prefs

/** Key-press vibration and click sound, controlled by the keyboard's own settings. */
class Feedback(context: Context, private val prefs: Prefs) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var effectStrength = -1
    private var effect: VibrationEffect? = null
    private val packs = SoundPacks(context)

    /** Password fields: no sounds (Stage 3 rule); vibration stays. */
    var quiet = false

    fun onPress(key: Key) {
        if (prefs.haptics) vibrate(prefs.vibrationStrength)
        if (prefs.sound && !quiet) playSound(key, prefs.soundPack, prefs.soundVolume)
    }

    /** Plays [key]'s sound in [pack]; also the Settings preview. */
    fun playSound(key: Key, pack: String, volume: Float) {
        // A pack still loading falls back to the phone's click for that press.
        if (pack != SoundPacks.SYSTEM && packs.play(pack, key, volume)) return
        run {
            val fx = when (key.code) {
                KeyCode.Delete -> AudioManager.FX_KEYPRESS_DELETE
                KeyCode.Enter -> AudioManager.FX_KEYPRESS_RETURN
                KeyCode.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(fx, if (pack == SoundPacks.SYSTEM) -1f else volume)
        }
    }

    fun release() = packs.release()

    /** One key buzz at [strength] (1–100); also used by the settings slider as a preview. */
    fun vibrate(strength: Int) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        if (strength != effectStrength) {
            effect = effectFor(v, strength.coerceIn(1, 100))
            effectStrength = strength
        }
        effect?.let { v.vibrate(it) }
    }

    /**
     * A short one-shot: stronger means both a higher amplitude and a slightly longer pulse.
     * The system "tick" effect was too faint on many phones. Without amplitude control only
     * the length changes.
     */
    private fun effectFor(v: Vibrator, strength: Int): VibrationEffect {
        val s = strength / 100f
        val ms = (MIN_MS + s * (MAX_MS - MIN_MS)).toLong()
        return if (v.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(ms, (MIN_AMPLITUDE + s * (255 - MIN_AMPLITUDE)).toInt().coerceIn(1, 255))
        } else {
            VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    private companion object {
        const val MIN_MS = 6f
        const val MAX_MS = 32f
        const val MIN_AMPLITUDE = 30f
    }
}
