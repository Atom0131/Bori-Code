package com.bori.app.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.window.core.layout.WindowWidthSizeClass

/**
 * How much room the window has, and where a fold sits. Read through [LocalWindowLayout]; the
 * shell picks the bottom bar or the rail from it, and the unfolded workspace splits at the hinge.
 */
@Immutable
data class WindowLayout(
    val width: Width = Width.Compact,
    /** A vertical fold's left and right edges in window pixels, if there is one. */
    val hingePx: ClosedFloatingPointRange<Float>? = null,
) {
    enum class Width { Compact, Medium, Expanded }

    val isCompact: Boolean get() = width == Width.Compact
    val isExpanded: Boolean get() = width == Width.Expanded
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout() }

@Composable
fun rememberWindowLayout(): WindowLayout {
    val info = currentWindowAdaptiveInfo()
    val width = when (info.windowSizeClass.windowWidthSizeClass) {
        WindowWidthSizeClass.EXPANDED -> WindowLayout.Width.Expanded
        WindowWidthSizeClass.MEDIUM -> WindowLayout.Width.Medium
        else -> WindowLayout.Width.Compact
    }
    val hinge = info.windowPosture.hingeList.firstOrNull { it.isVertical }?.bounds?.let { it.left..it.right }
    return WindowLayout(width, hinge)
}
