package com.bori.core.keyboard.view

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bori.core.keyboard.clipboard.ClipboardHistory
import com.bori.core.keyboard.theme.KeyboardTheme

/**
 * Clipboard panel shown in place of the keys: editing actions on top (select all, cut, copy,
 * paste), then recent clips two per row. Tap a clip to paste it; long-press to pin or delete.
 */
class ClipboardPanelView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onClipPaste(text: String)
        fun onClipLongPress(clip: ClipboardHistory.Clip)
        /** One of [ACTION_SELECT_ALL], [ACTION_CUT], [ACTION_COPY], [ACTION_PASTE], [ACTION_CLEAR]. */
        fun onClipAction(action: Int)
    }

    var listener: Listener? = null
    private var theme = KeyboardTheme.light()
    private var clips: List<ClipboardHistory.Clip> = emptyList()

    private val density = resources.displayMetrics.density
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { addView(list) }

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
        setBackgroundColor(theme.background)
        actions.removeAllViews()
        for ((label, action) in ACTIONS) {
            actions.addView(
                chip(label, bold = false).apply { setOnClickListener { listener?.onClipAction(action) } },
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) },
            )
        }
        list.removeAllViews()
        if (clips.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    text = "Copied text shows up here for an hour. Pin a clip to keep it."
                    setTextColor(theme.hint)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(32), dp(24), dp(32))
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            return
        }
        // Pinned clips first, then newest first.
        val ordered = clips.filter { it.pinned } + clips.filter { !it.pinned }
        for (pair in ordered.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in pair) row.addView(card(c), LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            list.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = theme.cornerRadiusDp * density
    }

    private fun chip(label: String, bold: Boolean) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 13f
        maxLines = 1
        setTextColor(theme.textFunction)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        background = rounded(theme.keyFunction)
    }

    private fun card(c: ClipboardHistory.Clip) = TextView(context).apply {
        text = if (c.pinned) "📌 " + c.text.trim() else c.text.trim()
        textSize = 13f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.text)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        background = rounded(theme.keyLetter)
        setOnClickListener { listener?.onClipPaste(c.text) }
        setOnLongClickListener {
            listener?.onClipLongPress(c)
            true
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
    }
}
