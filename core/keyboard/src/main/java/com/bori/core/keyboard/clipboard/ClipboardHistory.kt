package com.bori.core.keyboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Recently copied text, kept on this phone only (Gboard-style clipboard). Unpinned clips
 * expire after [EXPIRY_MS]; pinned clips stay until deleted. Text the copying app marks as
 * sensitive (passwords, Android 13+) is never kept.
 */
class ClipboardHistory(context: Context) {

    class Clip(val text: String, val time: Long, val pinned: Boolean)

    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val file = File(app.filesDir, "clipboard.json")
    private var clips = mutableListOf<Clip>()
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** Called after every change, on the main thread. */
    var onChanged: () -> Unit = {}

    /** Whether new copies are recorded (Settings switch, off while incognito). */
    var recording: () -> Boolean = { true }

    /** When the newest clip was copied while Bori was listening; 0 if none this session. */
    var lastCopyAt = 0L
        private set

    init {
        load()
    }

    fun startListening() {
        if (listener != null) return
        val l = ClipboardManager.OnPrimaryClipChangedListener { capture(fresh = true) }
        cm.addPrimaryClipChangedListener(l)
        listener = l
    }

    fun stopListening() {
        listener?.let { cm.removePrimaryClipChangedListener(it) }
        listener = null
    }

    /** Newest first, pinned and unpinned together, expired clips dropped. */
    fun items(): List<Clip> {
        prune()
        return clips.toList()
    }

    /** Reads the system clipboard into the history (copies made while Bori wasn't listening). */
    fun capture(fresh: Boolean = false) {
        if (!recording()) return
        val text = runCatching { currentText() }.getOrNull() ?: return
        if (text.isBlank() || text.length > MAX_CHARS) return
        val old = clips.firstOrNull { it.text == text }
        if (old != null && clips.first() === old && !fresh) return
        if (old != null) clips.remove(old)
        clips.add(0, Clip(text, System.currentTimeMillis(), old?.pinned ?: false))
        if (fresh) lastCopyAt = System.currentTimeMillis()
        prune()
        save()
        onChanged()
    }

    /** The text to offer in the suggestion bar: copied within [FRESH_MS] and not used yet. */
    fun freshClip(): String? {
        if (lastCopyAt == 0L || System.currentTimeMillis() - lastCopyAt > FRESH_MS) return null
        return clips.firstOrNull()?.text
    }

    /** The fresh clip was pasted or dismissed: stop offering it. */
    fun consumeFresh() {
        lastCopyAt = 0L
    }

    fun togglePin(text: String) {
        val i = clips.indexOfFirst { it.text == text }
        if (i < 0) return
        val c = clips[i]
        clips[i] = Clip(c.text, c.time, !c.pinned)
        save()
        onChanged()
    }

    fun delete(text: String) {
        if (clips.removeAll { it.text == text }) {
            if (clips.isEmpty()) lastCopyAt = 0L
            save()
            onChanged()
        }
    }

    /** Removes every clip that isn't pinned. */
    fun clearUnpinned() {
        clips.removeAll { !it.pinned }
        lastCopyAt = 0L
        save()
        onChanged()
    }

    /** Puts [text] on the system clipboard (used by "Copy" in the panel). */
    fun copy(text: String) {
        cm.setPrimaryClip(ClipData.newPlainText("Bori", text))
    }

    private fun currentText(): String? {
        if (!cm.hasPrimaryClip()) return null
        val clip = cm.primaryClip ?: return null
        val desc = clip.description
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            desc?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        ) {
            return null
        }
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(app)?.toString()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        var unpinned = 0
        clips.removeAll { c ->
            if (c.pinned) return@removeAll false
            unpinned++
            now - c.time > EXPIRY_MS || unpinned > MAX_UNPINNED
        }
    }

    private fun load() {
        clips = runCatching {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Clip(o.getString("text"), o.getLong("time"), o.optBoolean("pinned"))
            }
        }.getOrDefault(mutableListOf())
        prune()
    }

    private fun save() {
        val arr = JSONArray()
        for (c in clips) arr.put(JSONObject().put("text", c.text).put("time", c.time).put("pinned", c.pinned))
        runCatching { file.writeText(arr.toString()) }
    }

    companion object {
        const val EXPIRY_MS = 60 * 60 * 1000L
        const val FRESH_MS = 2 * 60 * 1000L
        private const val MAX_UNPINNED = 30
        private const val MAX_CHARS = 20_000
    }
}
