package com.bori.app.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The coding-CLI button's icon: four columns of falling code (the films' digital rain), each fading
 * up from a solid head, in [tint] like the bar's other icons. Static on purpose; an animated icon
 * would redraw the top bar all the time.
 */
@Composable
fun CodeRainIcon(tint: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val cols = 4
        val colW = this.size.width / cols
        val glyph = this.size.height / 7
        // Where each column's head is (in glyphs from the top) and how long its trail is.
        val heads = intArrayOf(5, 3, 6, 4)
        val trails = intArrayOf(4, 3, 5, 3)
        for (c in 0 until cols) {
            val x = c * colW + colW * 0.2f
            for (k in 0..trails[c]) {
                val row = heads[c] - k
                if (row < 0) break
                val alpha = if (k == 0) 1f else 0.75f - k * 0.15f
                drawRoundRect(
                    color = tint.copy(alpha = alpha.coerceAtLeast(0.15f)),
                    topLeft = Offset(x, row * glyph + glyph * 0.15f),
                    size = Size(colW * 0.6f, glyph * 0.7f),
                    cornerRadius = CornerRadius(glyph * 0.15f),
                )
            }
        }
    }
}
