package com.bori.core.keyboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.bori.core.keyboard.ime.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Everything copied recently, kept on this phone only: text and pictures. Unpinned clips are
 * kept for [keepMs] (Settings: an hour, a day, a week or until deleted); pinned clips stay
 * until deleted. Text the copying app marks as sensitive (passwords, Android 13+) is never kept.
 *
 * Each system copy is recorded once (by its ClipDescription timestamp), so a clip the user
 * deleted doesn't come back just because it's still on the system clipboard.
 */
class ClipboardHistory(context: Context) {

    /** [image] is a file name in the clips folder for pictures (then [text] is a label). */
    class Clip(
        val id: Long,
        val text: String,
        val time: Long,
        val pinned: Boolean,
        val image: String? = null,
        val mime: String? = null,
    ) {
        val isImage get() = image != null
        fun withPinned(p: Boolean) = Clip(id, text, time, p, image, mime)
    }

    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val file = File(app.filesDir, "clipboard.json")
    val imageDir = File(app.filesDir, "clips")
    private var clips = mutableListOf<Clip>()
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    /** Timestamp of the last system copy already recorded (or skipped on purpose). */
    private var lastStamp = 0L
    private var nextId = 0L

    /** Called after every change, on the main thread. */
    var onChanged: () -> Unit = {}

    /** Whether new copies are recorded (Settings switch, off while incognito). */
    var recording: () -> Boolean = { true }

    /** How long unpinned clips are kept in ms; 0 = until deleted. */
    var keepMs: () -> Long = { DEFAULT_KEEP_MS }

    /** When the newest clip was copied while Bori was listening; 0 if none this session. */
    var lastCopyAt = 0L
        private set

    init {
        load()
        // Pictures no clip points at any more (cleared, expired) are deleted once per start;
        // until then "Undo" after Clear can still bring them back.
        val used = clips.mapNotNull { it.image }.toHashSet()
        io.execute { imageDir.listFiles()?.forEach { if (it.name !in used) it.delete() } }
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
        if (prune()) save()
        return clips.toList()
    }

    fun imageFile(c: Clip): File? = c.image?.let { File(imageDir, it) }?.takeIf { it.exists() }

