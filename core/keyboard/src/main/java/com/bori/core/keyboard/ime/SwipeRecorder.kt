package com.bori.core.keyboard.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.bori.core.keyboard.predict.KeyProximity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Records labelled swipes for the Swipe Trainer. The keyboard only records while the focused
 * field is the trainer's own field (our package + [TRAINER_TAG] in privateImeOptions), and
 * only while the trainer has set a [target] word. Data stays in app-private storage until the
 * user copies it.
 */
object SwipeRecorder {
    const val TRAINER_TAG = "com.bori.core.keyboard.SWIPE_TRAINER"
    private const val FILE = "swipe_trainer.json"
    private const val MAX_EXPORT_POINTS = 80

    class Record(
        val target: String,
        val decoded: List<String>,
        val xs: IntArray,
        val ys: IntArray,
        val ts: IntArray,
    )

    /** The word the trainer is asking for, or null when not training. */
    @Volatile var target: String? = null
    /** Called on the main thread after each recorded swipe. */
    var listener: ((Record) -> Unit)? = null

    private val records = ArrayList<Record>()
    private var lang = ""
    private var keyWidth = 0f
    private var keyHeight = 0f
    private val keyCenters = LinkedHashMap<Char, Pair<Float, Float>>()
    private val main = Handler(Looper.getMainLooper())
    private var loaded = false

    val count: Int get() = synchronized(records) { records.size }

    fun isTrainerField(info: android.view.inputmethod.EditorInfo?, context: Context): Boolean =
        info != null && info.packageName == context.packageName && info.privateImeOptions == TRAINER_TAG

    fun record(context: Context, language: String, prox: KeyProximity, xs: FloatArray, ys: FloatArray, ts: IntArray, count: Int, decoded: List<String>) {
        val t = target ?: return
        ensureLoaded(context)
        // Keep at most MAX_EXPORT_POINTS evenly spaced points; plenty for analysis.
        val stride = maxOf(1, (count + MAX_EXPORT_POINTS - 1) / MAX_EXPORT_POINTS)
        val idx = (0 until count step stride).toMutableList().also { if (it.last() != count - 1) it.add(count - 1) }
        val rec = Record(
            t, decoded.take(3),
            IntArray(idx.size) { xs[idx[it]].toInt() },
            IntArray(idx.size) { ys[idx[it]].toInt() },
            IntArray(idx.size) { ts[idx[it]] },
        )
        synchronized(records) {
            lang = language
            keyWidth = prox.keyWidth
            keyHeight = prox.keyHeight
            keyCenters.clear()
            for (c in prox.letters.sorted()) keyCenters[c] = prox.centerX(c) to prox.centerY(c)
            records.add(rec)
        }
        save(context)
        main.post { listener?.invoke(rec) }
    }

    /** Compact JSON for pasting into a chat: key geometry plus every recorded swipe. */
    fun exportJson(context: Context): String {
        ensureLoaded(context)
        return synchronized(records) { toJson().toString() }
    }

    fun clear(context: Context) {
        synchronized(records) { records.clear() }
        File(context.filesDir, FILE).delete()
    }

    private fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("lang", lang)
        put("kw", keyWidth.toDouble())
        put("kh", keyHeight.toDouble())
        put("keys", JSONObject().apply {
            for ((c, p) in keyCenters) put(c.toString(), JSONArray(listOf(p.first.toInt(), p.second.toInt())))
        })
        put("swipes", JSONArray().apply {
            for (r in records) put(JSONObject().apply {
                put("w", r.target)
                put("got", JSONArray(r.decoded))
                put("x", JSONArray(r.xs.toList()))
                put("y", JSONArray(r.ys.toList()))
                put("t", JSONArray(r.ts.toList()))
            })
        })
    }

    private fun save(context: Context) {
        val json = synchronized(records) { toJson().toString() }
        runCatching { File(context.filesDir, FILE).writeText(json) }
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            synchronized(records) {
                lang = o.optString("lang")
                keyWidth = o.optDouble("kw", 0.0).toFloat()
                keyHeight = o.optDouble("kh", 0.0).toFloat()
                val k = o.optJSONObject("keys")
                k?.keys()?.forEach { c ->
                    val a = k.getJSONArray(c)
                    keyCenters[c[0]] = a.getInt(0).toFloat() to a.getInt(1).toFloat()
                }
                val arr = o.optJSONArray("swipes") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    fun ints(name: String) = s.getJSONArray(name).let { a -> IntArray(a.length()) { a.getInt(it) } }
                    val got = s.getJSONArray("got").let { a -> List(a.length()) { a.getString(it) } }
                    records.add(Record(s.getString("w"), got, ints("x"), ints("y"), ints("t")))
                }
            }
        }
    }
}
