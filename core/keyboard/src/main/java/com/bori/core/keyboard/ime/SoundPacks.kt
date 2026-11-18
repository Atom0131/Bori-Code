package com.bori.core.keyboard.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.bori.core.keyboard.layout.Key
import com.bori.core.keyboard.layout.KeyCode
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Key-sound packs (Stage 3.6): short WAVs made by tools/build_sounds.py in
 * assets/sounds/<pack>/key|space|enter|delete.wav, played with SoundPool. Each key press gets a
 * tiny random pitch change so fast typing doesn't sound like a machine gun. Piano mode plays a
 * note per letter (a pentatonic scale, so any typing sounds musical) from three recorded pitches.
 * Files are copied out of the APK once (SoundPool needs a real file; packaging may compress
 * assets). Loading is lazy: a pack loads the first time it is chosen.
 */
class SoundPacks(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var pool: SoundPool? = null
    private var loadedPack: String? = null
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private val rnd = java.util.Random()

    /** Plays [key]'s sound from [pack] at [volume] (0–1). False if the pack isn't ready yet. */
    fun play(pack: String, key: Key, volume: Float): Boolean {
        // Follow silent and vibrate modes.
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return true
        load(pack)
        val p = pool ?: return false
        if (pack == PIANO) return playPiano(p, key, volume)
        val name = when (key.code) {
            KeyCode.Space -> "space"
            KeyCode.Enter -> "enter"
            KeyCode.Delete -> "delete"
            else -> "key"
        }
        val id = ids[name] ?: ids["key"] ?: return false
        if (id !in ready) return false
        val rate = if (name == "key") 0.96f + rnd.nextFloat() * 0.08f else 1f
        p.play(id, volume, volume, 1, 0, rate)
        return true
    }

    private fun playPiano(p: SoundPool, key: Key, volume: Float): Boolean {
        val notes = when (key.code) {
            KeyCode.Enter -> intArrayOf(12, 16, 19)
            KeyCode.Space -> intArrayOf(0)
            KeyCode.Delete -> intArrayOf(-5)
            else -> intArrayOf(noteFor(key.label.lowercase().firstOrNull() ?: ' '))
        }
        var any = false
        for (semi in notes) {
            // Nearest recorded C (C4, C5, C6), then pitch it by at most half an octave.
            val base = (semi / 12f).roundToInt().coerceIn(0, 2)
            val id = ids["piano_c${4 + base}"] ?: continue
            if (id !in ready) continue
            val rate = 2f.pow((semi - 12 * base) / 12f).coerceIn(0.5f, 2f)
            p.play(id, volume * 0.8f, volume * 0.8f, 1, 0, rate)
            any = true
        }
        return any
    }

    private fun load(pack: String) {
        if (pack == loadedPack) return
        release()
        loadedPack = pack
        val names = if (pack == PIANO) listOf("piano_c4", "piano_c5", "piano_c6") else listOf("key", "space", "enter", "delete")
        val sp = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        sp.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
        pool = sp
        for (n in names) {
            val f = copyOut(pack, n) ?: continue
            ids[n] = sp.load(f.path, 1)
        }
    }

    /** When this APK was installed; versionCode stays 1 between builds, so it can't tell them apart. */
    private val installStamp: Long by lazy {
        runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
    }

    /** assets/sounds/<pack>/<name>.wav → filesDir/sounds/<pack>/<name>.wav (again after each install). */
    private fun copyOut(pack: String, name: String): File? = runCatching {
        val dir = File(app.filesDir, "sounds/$pack").apply { mkdirs() }
        val f = File(dir, "$name.wav")
        val stamp = File(dir, ".$name.$installStamp")
        if (!f.isFile || !stamp.isFile) {
            app.assets.open("sounds/$pack/$name.wav").use { input -> f.outputStream().use { input.copyTo(it) } }
            dir.listFiles()?.filter { it.name.startsWith(".$name.") }?.forEach { it.delete() }
            stamp.createNewFile()
        }
        f
    }.onFailure { DiagLog.event("sound $pack/$name failed: $it") }.getOrNull()

    fun release() {
        pool?.release()
        pool = null
        loadedPack = null
        ids.clear()
        ready.clear()
    }

    companion object {
        const val SYSTEM = "system"
        const val PIANO = "piano"

        /** Pack id to name, in Settings order. "system" = the phone's own key click. */
        val PACKS = listOf(
            SYSTEM to "Phone's own",
            "crisp" to "Crisp tap",
            "typewriter" to "Typewriter",
            "thocky" to "Thocky",
            "clacky" to "Clacky",
            "creamy" to "Creamy",
            "bubble" to "Bubble",
            "water" to "Water drop",
            "wood" to "Wood block",
            "8bit" to "8-bit",
            PIANO to "Piano",
        )

        /** Major pentatonic over three octaves, semitones above C4. */
        private val SCALE = intArrayOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24, 26, 28, 31, 33, 36)
        private const val ROW_TOP = "qwertyuiop"
        private const val ROW_MID = "asdfghjklñ"
        private const val ROW_LOW = "zxcvbnm"
        private const val ROW_NUM = "1234567890"

        /** Low notes on the bottom row, rising to the right and up the rows. */
        fun noteFor(c: Char): Int {
            ROW_LOW.indexOf(c).takeIf { it >= 0 }?.let { return SCALE[it] }
            ROW_MID.indexOf(c).takeIf { it >= 0 }?.let { return SCALE[(3 + it).coerceAtMost(SCALE.lastIndex)] }
            ROW_TOP.indexOf(c).takeIf { it >= 0 }?.let { return SCALE[(5 + it).coerceAtMost(SCALE.lastIndex)] }
            ROW_NUM.indexOf(c).takeIf { it >= 0 }?.let { return SCALE[(6 + it).coerceAtMost(SCALE.lastIndex)] }
            return SCALE[7]
        }
    }
}
