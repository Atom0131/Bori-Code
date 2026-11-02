package com.bori.core.keyboard.view

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.bori.core.keyboard.theme.KeyboardTheme

/**
 * The IME's input view: suggestion strip + keyboard, with the preview overlay on top. The
 * clipboard and emoji panels take the keyboard's place (same height) while open.
 */
class InputRootView(context: Context) : FrameLayout(context) {

    val strip = SuggestionStripView(context)
    val keyboard = KeyboardView(context)
    val overlay = KeyOverlayView(context)
    val clipboard = ClipboardPanelView(context).apply { visibility = GONE }
    val emoji = EmojiPanelView(context).apply { visibility = GONE }

    val clipboardOpen: Boolean get() = clipboard.visibility == VISIBLE
    val emojiOpen: Boolean get() = emoji.visibility == VISIBLE

    init {
        val density = resources.displayMetrics.density
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (44 * density).toInt()))
            // The keys stay laid out (invisible) under the panel so the height doesn't jump.
            val keys = FrameLayout(context).apply {
                addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(clipboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                addView(emoji, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            addView(keys, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        keyboard.overlay = overlay
    }

    fun applyTheme(theme: KeyboardTheme) {
        setBackgroundColor(theme.background)
        strip.setTheme(theme)
        keyboard.setTheme(theme)
        clipboard.setTheme(theme)
        emoji.setTheme(theme)
    }

    fun showClipboard(open: Boolean) {
        if (open == clipboardOpen) return
        if (open) {
            keyboard.cancelAll()
            showEmoji(false)
        }
        clipboard.visibility = if (open) VISIBLE else GONE
        keyboard.visibility = if (open) INVISIBLE else VISIBLE
        strip.clipboardOpen = open
    }

    fun showEmoji(open: Boolean) {
        if (open == emojiOpen) return
        if (open) {
            keyboard.cancelAll()
            showClipboard(false)
        } else {
            emoji.stopRepeat()
        }
        emoji.visibility = if (open) VISIBLE else GONE
        keyboard.visibility = if (open) INVISIBLE else VISIBLE
    }
}