    /**
     * Reads the system clipboard into the history. Called by the clipboard listener, and when
     * the keyboard or the clipboard panel opens (copies made while Bori wasn't listening).
     */
    fun capture(fresh: Boolean = false) {
        if (!recording()) return
        val clip = runCatching { if (cm.hasPrimaryClip()) cm.primaryClip else null }.getOrNull() ?: return
        val desc = clip.description ?: return
        val stamp = desc.timestamp
        if (stamp != 0L && stamp == lastStamp) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        ) {
            rememberStamp(stamp)
            DiagLog.event("clip skipped: marked sensitive")
            return
        }
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val imageMime = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
        val uri = item.uri
        if (imageMime != null && uri != null) {
            rememberStamp(stamp)
            captureImage(uri, imageMime, desc.label?.toString().orEmpty(), fresh)
            return
        }
        val text = runCatching { item.coerceToText(app)?.toString() }.getOrNull() ?: return
        if (text.isBlank() || text.length > MAX_CHARS) return
        val old = clips.firstOrNull { !it.isImage && it.text == text }
        // Same text already on top (and no timestamp to tell copies apart): nothing new.
        if (stamp == 0L && old != null && clips.first() === old && !fresh) return
        rememberStamp(stamp)
        if (old != null) clips.remove(old)
        clips.add(0, Clip(newId(), text, System.currentTimeMillis(), old?.pinned ?: false))
        if (fresh) lastCopyAt = System.currentTimeMillis()
        DiagLog.event("clip saved: text ${text.length} chars, ${clips.size} kept")
        prune()
        save()
        onChanged()
    }

    /** Copies a picture off the clipboard into Bori's own folder (the app's link can expire). */
    private fun captureImage(uri: Uri, mime: String, label: String, fresh: Boolean) {
        val id = newId()
        val ext = mime.substringAfter('/').substringBefore('+').take(5).ifEmpty { "img" }
        io.execute {
            val out = File(imageDir, "$id.$ext")
            val ok = runCatching {
                imageDir.mkdirs()
                var total = 0L
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMAGE_BYTES) error("too big")
                            o.write(buf, 0, n)
                        }
                    }
                }
                total > 0
            }.getOrElse {
                DiagLog.event("clip image not saved: ${it.javaClass.simpleName} ${it.message}")
                false
            }
            if (!ok) out.delete()
            main.post {
                if (!ok) return@post
                clips.add(0, Clip(id, label.ifBlank { "Picture" }, System.currentTimeMillis(), false, out.name, mime))
                if (fresh) lastCopyAt = 0L // the strip chip is for text only
                DiagLog.event("clip saved: image $mime ${out.length() / 1024} KB")
                prune()
                save()
                onChanged()
            }
        }
    }

    /** The text to offer in the suggestion bar: copied within [FRESH_MS] and not used yet. */
    fun freshClip(): String? {
        if (lastCopyAt == 0L || System.currentTimeMillis() - lastCopyAt > FRESH_MS) return null
        return clips.firstOrNull()?.takeIf { !it.isImage }?.text
    }

    /** The fresh clip was pasted or dismissed: stop offering it. */
    fun consumeFresh() {
        lastCopyAt = 0L
    }

    fun togglePin(id: Long) {
        val i = clips.indexOfFirst { it.id == id }
        if (i < 0) return
        clips[i] = clips[i].withPinned(!clips[i].pinned)
        save()
        onChanged()
    }

    fun delete(id: Long) {
        if (clips.removeAll { it.id == id }) {
            if (clips.isEmpty()) lastCopyAt = 0L
            save()
            onChanged()
        }
    }

    /** Removes every clip that isn't pinned and returns them (for Undo, see [restore]). */
    fun clearUnpinned(): List<Clip> {
        val removed = clips.filter { !it.pinned }
        clips.removeAll { !it.pinned }
        lastCopyAt = 0L
        save()
        onChanged()
        return removed
    }

    /** Puts back clips removed by [clearUnpinned]. */
    fun restore(removed: List<Clip>) {
        val ids = clips.mapTo(HashSet()) { it.id }
        clips.addAll(removed.filter { it.id !in ids })
        clips.sortByDescending { it.time }
        save()
        onChanged()
    }

    /** Puts [text] on the system clipboard. */
    fun copy(text: String) {
        cm.setPrimaryClip(ClipData.newPlainText("Bori", text))
    }

    private fun newId(): Long {
        val t = System.currentTimeMillis()
        nextId = if (t > nextId) t else nextId + 1
        return nextId
    }

    private fun rememberStamp(stamp: Long) {
        if (stamp != 0L) lastStamp = stamp
    }

    /** Drops expired and overflow clips; true if anything was removed. */
    private fun prune(): Boolean {
        val now = System.currentTimeMillis()
        val keep = keepMs()
        var unpinned = 0
        var images = 0
        return clips.removeAll { c ->
            if (c.pinned) return@removeAll false
            unpinned++
            if (c.isImage) images++
            (keep > 0 && now - c.time > keep) || unpinned > MAX_UNPINNED || (c.isImage && images > MAX_IMAGES)
        }
    }

    private fun load() {
        runCatching {
            val raw = file.readText().trim()
            // Old files (before Session 21) were a bare array of {text, time, pinned}.
            val arr = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw).also {
                lastStamp = it.optLong("stamp")
            }.getJSONArray("clips")
            clips = MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val time = o.getLong("time")
                Clip(
                    if (o.has("id")) o.getLong("id") else time + i,
                    o.getString("text"),
                    time,
                    o.optBoolean("pinned"),
                    o.optString("image").ifEmpty { null },
                    o.optString("mime").ifEmpty { null },
                )
            }
            nextId = clips.maxOfOrNull { it.id } ?: 0L
        }
        prune()
    }

    private fun save() {
        val arr = JSONArray()
        for (c in clips) {
            val o = JSONObject().put("id", c.id).put("text", c.text).put("time", c.time).put("pinned", c.pinned)
            if (c.image != null) o.put("image", c.image).put("mime", c.mime)
            arr.put(o)
        }
        val json = JSONObject().put("stamp", lastStamp).put("clips", arr).toString()
        runCatching { file.writeText(json) }
    }

    companion object {
        const val DEFAULT_KEEP_MS = 7 * 24 * 60 * 60 * 1000L
        const val FRESH_MS = 2 * 60 * 1000L
        private const val MAX_UNPINNED = 200
        private const val MAX_IMAGES = 30
        private const val MAX_CHARS = 20_000
        private const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
    }
}
