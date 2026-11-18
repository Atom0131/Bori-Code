package com.bori.core.keyboard.learn

import android.content.Context
import com.bori.core.keyboard.predict.Composer
import com.bori.core.keyboard.predict.KeyProximity
import com.bori.core.keyboard.view.Posture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Touch heat map: where this user's taps really land on each key, kept separately for each
 * way of holding the phone ([Posture]). Points are offsets from the key centre in key units
 * (1.0 = one key over), so they survive key-height and split changes.
 *
 * Per key it keeps running sums (mean + spread, slowly forgetting old habits) and the last
 * [RECENT] points for the Settings picture. The mean becomes a per-user target shift that the
 * corrector uses (see [applyTo]). Stays in app-private storage; the IME only records when
 * learning is allowed (not incognito, not passwords).
 *
 * Main thread only (IME and Settings share the process).
 */
class TouchMap private constructor(private val file: File) {

    class KeyStats {
        var n = 0f
        var sx = 0f
        var sy = 0f
        var sxx = 0f
        var syy = 0f
        /** Recent points as dx, dy pairs, oldest first. */
        val recent = ArrayList<Float>()

        val meanX: Float get() = if (n > 0f) sx / n else 0f
        val meanY: Float get() = if (n > 0f) sy / n else 0f
        val spreadX: Float get() = if (n > 1f) kotlin.math.sqrt((sxx / n - meanX * meanX).coerceAtLeast(0f)) else 0f
        val spreadY: Float get() = if (n > 1f) kotlin.math.sqrt((syy / n - meanY * meanY).coerceAtLeast(0f)) else 0f

        /** Mean shrunk towards 0 while there are few taps, so a handful can't move a key. */
        fun shiftX(): Float = (sx / (n + PRIOR)).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        fun shiftY(): Float = (sy / (n + PRIOR)).coerceIn(-MAX_SHIFT, MAX_SHIFT)

        fun add(dx: Float, dy: Float) {
            if (n >= MAX_N) {
                // Forget slowly: old habits fade as new taps come in (new grip, new case).
                val k = (MAX_N - 1f) / n
                n *= k; sx *= k; sy *= k; sxx *= k; syy *= k
            }
            n += 1f; sx += dx; sy += dy; sxx += dx * dx; syy += dy * dy
            recent.add(dx); recent.add(dy)
            while (recent.size > RECENT * 2) { recent.removeAt(0); recent.removeAt(0) }
        }
    }

    private val data = HashMap<Posture, HashMap<Char, KeyStats>>()
    private var unsaved = 0

    /** Bumped on every change, so cached [KeyProximity] shifts know to refresh. */
    var version = 0
        private set

    fun keys(p: Posture): Map<Char, KeyStats> = data[p] ?: emptyMap()
    fun count(p: Posture): Int = keys(p).values.sumOf { it.n.toInt() }
    fun total(): Int = Posture.entries.sumOf { count(it) }

    /**
     * Records the taps of a committed word. [intended] is what the user meant: the word as
     * typed when it was kept, or the picked/corrected word when it has the same length
     * (each tap then belongs to the intended letter's key, which is where misses show up).
     */
    fun record(posture: Posture, composer: Composer, intended: String, prox: KeyProximity): Int {
        if (intended.length != composer.length) return 0
        val map = data.getOrPut(posture) { HashMap() }
        var added = 0
        for (i in intended.indices) {
            val x = composer.x(i)
            val y = composer.y(i)
            if (x.isNaN() || y.isNaN()) continue
            val c = keyFor(intended[i], prox) ?: continue
            val dx = (x - prox.centerX(c)) / prox.keyWidth
            val dy = (y - prox.centerY(c)) / prox.keyHeight
            // Further than this wasn't aiming at that key (a different word, a slip of the hand).
            if (dx * dx + dy * dy > MAX_DIST * MAX_DIST) continue
            map.getOrPut(c) { KeyStats() }.add(dx, dy)
            added++
        }
        if (added > 0) {
            version++
            unsaved += added
            if (unsaved >= SAVE_EVERY) save()
        }
        return added
    }

    private var shiftsFor: Posture? = null
    private var shiftsVersion = -1
    private var shiftsCache: Map<Char, FloatArray> = emptyMap()

