package com.bori.core.keyboard.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bori.core.keyboard.theme.KeyboardTheme

/**
 * Emoji panel shown in place of the keys: a scrolling grid of emoji, and a bottom bar with
 * ABC (back to the keys), the categories (recent first) and delete (repeats while held).
 */
class EmojiPanelView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onEmoji(text: String)
        fun onEmojiDelete()
        fun onEmojiClose()
    }

    var listener: Listener? = null
    private var theme = KeyboardTheme.light()
    private var recents: List<String> = emptyList()
    /** -1 = recent, otherwise an index into [EmojiData.categories]. */
    private var category = 0

    private val density = resources.displayMetrics.density
    private val grid = EmojiGridView(context)
    private val scroll = ScrollView(context).apply { addView(grid) }
    private val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val tabScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(tabs)
    }
    /** Emoji per category that the phone's font can draw, filtered on first use. */
    private val usable = HashMap<Int, List<String>>()
    private val repeat = Handler(Looper.getMainLooper())

    init {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        rebuildBar()
    }

    fun setTheme(theme: KeyboardTheme) {
        this.theme = theme
        rebuildBar()
        grid.invalidate()
    }

    /** Called each time the panel opens; starts on Recent when there is something in it. */
    fun open(recents: List<String>) {
        this.recents = recents
        select(if (recents.isEmpty()) 0 else -1)
    }

    /** Updates the recent list without jumping to it (the user just picked an emoji). */
    fun setRecents(recents: List<String>) {
        this.recents = recents
    }

    fun stopRepeat() = repeat.removeCallbacksAndMessages(null)

    private fun dp(v: Int) = (v * density).toInt()

    private fun select(cat: Int) {
        category = cat
        grid.items = if (cat < 0) recents else emojiOf(cat)
        scroll.scrollTo(0, 0)
        rebuildBar()
    }

    private fun emojiOf(cat: Int): List<String> = usable.getOrPut(cat) {
        val paint = Paint()
        EmojiData.categories[cat].emoji.split(' ').filter { it.isNotEmpty() && paint.hasGlyph(it) }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = theme.cornerRadiusDp * density
    }

    private fun barButton(label: String, selected: Boolean) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = if (label.length > 2 && label[0].isLetter()) 14f else 18f
        maxLines = 1
        setTextColor(theme.textFunction)
        if (selected) background = rounded(theme.keyFunction)
    }

    private fun rebuildBar() {
        setBackgroundColor(theme.background)
        bar.removeAllViews()
        tabs.removeAllViews()
        bar.addView(
            barButton("ABC", selected = false).apply {
                background = rounded(theme.keyFunction)
                setOnClickListener { listener?.onEmojiClose() }
            },
            LinearLayout.LayoutParams(dp(56), LayoutParams.MATCH_PARENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) },
        )
        val icons = listOf(-1 to "🕘") + EmojiData.categories.mapIndexed { i, c -> i to c.icon }
        for ((cat, icon) in icons) {
            tabs.addView(
                barButton(icon, selected = cat == category).apply {
                    contentDescription = if (cat < 0) "Recent" else EmojiData.categories[cat].name
                    setOnClickListener { select(cat) }
                },
                LinearLayout.LayoutParams(dp(40), LayoutParams.MATCH_PARENT).apply { setMargins(dp(1), dp(4), dp(1), dp(4)) },
            )
        }
        bar.addView(tabScroll, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bar.addView(
            deleteButton(),
            LinearLayout.LayoutParams(dp(56), LayoutParams.MATCH_PARENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) },
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun deleteButton() = barButton("⌫", selected = false).apply {
        background = rounded(theme.keyFunction)
        contentDescription = "Delete"
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    listener?.onEmojiDelete()
                    repeat.postDelayed(object : Runnable {
                        override fun run() {
                            listener?.onEmojiDelete()
                            repeat.postDelayed(this, REPEAT_MS)
                        }
                    }, REPEAT_START_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopRepeat()
                }
            }
            true
        }
    }

    /** One view that draws the whole grid; cheaper than a view per emoji. */
    private inner class EmojiGridView(context: Context) : View(context) {
        var items: List<String> = emptyList()
            set(v) {
                field = v
                pressedIndex = -1
                requestLayout()
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val rect = RectF()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var pressedIndex = -1
        private var downX = 0f
        private var downY = 0f

        private fun columns() = (width / (CELL_DP * density)).toInt().coerceAtLeast(6)
        private fun cell() = width.toFloat() / columns()

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val cols = (w / (CELL_DP * density)).toInt().coerceAtLeast(6)
            val rows = (items.size + cols - 1) / cols
            val minH = MeasureSpec.getSize(heightSpec)
            setMeasuredDimension(w, maxOf((rows * w.toFloat() / cols).toInt(), minH, dp(120)))
        }

        override fun onDraw(canvas: Canvas) {
            if (items.isEmpty()) {
                paint.textSize = 14 * density
                paint.color = theme.hint
                canvas.drawText("Emoji you use show up here", width / 2f, dp(56).toFloat(), paint)
                return
            }
            val cols = columns()
            val c = cell()
            paint.textSize = c * 0.6f
            paint.color = theme.text
            val fm = paint.fontMetrics
            val baseline = c / 2 - (fm.ascent + fm.descent) / 2
            for ((i, e) in items.withIndex()) {
                val x = (i % cols) * c
                val y = (i / cols) * c
                if (i == pressedIndex) {
                    paint.color = theme.keyPressed
                    rect.set(x + 2, y + 2, x + c - 2, y + c - 2)
                    canvas.drawRoundRect(rect, theme.cornerRadiusDp * density, theme.cornerRadiusDp * density, paint)
                    paint.color = theme.text
                }
                canvas.drawText(e, x + c / 2, y + baseline, paint)
            }
        }

        private fun indexAt(x: Float, y: Float): Int {
            val cols = columns()
            val c = cell()
            val col = (x / c).toInt()
            val row = (y / c).toInt()
            if (col !in 0 until cols || row < 0) return -1
            val i = row * cols + col
            return if (i < items.size) i else -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    pressedIndex = indexAt(e.x, e.y)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (pressedIndex >= 0 && (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop)) {
                    pressedIndex = -1
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val i = pressedIndex
                    pressedIndex = -1
                    invalidate()
                    if (i >= 0) listener?.onEmoji(items[i])
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedIndex = -1
                    invalidate()
                }
            }
            return true
        }
    }

    companion object {
        private const val CELL_DP = 44
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_MS = 50L
    }
}
