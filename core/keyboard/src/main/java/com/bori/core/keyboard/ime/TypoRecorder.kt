package com.bori.core.keyboard.ime

import android.content.Context
import com.bori.core.keyboard.predict.Composer
import com.bori.core.keyboard.predict.KeyProximity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Opt-in log of the user's own corrections, for tuning the corrector: whenever they pick a
 * suggestion while typing, or undo an autocorrection, it stores what was typed, what they
 * chose, what autocorrect wanted and the previous word. Each letter's touch point is kept as
 * an offset from the typed key's centre (in key units), so tools/typo_bench.py can replay
 * it on its own key layout. Stays in app-private storage until the user copies it.
 */
object TypoRecorder {
    private const val FILE = "typo_log.jsonl"
    private const val MAX_LINES = 3000

    /**
     * kind: "pick" (chose from the strip), "undo" (backspace undid an autocorrection; may be
     * the start of an edit), "keep" (the undone word was then committed unchanged) or
     * "tapfix" (tapped a typed word and chose a fix).
     */
    fun record(
        context: Context,
        kind: String,
        language: String,
        typed: String,
        chosen: String,
        auto: String?,
        prev: String?,
        composer: Composer?,
        prox: KeyProximity?,
    ) {
        if (typed.isEmpty()) return
        val o = JSONObject().apply {
            put("k", kind)
            put("lang", language)
            put("typed", typed)
            put("chosen", chosen)
            if (auto != null) put("auto", auto)
            if (prev != null) put("prev", prev)
            val taps = touchOffsets(typed, composer, prox)
            if (taps != null) put("taps", taps)
        }
        runCatching {
            val f = File(context.filesDir, FILE)
            f.appendText(o.toString() + "\n")
            if (f.length() > MAX_LINES * 200L) {
                val lines = f.readLines()
                if (lines.size > MAX_LINES) f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** [[dx, dy], ...] per typed letter, or null when the touches are unknown. */
    private fun touchOffsets(typed: String, composer: Composer?, prox: KeyProximity?): JSONArray? {
        if (composer == null || prox == null || composer.typed != typed) return null
        val out = JSONArray()
        var any = false
        for (i in typed.indices) {
            val c = typed[i].lowercaseChar()
            val x = composer.x(i)
            val y = composer.y(i)
            if (x.isNaN() || !prox.hasKey(c)) {
                out.put(JSONArray())
                continue
            }
            any = true
            val dx = (x - prox.centerX(c)) / prox.keyWidth
            val dy = (y - prox.centerY(c)) / prox.keyHeight
            out.put(JSONArray(listOf(round2(dx), round2(dy))))
        }
        return if (any) out else null
    }

    private fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0

    fun count(context: Context): Int =
        runCatching { File(context.filesDir, FILE).useLines { it.count() } }.getOrDefault(0)

    fun export(context: Context): String =
        runCatching { File(context.filesDir, FILE).readText() }.getOrDefault("")

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
