package com.bori.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.bori.app.ui.adaptive.LocalWindowLayout

/** What the workspace's second pane shows. */
enum class WorkspacePane(val label: String) { EDITOR("Code"), TERMINAL("Terminal") }

/** Which side, if either, fills the whole workspace. */
enum class Maximized { NONE, CHAT, PANE }

/** A side of the workspace; the one touched last is what the cover screen shows after folding. */
enum class WorkspaceSide { CHAT, PANE }

/**
 * False while a screen is composed but not on screen (the other tab on a phone, the hidden side
 * of the workspace, or under Projects/More). Screens use it to drop keyboard focus and stop
 * holding the screen on.
 */
val LocalPaneVisible = compositionLocalOf { true }

/**
 * Chat, Code and Terminal, composed once for the life of the app. Unfolded it's Chat on the left
 * and Code or Terminal on the right, split at the hinge by default; the divider drags, and
 * double-tap it, or use the arrows, to give one side the whole screen. On a phone or the cover
 * screen one side is maximized and the bottom bar picks it. Every pane stays composed (hidden
 * ones at zero width), so folding, unfolding or switching never rebuilds a screen: a half-typed
 * message, a scrolled file and a running terminal carry straight across.
 */
@Composable
fun WorkspaceScreen(
    pane: WorkspacePane,
    onPaneChange: (WorkspacePane) -> Unit,
    maximized: Maximized,
    onMaximizedChange: (Maximized) -> Unit,
    showHeader: Boolean,
    /** The chat's share of the width; null until the user drags, then the hinge or half is used. */
    fraction: Float?,
    onFractionChange: (Float) -> Unit,
    /** False while another screen (Projects, More…) covers the workspace. */
    visible: Boolean,
    onSideTouched: (WorkspaceSide) -> Unit,
    chat: @Composable () -> Unit,
    editor: @Composable () -> Unit,
    terminal: @Composable () -> Unit,
) {
    val hingePx = LocalWindowLayout.current.hingePx
    var originX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            .onGloballyPositioned {
                originX = it.positionInWindow().x
                widthPx = it.size.width.toFloat()
            },
    ) {
        // Default split: where the fold is, measured in this area's own coordinates.
        val hingeFraction = hingePx?.takeIf { widthPx > 0f }
            ?.let { (((it.start + it.endInclusive) / 2f) - originX) / widthPx }
            ?.takeIf { it in 0.25f..0.75f }
        val split = (dragFraction ?: fraction ?: hingeFraction ?: 0.5f).coerceIn(MIN_FRACTION, 1f - MIN_FRACTION)
        val chatWeight = when (maximized) {
            Maximized.CHAT -> 1f
            Maximized.PANE -> 0f
            Maximized.NONE -> split
        }
        val density = LocalDensity.current
        val totalPx = with(density) { maxWidth.toPx() }

        Row(modifier = Modifier.fillMaxSize()) {
            PaneSlot(
                weight = chatWeight,
                visible = visible,
                onTouch = { onSideTouched(WorkspaceSide.CHAT) },
                content = chat,
            )
            if (maximized == Maximized.NONE) {
                SplitDivider(
                    onDrag = { delta ->
                        val next = ((dragFraction ?: split) + delta / totalPx).coerceIn(MIN_FRACTION, 1f - MIN_FRACTION)
                        dragFraction = next
                    },
                    onDragEnd = { dragFraction?.let(onFractionChange); dragFraction = null },
                    onDoubleTap = { onMaximizedChange(Maximized.PANE) },
                )
            }
            PaneSlot(
                weight = 1f - chatWeight,
                visible = visible,
                onTouch = { onSideTouched(WorkspaceSide.PANE) },
            ) {
                // The header takes the status bar's space, so the pane's own top bar doesn't too.
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    if (showHeader) {
                        PaneHeader(
                            pane = pane,
                            onPaneChange = onPaneChange,
                            maximized = maximized,
                            onMaximizedChange = onMaximizedChange,
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        HiddenUnless(pane == WorkspacePane.EDITOR, editor)
                        HiddenUnless(pane == WorkspacePane.TERMINAL, terminal)
                    }
                }
            }
        }
    }
}

/** A pane of the row at [weight]; at zero it stays composed but takes no room. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.PaneSlot(
    weight: Float,
    visible: Boolean,
    onTouch: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shown = weight > 0f
    val modifier = if (shown) Modifier.weight(weight).fillMaxHeight() else Modifier.width(0.dp).fillMaxHeight().alpha(0f)
    val touch = rememberUpdatedState(onTouch)
    Box(
        modifier.pointerInput(Unit) {
            // Watch, don't consume: the pane still gets every touch.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touch.value()
            }
        },
    ) {
        val parentVisible = LocalPaneVisible.current
        CompositionLocalProvider(LocalPaneVisible provides (parentVisible && visible && shown)) { content() }
    }
}

/** Keeps [content] composed while hidden, so its Android views survive. */
@Composable
private fun HiddenUnless(visible: Boolean, content: @Composable () -> Unit) {
    val parentVisible = LocalPaneVisible.current
    Box(if (visible) Modifier.fillMaxSize() else Modifier.size(0.dp).alpha(0f)) {
        CompositionLocalProvider(LocalPaneVisible provides (parentVisible && visible)) { content() }
    }
}

/** Switches the right pane between Code and Terminal, and gives either side the whole screen. */
@Composable
private fun PaneHeader(
    pane: WorkspacePane,
    onPaneChange: (WorkspacePane) -> Unit,
    maximized: Maximized,
    onMaximizedChange: (Maximized) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = { onMaximizedChange(if (maximized == Maximized.PANE) Maximized.NONE else Maximized.PANE) },
        ) {
            Icon(
                if (maximized == Maximized.PANE) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = if (maximized == Maximized.PANE) "Show chat" else "Hide chat",
            )
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f, fill = false)) {
            WorkspacePane.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = pane == p,
                    onClick = { onPaneChange(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, WorkspacePane.entries.size),
                    label = { Text(p.label) },
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** The bar between Chat and the other pane: drag to resize, double-tap to hide the chat. */
@Composable
private fun SplitDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit, onDoubleTap: () -> Unit) {
    Box(
        modifier = Modifier
            .width(12.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .draggable(rememberDraggableState(onDrag), Orientation.Horizontal, onDragStopped = { onDragEnd() })
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(48.dp)
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
    }
}

private const val MIN_FRACTION = 0.25f