    /**
     * Learned per-key target shifts (key units) for [posture], only keys with enough taps.
     * Cached until the map changes, so the keyboard can ask on every touch without allocating.
     */
    fun shifts(posture: Posture): Map<Char, FloatArray> {
        if (posture != shiftsFor || version != shiftsVersion) {
            val m = HashMap<Char, FloatArray>()
            for ((c, s) in keys(posture)) {
                if (s.n >= MIN_TAPS) m[c] = floatArrayOf(s.shiftX(), s.shiftY())
            }
            shiftsCache = m
            shiftsFor = posture
            shiftsVersion = version
        }
        return shiftsCache
    }

    /** Gives [prox] this posture's learned per-key target shifts. */
    fun applyTo(prox: KeyProximity, posture: Posture) {
        prox.setShifts(shifts(posture))
    }

    /** One tap aimed at [c] at ([dx], [dy]) key units from its centre (the calibration screen). */
    fun recordPoint(posture: Posture, c: Char, dx: Float, dy: Float) {
        if (dx * dx + dy * dy > MAX_DIST * MAX_DIST) return
        data.getOrPut(posture) { HashMap() }.getOrPut(c) { KeyStats() }.add(dx, dy)
        version++
        unsaved++
    }

    fun clear(p: Posture?) {
        if (p == null) data.clear() else data.remove(p)
        version++
        save()
    }

    fun saveIfChanged() {
        if (unsaved > 0) save()
    }

    fun save() {
        unsaved = 0
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(toJson().toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    /** Everything as JSON, for saving and for "Copy heat map data". */
    fun toJson(): JSONObject {
        val root = JSONObject().put("v", 1)
        val ps = JSONObject()
        for ((p, keys) in data) {
            val ko = JSONObject()
            for ((c, s) in keys) {
                ko.put(c.toString(), JSONObject().apply {
                    put("n", r3(s.n)); put("sx", r3(s.sx)); put("sy", r3(s.sy))
                    put("sxx", r3(s.sxx)); put("syy", r3(s.syy))
                    put("p", JSONArray().apply { s.recent.forEach { put(r3(it)) } })
                })
            }
            ps.put(p.id, ko)
        }
        return root.put("postures", ps)
    }

    private fun load() {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        runCatching {
            val ps = JSONObject(text).getJSONObject("postures")
            for (p in Posture.entries) {
                val ko = ps.optJSONObject(p.id) ?: continue
                val map = HashMap<Char, KeyStats>()
                for (k in ko.keys()) {
                    val o = ko.getJSONObject(k)
                    map[k.firstOrNull() ?: continue] = KeyStats().apply {
                        n = o.optDouble("n").toFloat(); sx = o.optDouble("sx").toFloat()
                        sy = o.optDouble("sy").toFloat(); sxx = o.optDouble("sxx").toFloat()
                        syy = o.optDouble("syy").toFloat()
                        val a = o.optJSONArray("p")
                        if (a != null) for (i in 0 until a.length()) recent.add(a.optDouble(i).toFloat())
                    }
                }
                data[p] = map
            }
        }
    }

    companion object {
        private const val FILE = "touch_map.json"
        /** Points kept per key for the picture. */
        const val RECENT = 60
        /** Taps per key before the corrector uses its shift. */
        const val MIN_TAPS = 15
        private const val PRIOR = 20f
        private const val MAX_SHIFT = 0.4f
        private const val MAX_N = 500f
        private const val MAX_DIST = 1.5f
        private const val SAVE_EVERY = 25

        @Volatile private var instance: TouchMap? = null

        fun get(context: Context): TouchMap =
            instance ?: synchronized(this) {
                instance ?: TouchMap(File(context.applicationContext.filesDir, FILE)).also {
                    it.load()
                    instance = it
                }
            }

        private fun r3(v: Float): Double = Math.round(v * 1000.0) / 1000.0

        /** The key a letter is typed on: itself, or its base letter (á → a) when it has no key. */
        private fun keyFor(ch: Char, prox: KeyProximity): Char? {
            val c = ch.lowercaseChar()
            if (prox.hasKey(c)) return c
            val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: return null
            return base.takeIf { prox.hasKey(it) }
        }
    }
}
