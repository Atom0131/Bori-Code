package com.bori.core.keyboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.bori.core.keyboard.theme.KeyboardTheme

/**
 * Bar above the keys: settings button, three suggestion slots laid out
 * [second | best | third], and a clipboard button on the right. Long-pressing a suggestion
 * asks whether to stop suggesting it. The strip can also ask a one-line question with two
 * answers (see [showPrompt]) and offer just-copied text as a chip (see [setClip]).
 */
class SuggestionStripView(context: Context) : View(context) {

    interface Listener {
        fun onOpenSettings()
        fun onSuggestionPicked(slot: Int)
        fun onSuggestionLongPressed(slot: Int)
        fun onBlockAnswered(word: String, block: Boolean)
        fun onClipboardButton()
        fun onClipChipPicked()
    }

    /** One slot. [quoted] marks the user's literal typing when it would be corrected. */
    class Item(val text: String, val bold: Boolean = false, val quoted: Boolean = false)

    var listener: Listener? = null
    private var theme = KeyboardTheme.light()
    private var items: List<Item?> = listOf(null, null, null)
    /** A question shown instead of the suggestions; [sticky] ones survive new suggestions. */
    private class Prompt(
        val text: String,
        val yes: String,
        val no: String,
        val sticky: Boolean,
        val onAnswer: (Boolean) -> Unit,
    )

    private var prompt: Prompt? = null
    private var incognito = false
    /** Just-copied text offered in place of the slots; tapping it pastes. */
    private var clip: String? = null

    /** The clipboard panel is open: the right button shows a keyboard to go back. */
    var clipboardOpen = false
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dividerPaint = Paint()
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val settingsWidth get() = 48 * density
    private val clipWidth get() = 48 * density

    private var downSlot = NONE
    private var longPressed = false
    private val longPress = Runnable {
        val slot = downSlot
        if (slot >= 0 && items.getOrNull(slot) != null) {
            longPressed = true
            listener?.onSuggestionLongPressed(slot)
        }
    }

    fun setTheme(theme: KeyboardTheme) {
        this.theme = theme
        invalidate()
    }

    /** Exactly three entries, in slot order [left, centre, right]; null leaves a slot empty. */
    fun setItems(items: List<Item?>) {
        this.items = items
        if (prompt?.sticky == false) prompt = null
        invalidate()
    }

    fun clear() = setItems(listOf(null, null, null))

    fun setClip(text: String?) {
        if (clip != text) {
            clip = text
            invalidate()
        }
    }

    fun showBlockPrompt(word: String) =
        showPrompt("Stop suggesting “$word”?", "Block", "✕", sticky = false) { listener?.onBlockAnswered(word, it) }

    /** Asks [text] with a [yes] and a [no] button; a [sticky] prompt stays until answered. */
    fun showPrompt(text: String, yes: String, no: String, sticky: Boolean, onAnswer: (Boolean) -> Unit) {
        prompt = Prompt(text, yes, no, sticky, onAnswer)
        invalidate()
    }

    fun setIncognito(on: Boolean) {
        if (incognito != on) {
            incognito = on
            invalidate()
        }
    }

