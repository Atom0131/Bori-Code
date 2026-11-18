package com.bori.core.keyboard.view

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.bori.core.keyboard.layout.Key
import com.bori.core.keyboard.layout.KeyCode
import com.bori.core.keyboard.layout.KeyGeometry
import com.bori.core.keyboard.layout.KeyKind
import com.bori.core.keyboard.layout.KeyboardLayout
import com.bori.core.keyboard.layout.LayoutId
import com.bori.core.keyboard.layout.PlacedKey
import com.bori.core.keyboard.theme.KeyboardTheme
import java.util.Locale

enum class ShiftState { OFF, ON, LOCKED }

/**
 * Draws the keys and turns raw touches into key actions. Each finger runs a small state
 * machine on the same pointer stream: KEY (tap, rollover, long-press popup, delete repeat,
 * upward flick for the key's hint), GLIDE (swipe typing), SPACE_CURSOR (slide on the
 * spacebar moves the cursor) or DELETE_SLIDE (slide left from delete selects words).
 * A finger is in exactly one mode, so the gestures never fight each other.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        /** Haptic/sound hook, called when a key is first touched. */
        fun onKeyPressed(key: Key)
        /** [x]/[y] are where the key was touched, in view coordinates (NaN for repeats). */
        fun onKey(key: Key, x: Float, y: Float)
        /** Text picked from a long-press popup. */
        fun onPopupText(text: String)
        /** Long-press on a key without a popup. Return true if handled. */
        fun onKeyLongPress(key: Key): Boolean
        /** A swipe across the letters ended; points in view coordinates, [ts] in ms since touch-down. */
        fun onGlide(xs: FloatArray, ys: FloatArray, ts: IntArray, count: Int)
        /** Spacebar slide: move the cursor by [steps] characters (negative = left). */
        fun onCursorMove(steps: Int)
        /** Delete slide: select the last [words] words (0 = none) for deletion. */
        fun onDeleteSlide(words: Int)
        /** Delete slide released: delete the selection. */
        fun onDeleteSlideEnd()
    }

    var listener: Listener? = null
    var overlay: KeyOverlayView? = null
    /** Press effects layer (sparkles, hearts…), same coordinates as this view. */
    var effects: EffectsView? = null
    /** LED lighting on the keys (configured by the service). */
    val led = LedLights()
    var keyPreviewEnabled = true
    /** Key height multiplier per [Posture] (indexed by ordinal); picked again after a fold or rotation. */
    var keyHeightScales = FloatArray(Posture.entries.size) { 1f }
        set(value) {
            if (field.contentEquals(value)) return
            field = value
            requestLayout()
        }

    /**
     * The user's learned aim per letter (key units, from the touch heat map) for how the phone
     * is held now; null = plain key boxes. Asked on every touch, so it must be cheap (cached).
     */
    var aimShifts: (() -> Map<Char, FloatArray>)? = null

    /** Split the letters and symbols in two on big screens (unfolded foldables, tablets). */
    var splitOnBigScreens = true
        set(value) {
            if (field == value) return
            field = value
            cancelAll()
            rebuildGeometry(width, height)
            invalidate()
        }

    /** Split keyboard spacing, as shares of the width: between the halves, and at each edge. */
    private var splitGap = 0.3f
    private var splitEdge = 0.04f

    fun setSplitSpacing(gap: Float, edge: Float) {
        if (gap == splitGap && edge == splitEdge) return
        splitGap = gap
        splitEdge = edge
        cancelAll()
        rebuildGeometry(width, height)
        invalidate()
    }

    /** Rows the keyboard is sized for, so switching layouts never changes its height. */
    var heightRows = 4
        set(value) {
            field = value
            requestLayout()
        }

    private var layout: KeyboardLayout? = null
    private var geometry: KeyGeometry? = null
    private var theme = KeyboardTheme.light()
    private var shift = ShiftState.OFF
    private var enterLabel = "↵"
    private var spaceLabel = ""
    private var locale: Locale = Locale.US

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val backgroundPaint = Paint()
    private val ledTicker = FrameTicker(this, "led")
    private val pressedNow = ArrayList<PlacedKey>(4)
    /** Upper-case labels, made once (not a new String per key per frame). */
    private val upperLabels = HashMap<String, String>()
    /** False while an ambient effect runs behind us: InputRootView paints the background. */
    var paintBackground = true
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    /** Draws keys in the theme's look; set up in [updateBorderPaint]. */
    private val painter = com.bori.core.keyboard.theme.KeyPainter()
    private var labelFace: Typeface = Typeface.DEFAULT
    private var labelFaceBold: Typeface = Typeface.DEFAULT_BOLD

    private class Pointer(val id: Int) {
        var placed: PlacedKey? = null
        /** Action already performed (on down, by rollover, or by long-press). */
        var consumed = false
        /** Entered this key by sliding; shift/delete must not fire from a slide. */
        var slid = false
        var popup: KeyOverlayView.Popup? = null
        var x = Float.NaN
        var y = Float.NaN
        /**
         * Where the finger was when it got onto [placed]. A tap types that key with these
         * coordinates, not where the finger lifted: thumbs roll a few pixels as they lift.
         */
        var keyX = Float.NaN
        var keyY = Float.NaN
        var longPress: Runnable? = null
        var repeat: Runnable? = null
        var mode = Mode.KEY
        var downKey: PlacedKey? = null
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var steps = 0
        var pathX = FloatArray(128)
        var pathY = FloatArray(128)
        var pathT = IntArray(128)
        var pathCount = 0

        fun addPoint(px: Float, py: Float, time: Long) {
            if (pathCount == MAX_PATH) return
            if (pathCount == pathX.size) {
                pathX = pathX.copyOf(pathCount * 2)
                pathY = pathY.copyOf(pathCount * 2)
                pathT = pathT.copyOf(pathCount * 2)
            }
            pathX[pathCount] = px
            pathY[pathCount] = py
            pathT[pathCount] = (time - downTime).toInt()
            pathCount++
        }
    }

    private enum class Mode { KEY, GLIDE, SPACE_CURSOR, DELETE_SLIDE }

    /** Set by the service: glide typing is possible now (letters, suggestions, dictionary). */
    var glideEnabled = false
    var glideTrail = true
    /** Prefs.TRAIL_THEME (accent), Prefs.TRAIL_RAINBOW, or an ARGB colour. */
    var trailColor = com.bori.core.keyboard.settings.Prefs.TRAIL_THEME

    private fun trailColorOrAccent() =
        if (trailColor == com.bori.core.keyboard.settings.Prefs.TRAIL_THEME) theme.accent else trailColor
    var spaceCursorEnabled = true
    var deleteSlideEnabled = true
    var keyFlicksEnabled = true

    private val pointers = LinkedHashMap<Int, Pointer>()

    fun setKeyboard(layout: KeyboardLayout, locale: Locale) {
        cancelAll()
        this.layout = layout
        if (locale != this.locale) upperLabels.clear()
        this.locale = locale
        rebuildGeometry(width, height)
        requestLayout()
        invalidate()
    }

    fun setState(shift: ShiftState, enterLabel: String, spaceLabel: String) {
        this.shift = shift
        this.enterLabel = enterLabel
        this.spaceLabel = spaceLabel
        invalidate()
    }

    fun currentTheme(): KeyboardTheme = theme

    fun setTheme(theme: KeyboardTheme) {
        this.theme = theme
        rebuildGeometry(width, height)
        updateBorderPaint(width)
        invalidate()
    }

    /** Key painter + label fonts; rebuilt on theme or width change, never in onDraw. */
    private fun updateBorderPaint(w: Int) {
        painter.prepare(theme, w.toFloat(), density)
        labelFace = theme.typeface ?: Typeface.DEFAULT
        labelFaceBold = if (theme.typeface == null) Typeface.DEFAULT_BOLD else Typeface.create(theme.typeface, Typeface.BOLD)
    }

    /** Key centres for the current geometry; the glide decoder will read this in Stage 2. */
    fun currentGeometry(): KeyGeometry? = geometry

    private fun dp(v: Float) = v * density

    private fun keyHeightPx(): Float {
        val c = resources.configuration
        // The user's height for how the phone is held now, capped so the keyboard stays about
        // half the screen at most (landscape and cover screens stay short).
        val scale = keyHeightScales.getOrElse(Posture.of(c).ordinal) { 1f }
        return dp(KeySize.rowDp(c.screenHeightDp, scale))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = maxOf(heightRows, layout?.rows?.size ?: 4)
        val h = (rows * keyHeightPx() + dp(8f)).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Fold/unfold, rotation or a height change: old touch points no longer match the keys.
        if (oldw != 0) cancelAll()
        rebuildGeometry(w, h)
        if (w != oldw) updateBorderPaint(w)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        cancelAll()
        requestLayout()
    }

    private fun rebuildGeometry(w: Int, h: Int) {
        led.clearPresses()
        val l = layout ?: return
        if (w <= 0 || h <= 0) return
        val split = isSplit(w, l)
        geometry = KeyGeometry(
            l, w, h,
            // A split keyboard keeps its outer keys off the screen edges, where thumbs can't
            // reach well. The edge keys' touch areas still run to the edge.
            padH = dp(2f) + if (split) w * splitEdge else 0f,
            padV = dp(4f),
            gapH = dp(theme.keyGapHDp), gapV = dp(theme.keyGapVDp),
            splitGap = if (split) w * splitGap else 0f,
        )
    }

    /**
     * The key under ([x], [y]), except near a border between two letters: there the letter
     * whose learned aim point (centre + the user's shift) is closer wins. Shifts are at most
     * 0.4 key, and only touches within [AIM_ZONE] of a letter's face are reconsidered.
     */
    private fun aimedKeyAt(x: Float, y: Float): PlacedKey? {
        val g = geometry ?: return null
        val pk = g.keyAt(x, y)
        val shifts = aimShifts?.invoke()
        if (shifts.isNullOrEmpty() || pk == null || !pk.key.isLetter) return pk
        val kw = (pk.visual.width() + dp(theme.keyGapHDp)).coerceAtLeast(1f)
        val kh = g.rowHeight.coerceAtLeast(1f)
        var best = pk
        var bestD = Float.MAX_VALUE
        for (cand in g.keys) {
            if (!cand.key.isLetter) continue
            val v = cand.visual
            if (x < v.left - kw * AIM_ZONE || x > v.right + kw * AIM_ZONE ||
                y < v.top - kh * AIM_ZONE || y > v.bottom + kh * AIM_ZONE
            ) continue
            val sh = shifts[cand.key.label[0].lowercaseChar()]
            val dx = (x - v.centerX()) / kw - (sh?.get(0) ?: 0f)
            val dy = (y - v.centerY()) / kh - (sh?.get(1) ?: 0f)
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = cand
            }
        }
        return best
    }

    /**
     * Split into two halves? Only on big screens, and never the number pads; a phone in
     * landscape doesn't count (its smallest width is phone-sized).
     */
    private fun isSplit(w: Int, l: KeyboardLayout): Boolean =
        splitOnBigScreens && l.id != LayoutId.NUMBER && l.id != LayoutId.PHONE &&
            BigScreen.isBig(context) && w / density >= BigScreen.MIN_DP

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        // Only our own rectangle: drawColor fills the whole clip, and InputRootView turns
        // child clipping off (for press effects), so it would paint over the suggestion bar.
        val bg = theme.solidBackground
        if (paintBackground && android.graphics.Color.alpha(bg) != 0) {
            backgroundPaint.color = bg
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        }
        val g = geometry ?: return
        val keyH = g.rowHeight
        val scale = theme.labelScale
        // The frame's vsync time (even steps), not the clock at draw time (Session 44).
        val now = ledTicker.now()
        val ledOn = led.active
        val rowCount = g.rows.size
        val w = width.toFloat()
        // Continuous rainbow (wave, rows, rainbow breathing): one gradient across all keys.
        val ledPaint = if (ledOn) led.framePaint(w, height.toFloat(), now) else null
        val ledKeys = ledPaint != null && led.target == LedLights.Target.KEYS
        // Pressed keys, once per frame (no iterator per key at 120 fps).
        pressedNow.clear()
        for (p in pointers.values) if (p.popup == null) p.placed?.let { pressedNow.add(it) }
        for (ri in 0 until rowCount) for (pk in g.rows[ri]) {
            val key = pk.key
            val light = if (ledOn && ledPaint == null) led.colorFor(pk, ri, rowCount, pk.visual.centerX(), w, now) else 0
            val pressed = pressedNow.contains(pk)
            val accentFill = key.kind == KeyKind.ENTER ||
                (key.code == KeyCode.Shift && shift == ShiftState.LOCKED)
            val fill = when {
                pressed -> theme.keyPressed
                accentFill -> theme.accent
                key.kind == KeyKind.LETTER || key.kind == KeyKind.SPACE -> theme.keyLetter
                else -> theme.keyFunction
            }
            // "Key borders" off: only pressed and accent keys get a shape (inside the painter).
            val lit = if (light != 0 && led.target == LedLights.Target.KEYS) LedLights.mix(fill, light) else fill
            val face = painter.draw(canvas, pk.visual, lit, pressed, accentFill || lit != fill || ledKeys)
            if (ledKeys && !pressed) painter.overlay(canvas, ledPaint!!)
            if (ledPaint != null && led.target == LedLights.Target.OUTLINE) painter.outline(canvas, ledPaint, dp(2f))
            if (light != 0 && led.target == LedLights.Target.OUTLINE) painter.outline(canvas, light, dp(2f))

            val label = displayLabel(key)
            if (label.isEmpty()) continue
            textPaint.color = when {
                accentFill && !pressed -> theme.onAccent
                key.code == KeyCode.Shift && shift == ShiftState.ON -> theme.accent
                key.kind == KeyKind.LETTER -> theme.text
                else -> theme.textFunction
            }
            if (light != 0 && led.target == LedLights.Target.LETTERS) {
                textPaint.color = LedLights.mix(textPaint.color, light)
            }
            val small = label.length > 1 && key.kind != KeyKind.LETTER || key.kind == KeyKind.SPACE
            textPaint.textSize = keyH * scale * if (small) 0.26f else 0.40f
            textPaint.typeface = if (small) labelFaceBold else labelFace
            drawCentered(canvas, label, face, textPaint)
            if (ledPaint != null && led.target == LedLights.Target.LETTERS) {
                // The letters again in the rainbow, over the normal ones (alpha = brightness).
                val alpha = ledPaint.alpha
                textPaint.shader = ledPaint.shader
                textPaint.color = android.graphics.Color.WHITE
                textPaint.alpha = alpha
                drawCentered(canvas, label, face, textPaint)
                textPaint.shader = null
                textPaint.alpha = 255
            }

            if (theme.hints) key.hint?.let {
                hintPaint.color = theme.hint
                hintPaint.textSize = keyH * 0.2f
                hintPaint.typeface = labelFace
                canvas.drawText(it, face.right - dp(4f), face.top + hintPaint.textSize + dp(1f), hintPaint)
            }
        }
        // LED animation: 60 frames a second on the display's vsync while a mode moves or keys
        // fade; idle otherwise.
        if (led.animating && isShown) ledTicker.request() else ledTicker.cancel()
    }

    private fun displayLabel(key: Key): String = when (key.code) {
        KeyCode.Enter -> enterLabel
        KeyCode.Space -> spaceLabel
        KeyCode.Shift -> if (shift == ShiftState.OFF) "⇧" else "⬆"
        else -> if (key.isLetter && showUpper()) {
            upperLabels.getOrPut(key.label) { key.label.uppercase(locale) }
        } else {
            key.label
        }
    }

    private fun showUpper(): Boolean = when (theme.labelCase) {
        com.bori.core.keyboard.theme.LabelCase.UPPER -> true
        com.bori.core.keyboard.theme.LabelCase.LOWER -> false
        com.bori.core.keyboard.theme.LabelCase.AUTO -> shift != ShiftState.OFF
    }

    private fun drawCentered(canvas: Canvas, text: String, r: RectF, paint: Paint) {
        val y = r.centerY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, r.centerX(), y, paint)
    }

    // ---------------------------------------------------------------- touch

    private fun keyWidthPx(): Float = geometry?.let { g ->
        // Median, so the wide edge keys and the keys beside a split gap don't skew it.
        val widths = g.keys.filter { it.key.kind == KeyKind.LETTER }.map { it.hit.width() }.sorted()
        if (widths.isEmpty()) g.width / 10f else widths[widths.size / 2]
    } ?: (width / 10f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Extra fingers are ignored while a glide is in progress.
                if (pointers.values.any { it.mode == Mode.GLIDE }) return true
                // Rollover: a new finger commits keys still held by other fingers.
                for (p in pointers.values) {
                    if (p.mode == Mode.KEY && !p.consumed && p.popup == null) commit(p)
                }
                val i = e.actionIndex
                val p = Pointer(e.getPointerId(i))
                p.x = e.getX(i)
                p.y = e.getY(i)
                p.downX = p.x
                p.downY = p.y
                p.downTime = e.eventTime
                p.addPoint(p.x, p.y, e.eventTime)
                pointers[p.id] = p
                val pk = aimedKeyAt(p.x, p.y)
                p.downKey = pk
                press(p, pk, slid = false)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val p = pointers[e.getPointerId(i)] ?: continue
                    // Batched intermediate points keep fast swipes accurate.
                    for (h in 0 until e.historySize) {
                        p.addPoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h))
                    }
                    p.x = e.getX(i)
                    p.y = e.getY(i)
                    p.addPoint(p.x, p.y, e.eventTime)
                    move(p, p.x, p.y, e.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointers.remove(e.getPointerId(e.actionIndex))?.let { release(it, e.eventTime) }
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun press(p: Pointer, pk: PlacedKey?, slid: Boolean) {
        clearTimers(p)
        p.placed = pk
        p.keyX = p.x
        p.keyY = p.y
        p.slid = slid
        p.consumed = false
        if (pk == null) {
            updatePreview(p)
            return
        }
        val key = pk.key
        listener?.onKeyPressed(key)
        if (!slid) {
            led.onPress(pk, android.os.SystemClock.uptimeMillis())
            effects?.burst(p.x, p.y, pk.visual)
            if (led.active) invalidate()
        }
        if (!slid && (key.code == KeyCode.Shift || key.code == KeyCode.Delete)) {
            // These act on touch-down so they can be held while typing with another finger.
            p.consumed = true
            listener?.onKey(key, p.x, p.y)
            if (key.code == KeyCode.Delete) startRepeat(p, key)
        } else {
            val r = Runnable { onLongPress(p) }
            p.longPress = r
            // Space gets longer so a slow start of a cursor slide doesn't open the picker.
            handler.postDelayed(r, if (key.code == KeyCode.Space) SPACE_LONG_PRESS_MS else LONG_PRESS_MS)
        }
        updatePreview(p)
        invalidate()
    }

    private fun move(p: Pointer, x: Float, y: Float, time: Long) {
        p.popup?.let { popup ->
            overlay?.updatePopupSelection(popup, x)
            return
        }
        val kw = keyWidthPx()
        when (p.mode) {
            Mode.GLIDE -> {
                if (glideTrail) overlay?.setTrail(this, p.pathX, p.pathY, p.pathCount, trailColorOrAccent())
                return
            }
            Mode.SPACE_CURSOR -> {
                val steps = ((x - p.downX) / (kw * CURSOR_STEP)).toInt()
                if (steps != p.steps) {
                    listener?.onCursorMove(steps - p.steps)
                    p.steps = steps
                }
                return
            }
            Mode.DELETE_SLIDE -> {
                val words = maxOf(0, ((p.downX - x) / (kw * DELETE_WORD_STEP)).toInt())
                if (words != p.steps) {
                    p.steps = words
                    listener?.onDeleteSlide(words)
                }
                return
            }
            Mode.KEY -> Unit
        }
        val down = p.downKey
        val dx = x - p.downX
        val dy = y - p.downY
        if (down != null) {
            when {
                down.key.code == KeyCode.Space && spaceCursorEnabled && kotlin.math.abs(dx) > kw * SLIDE_START -> {
                    enterSlide(p, Mode.SPACE_CURSOR)
                    return
                }
                down.key.code == KeyCode.Delete && deleteSlideEnabled && dx < -kw * SLIDE_START -> {
                    enterSlide(p, Mode.DELETE_SLIDE)
                    p.steps = 0
                    move(p, x, y, time)
                    return
                }
                glideEnabled && pointers.size == 1 && !p.consumed && down.key.isLetter &&
                    kotlin.math.hypot(dx, dy) > kw * GLIDE_START -> {
                    // A quick short upward stroke may still become a flick; wait and see.
                    if (!looksLikeFlick(p, dx, dy, time)) {
                        startGlide(p)
                        return
                    }
                }
            }
        }
        val pk = aimedKeyAt(x, y)
        if (pk === p.placed) return
        // Hysteresis (as in AOSP/HeliBoard): the finger has to go clearly past the edge of the
        // key it is on before the touch moves to the neighbour, so a wobble at a key border
        // doesn't swap the letter.
        p.placed?.let { cur ->
            val outX = maxOf(cur.hit.left - x, x - cur.hit.right, 0f)
            val outY = maxOf(cur.hit.top - y, y - cur.hit.bottom, 0f)
            val kh = geometry?.rowHeight ?: kw
            if (outX < kw * KEY_HYSTERESIS && outY < kh * KEY_HYSTERESIS) return
        }
        if (p.consumed) {
            // Leaving a held delete stops the repeat; nothing else follows a consumed touch.
            if (p.repeat != null) clearTimers(p)
            return
        }
        press(p, pk, slid = true)
    }

    private fun enterSlide(p: Pointer, mode: Mode) {
        clearTimers(p)
        p.mode = mode
        p.consumed = true
        p.steps = 0
        overlay?.hidePreview(p.id)
        invalidate()
    }

    private fun startGlide(p: Pointer) {
        clearTimers(p)
        p.mode = Mode.GLIDE
        p.consumed = true
        p.placed = null
        overlay?.hidePreview(p.id)
        if (glideTrail) overlay?.setTrail(this, p.pathX, p.pathY, p.pathCount, trailColorOrAccent())
        invalidate()
    }

    private fun looksLikeFlick(p: Pointer, dx: Float, dy: Float, time: Long): Boolean {
        if (!keyFlicksEnabled) return false
        val key = p.downKey?.key ?: return false
        if (flickText(key) == null) return false
        val kh = geometry?.rowHeight ?: return false
        return dy < 0 && kotlin.math.abs(dx) < FLICK_SLOPE * -dy && time - p.downTime < FLICK_MS &&
            -dy < kh * FLICK_MAX
    }

    private fun flickText(key: Key): String? {
        if (!key.isLetter) return null
        val t = key.hint ?: key.popup.firstOrNull() ?: return null
        return if (shift != ShiftState.OFF) t.uppercase(locale) else t
    }

    private fun release(p: Pointer, time: Long) {
        clearTimers(p)
        when (p.mode) {
            Mode.GLIDE -> {
                listener?.onGlide(p.pathX, p.pathY, p.pathT, p.pathCount)
                overlay?.fadeTrail()
            }
            Mode.SPACE_CURSOR -> Unit
            Mode.DELETE_SLIDE -> listener?.onDeleteSlideEnd()
            Mode.KEY -> {
                val popup = p.popup
                val kh = geometry?.rowHeight ?: 0f
                val dx = p.x - p.downX
                val dy = p.y - p.downY
                val flick = p.downKey?.key?.let { flickText(it) }
                if (popup != null) {
                    popup.selectedText()?.let { listener?.onPopupText(it) }
                    overlay?.hidePopup()
                } else if (!p.consumed && flick != null && keyFlicksEnabled && dy < -kh * FLICK_MIN &&
                    kotlin.math.abs(dx) < FLICK_SLOPE * -dy && time - p.downTime < FLICK_MS
                ) {
                    p.consumed = true
                    listener?.onPopupText(flick)
                } else if (!p.consumed) {
                    commit(p)
                }
            }
        }
        p.placed = null
        overlay?.hidePreview(p.id)
        invalidate()
    }

    private fun commit(p: Pointer) {
        clearTimers(p)
        p.consumed = true
        val key = p.placed?.key ?: return
        if (key.code == KeyCode.Shift || key.code == KeyCode.Delete) return
        listener?.onKey(key, p.keyX, p.keyY)
        overlay?.hidePreview(p.id)
    }

    private fun onLongPress(p: Pointer) {
        p.longPress = null
        if (p.mode != Mode.KEY) return
        val pk = p.placed ?: return
        val ov = overlay
        if (pk.key.popup.isNotEmpty() && ov != null) {
            val alts = if (shift != ShiftState.OFF && pk.key.isLetter) {
                pk.key.popup.map { it.uppercase(locale) }
            } else {
                pk.key.popup
            }
            ov.hidePreview(p.id)
            p.popup = ov.showPopup(this, pk.visual, alts, theme)
            listener?.onKeyPressed(pk.key)
            invalidate()
        } else if (listener?.onKeyLongPress(pk.key) == true) {
            p.consumed = true
            overlay?.hidePreview(p.id)
            invalidate()
        }
    }

    private fun startRepeat(p: Pointer, key: Key) {
        val r = object : Runnable {
            override fun run() {
                listener?.onKey(key, Float.NaN, Float.NaN)
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        p.repeat = r
        handler.postDelayed(r, REPEAT_START_MS)
    }

    private fun clearTimers(p: Pointer) {
        p.longPress?.let { handler.removeCallbacks(it) }
        p.repeat?.let { handler.removeCallbacks(it) }
        p.longPress = null
        p.repeat = null
    }

    private fun updatePreview(p: Pointer) {
        val ov = overlay ?: return
        val pk = p.placed
        if (!keyPreviewEnabled || pk == null || pk.key.kind != KeyKind.LETTER || p.consumed) {
            ov.hidePreview(p.id)
            return
        }
        ov.showPreview(this, p.id, pk.visual, displayLabel(pk.key), theme)
    }

    fun cancelAll() {
        for (p in pointers.values) clearTimers(p)
        pointers.clear()
        overlay?.hideAll()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        effects?.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LONG_PRESS_MS = 350L
        const val SPACE_LONG_PRESS_MS = 600L
        const val REPEAT_START_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
        const val MAX_PATH = 4000

        /** Movement (in key widths) before a letter touch becomes a glide. */
        const val GLIDE_START = 0.6f
        /** How far (in key widths/heights) past a key's edge a finger goes before it changes key. */
        const val KEY_HYSTERESIS = 0.25f
        /** How far past a letter's face (in keys) a touch can still be given to it by aim. */
        const val AIM_ZONE = 0.3f
        /** Movement (in key widths) before a space/delete touch becomes a slide. */
        const val SLIDE_START = 0.6f
        /** Key widths of spacebar travel per cursor step. */
        const val CURSOR_STEP = 0.45f
        /** Key widths of delete-slide travel per word. */
        const val DELETE_WORD_STEP = 0.9f

        /** Upward flick: quick, mostly vertical, between FLICK_MIN and FLICK_MAX key heights. */
        const val FLICK_MS = 300L
        const val FLICK_SLOPE = 0.6f
        const val FLICK_MIN = 0.5f
        const val FLICK_MAX = 1.6f
    }
}
