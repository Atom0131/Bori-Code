package com.bori.core.keyboard.view

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.bori.core.keyboard.theme.KeyboardTheme

/**
 * The terminal keys' root: suggestion strip + keyboard, with the preview overlay on top. The
 * clipboard and emoji panels take the keyboard's place (same height) while open.
 *
 * Bori Code's own trimmed copy of Bori Keyboard's InputRootView (no GIF, voice, search bar or
 * one-handed bar): `scripts/sync_bori_keyboard.sh` keeps it. When upstream changes this file,
 * merge the parts the terminal uses by hand.
 */
class InputRootView(context: Context) : FrameLayout(context) {

    enum class Panel { KEYS, CLIPBOARD, EMOJI }

    val strip = SuggestionStripView(context)
    val keyboard = KeyboardView(context)
    /** Ambient background effect; the terminal leaves it off. */
    val ambient = AmbientView(context)
    /** Press effects over the keys; the terminal leaves them off. */
    val effects = EffectsView(context)
    val overlay = KeyOverlayView(context)
    val clipboard = ClipboardPanelView(context).apply { visibility = GONE }
    val emoji = EmojiPanelView(context).apply { visibility = GONE }

    /** What fills the key area right now. */
    var panel = Panel.KEYS
        private set

    val clipboardOpen: Boolean get() = panel == Panel.CLIPBOARD
    val emojiOpen: Boolean get() = panel == Panel.EMOJI

    init {
        val density = resources.displayMetrics.density
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (44 * density).toInt()))
            // The keys stay laid out (invisible) under the panel so the height doesn't jump.
            val keys = KeysFrame(context, keyboard).apply {
                addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(clipboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                addView(emoji, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                addView(effects, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                clipChildren = false
            }
            clipChildren = false
            addView(keys, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(ambient, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        keyboard.overlay = overlay
        keyboard.effects = effects
        clipChildren = false
    }

    fun applyTheme(theme: KeyboardTheme) {
        val grad = theme.backgroundGradient
        val backdrop = theme.backdrop
        if (backdrop != null) {
            background = com.bori.core.keyboard.theme.BackdropDrawable(context, backdrop)
        } else if (grad != null) {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, grad,
            )
        } else {
            setBackgroundColor(theme.background)
        }
        strip.setTheme(theme)
        keyboard.setTheme(theme)
        clipboard.setTheme(theme)
        emoji.setTheme(theme)
    }

    /** Shows [p] in the key area (the keys stay laid out underneath to keep the height). */
    fun show(p: Panel) {
        if (p == panel) return
        if (panel == Panel.EMOJI) emoji.stopRepeat()
        if (p != Panel.KEYS) {
            keyboard.cancelAll()
            effects.clear()
        }
        clipboard.visibility = if (p == Panel.CLIPBOARD) VISIBLE else GONE
        emoji.visibility = if (p == Panel.EMOJI) VISIBLE else GONE
        keyboard.visibility = if (p == Panel.KEYS) VISIBLE else INVISIBLE
        strip.clipboardOpen = p == Panel.CLIPBOARD
        panel = p
    }

    fun showClipboard(open: Boolean) {
        if (open) show(Panel.CLIPBOARD) else if (panel == Panel.CLIPBOARD) show(Panel.KEYS)
    }

    fun showEmoji(open: Boolean) {
        if (open) show(Panel.EMOJI) else if (panel == Panel.EMOJI) show(Panel.KEYS)
    }
}

/**
 * Holds the keys and the panels. Its height is always the keyboard's height; the panels are
 * fitted into it. (A plain FrameLayout grew to the panel's size: the emoji grid is hundreds of
 * rows tall, so the keyboard covered the whole screen.)
 */
private class KeysFrame(context: Context, private val sizer: View) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        sizer.measure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = sizer.measuredHeight
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === sizer || child.visibility == GONE) continue
            child.measure(ws, hs)
        }
        setMeasuredDimension(w, h)
    }
}
