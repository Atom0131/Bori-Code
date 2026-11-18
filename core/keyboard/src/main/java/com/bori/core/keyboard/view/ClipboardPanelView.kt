package com.bori.core.keyboard.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bori.core.keyboard.clipboard.ClipboardHistory
import com.bori.core.keyboard.theme.KeyboardTheme
import java.io.File

/**
 * Clipboard panel shown in place of the keys: search and editing actions on top (select all,
 * cut, copy, paste, clear), then pinned clips and recent clips, two per row, each with how
 * long ago it was copied. Tap a clip to paste it; long-press to pin or delete.
 */
class ClipboardPanelView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onClipPaste(clip: ClipboardHistory.Clip)
        fun onClipLongPress(clip: ClipboardHistory.Clip)
        /** One of [ACTION_SELECT_ALL], [ACTION_CUT], [ACTION_COPY], [ACTION_PASTE], [ACTION_CLEAR]. */
        fun onClipAction(action: Int)
        /** The 🔍 chip: type a word to find a clip. */
        fun onClipSearch()
    }

    var listener: Listener? = null
    private var theme = KeyboardTheme.light()
    private var clips: List<ClipboardHistory.Clip> = emptyList()
    /** Shows only clips containing this (from the search), or everything when empty. */
    var filter: String = ""
        set(v) {
            field = v
            rebuild()
        }
    /** Where picture clips are stored. */
    var imageFile: (ClipboardHistory.Clip) -> File? = { null }

    private val density = resources.displayMetrics.density
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { addView(list) }
    private val thumbs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        column.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        rebuild()
    }

    fun setTheme(theme: KeyboardTheme) {
        this.theme = theme
        rebuild()
    }

    fun setClips(clips: List<ClipboardHistory.Clip>) {
        this.clips = clips
        rebuild()
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun rebuild() {
        setBackgroundColor(theme.solidBackground)
        actions.removeAllViews()
        actions.addView(
            chip("🔍").apply {
                contentDescription = "Search clips"
                setOnClickListener { listener?.onClipSearch() }
            },
            LinearLayout.LayoutParams(dp(44), LayoutParams.MATCH_PARENT).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) },
        )
        for ((label, action) in ACTIONS) {
            actions.addView(
                chip(label).apply { setOnClickListener { listener?.onClipAction(action) } },
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) },
            )
        }
        list.removeAllViews()
        val q = filter.trim()
        val shown = if (q.isEmpty()) clips else clips.filter { it.text.contains(q, ignoreCase = true) }
        if (q.isNotEmpty()) {
            list.addView(
                label("Clips with “$q” · ${shown.size}   ✕ show all").apply {
                    setOnClickListener { filter = "" }
                },
            )
        }
        if (shown.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    text = if (q.isNotEmpty()) {
                        "No clips contain “$q”."
                    } else {
                        "Text and pictures you copy show up here. Pin a clip to keep it for good."
                    }
                    setTextColor(theme.hint)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(32), dp(24), dp(32))
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            return
        }
        val pinned = shown.filter { it.pinned }
        val recent = shown.filter { !it.pinned }
        if (pinned.isNotEmpty()) {
            list.addView(label("📌 Pinned"))
            addRows(pinned)
        }
        if (recent.isNotEmpty()) {
            if (pinned.isNotEmpty() || q.isEmpty()) list.addView(label("Recent · ${recent.size}"))
            addRows(recent)
        }
    }

    private fun addRows(items: List<ClipboardHistory.Clip>) {
        for (pair in items.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in pair) row.addView(card(c), cardParams())
            if (pair.size == 1) row.addView(View(context), cardParams())
            list.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun cardParams() = LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(theme.hint)
        setPadding(dp(10), dp(6), dp(10), dp(2))
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = theme.cornerRadiusDp * density
    }

    private fun chip(label: String) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 13f
        maxLines = 1
        setTextColor(theme.textFunction)
        background = rounded(theme.keyFunction)
    }

    private fun card(c: ClipboardHistory.Clip): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(theme.keyLetter)
            setPadding(dp(10), dp(6), dp(10), dp(4))
            setOnClickListener { listener?.onClipPaste(c) }
            setOnLongClickListener {
                listener?.onClipLongPress(c)
                true
            }
        }
        val thumb = if (c.isImage) thumbnail(c) else null
        if (thumb != null) {
            box.addView(
                ImageView(context).apply {
                    setImageBitmap(thumb)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = c.text
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
            )
        } else {
            box.addView(
                TextView(context).apply {
                    text = if (c.isImage) "🖼 " + c.text else c.text.trim()
                    textSize = 13f
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(theme.text)
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        box.addView(
            TextView(context).apply {
                text = listOfNotNull(if (c.pinned) "📌" else null, kindIcon(c), ago(c.time)).joinToString("  ")
                textSize = 10f
                maxLines = 1
                gravity = Gravity.END
                setTextColor(theme.hint)
                typeface = Typeface.DEFAULT
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        return box
    }

    /** A small picture for a picture clip, decoded at card size and cached. */
    private fun thumbnail(c: ClipboardHistory.Clip): Bitmap? {
        val f = imageFile(c) ?: return null
        thumbs.get(f.name)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= dp(160) && bounds.outHeight / (sample * 2) >= dp(60)) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { thumbs.put(f.name, it) }
    }

    private fun kindIcon(c: ClipboardHistory.Clip): String? {
        if (c.isImage) return "🖼"
        val t = c.text.trim()
        return when {
            URL.matches(t) -> "🔗"
            EMAIL.matches(t) -> "✉"
            PHONE.matches(t) && t.count { it.isDigit() } >= 7 -> "📞"
            else -> null
        }
    }

    private fun ago(time: Long): String {
        val s = (System.currentTimeMillis() - time) / 1000
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }

    companion object {
        const val ACTION_SELECT_ALL = 1
        const val ACTION_CUT = 2
        const val ACTION_COPY = 3
        const val ACTION_PASTE = 4
        const val ACTION_CLEAR = 5
        private val ACTIONS = listOf(
            "Select all" to ACTION_SELECT_ALL,
            "Cut" to ACTION_CUT,
            "Copy" to ACTION_COPY,
            "Paste" to ACTION_PASTE,
            "Clear" to ACTION_CLEAR,
        )
        private val URL = Regex("""(?i)(https?://|www\.)\S+""")
        private val EMAIL = Regex("""[^\s@]+@[^\s@]+\.[A-Za-z]{2,}""")
        private val PHONE = Regex("""\+?[\d\s().-]{7,20}""")
    }
}