    private fun slotWidth() = (width - settingsWidth - clipWidth) / 3f
    private fun slotAt(x: Float): Int = when {
        x < settingsWidth -> SETTINGS
        x >= width - clipWidth -> CLIPBOARD
        else -> ((x - settingsWidth) / slotWidth()).toInt().coerceIn(0, 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        textPaint.color = theme.stripText
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = height * 0.42f
        drawText(canvas, if (incognito) "🕶" else "⚙", settingsWidth / 2, settingsWidth)
        drawText(canvas, if (clipboardOpen) "⌨" else "📋", width - clipWidth / 2, clipWidth)

        prompt?.let { p ->
            val w = width - settingsWidth - clipWidth
            textPaint.textSize = height * 0.32f
            drawText(canvas, p.text, settingsWidth + w * 0.36f, w * 0.7f)
            textPaint.color = theme.accent
            textPaint.typeface = Typeface.DEFAULT_BOLD
            drawText(canvas, p.yes, settingsWidth + w * 0.78f, w * 0.15f)
            textPaint.color = theme.stripText
            textPaint.typeface = Typeface.DEFAULT
            drawText(canvas, p.no, settingsWidth + w * 0.93f, w * 0.12f)
            return
        }
        // Pin/delete prompts show over the clipboard panel; suggestions don't.
        if (clipboardOpen) return

        clip?.let { c ->
            // One wide pill across the slots: "📋 copied text".
            val left = settingsWidth + 4 * density
            val right = width - clipWidth - 4 * density
            pressPaint.color = if (downSlot >= 0) theme.keyPressed else theme.keyFunction
            canvas.drawRoundRect(left, 6 * density, right, height - 6 * density, height / 2f, height / 2f, pressPaint)
            textPaint.color = theme.stripText
            textPaint.textSize = height * 0.34f
            val oneLine = c.trim().replace(Regex("\\s+"), " ")
            drawText(canvas, "📋  $oneLine", (left + right) / 2, right - left - 24 * density)
            return
        }

        val sw = slotWidth()
        dividerPaint.color = theme.hint
        dividerPaint.alpha = 80
        for (i in 0..2) {
            val left = settingsWidth + i * sw
            if (i == downSlot) {
                pressPaint.color = theme.keyPressed
                canvas.drawRoundRect(left + 2 * density, 4 * density, left + sw - 2 * density, height - 4 * density, 8 * density, 8 * density, pressPaint)
            }
            if (i > 0) canvas.drawLine(left, height * 0.3f, left, height * 0.7f, dividerPaint)
            val item = items.getOrNull(i) ?: continue
            textPaint.color = theme.stripText
            textPaint.typeface = if (item.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textPaint.textSize = height * 0.4f
            val label = if (item.quoted) "“${item.text}”" else item.text
            drawText(canvas, label, left + sw / 2, sw - 8 * density)
        }
    }

    /** Draws centred text, shrinking it (down to 60%) and then truncating to fit [maxWidth]. */
    private fun drawText(canvas: Canvas, text: String, cx: Float, maxWidth: Float) {
        var t = text
        val size = textPaint.textSize
        val w = textPaint.measureText(t)
        if (w > maxWidth) textPaint.textSize = maxOf(size * 0.6f, size * maxWidth / w)
        if (textPaint.measureText(t) > maxWidth) {
            val n = textPaint.breakText(t, true, maxWidth - textPaint.measureText("…"), null)
            t = t.take(n.coerceAtLeast(1)) + "…"
        }
        val cy = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(t, cx, cy, textPaint)
        textPaint.textSize = size
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downSlot = if (prompt == null) slotAt(e.x).takeIf { it >= 0 } ?: NONE else NONE
                if (downSlot >= 0 && clip == null && !clipboardOpen) handler.postDelayed(longPress, LONG_PRESS_MS)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (downSlot >= 0 && slotAt(e.x) != downSlot) cancelPress()
            MotionEvent.ACTION_UP -> {
                val slot = downSlot
                cancelPress()
                val p = prompt
                when {
                    e.x >= width - clipWidth -> listener?.onClipboardButton()
                    p != null -> {
                        val rel = (e.x - settingsWidth) / (width - settingsWidth - clipWidth)
                        if (rel >= 0.7f) {
                            prompt = null
                            p.onAnswer(rel < 0.86f)
                            invalidate()
                        }
                    }
                    e.x < settingsWidth -> listener?.onOpenSettings()
                    clipboardOpen -> Unit
                    clip != null -> listener?.onClipChipPicked()
                    slot >= 0 && !longPressed && items.getOrNull(slot) != null -> listener?.onSuggestionPicked(slot)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelPress()
        }
        return true
    }

    private fun cancelPress() {
        handler.removeCallbacks(longPress)
        downSlot = NONE
        invalidate()
    }

    private companion object {
        const val NONE = -1
        const val SETTINGS = -2
        const val CLIPBOARD = -3
        const val LONG_PRESS_MS = 450L
    }
}
