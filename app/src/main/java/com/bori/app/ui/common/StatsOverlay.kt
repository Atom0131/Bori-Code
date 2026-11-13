package com.bori.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bori.app.stats.StatsFormat
import com.bori.app.stats.StatsPrefs
import com.bori.app.stats.StatsSampler
import com.bori.app.stats.TokenStats
import com.bori.theme.components.CrystalSurface
import kotlin.math.roundToInt

/**
 * The stats panel: the chosen stats in one row, or in a two-column list when [expanded]. Shared by
 * the in-app overlay and the one floating over other apps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsPanel(
    items: List<StatsFormat.Item>,
    expanded: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CrystalSurface(
        modifier = modifier
            .widthIn(max = 320.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        shape = MaterialTheme.shapes.medium,
        fill = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f),
        crystalElevation = 4.dp,
    ) {
        val style = MaterialTheme.typography.labelMedium
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        if (items.isEmpty()) {
            Text("No stats to show", style = style, color = dim, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        } else if (!expanded) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                items.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.label, style = style, color = dim, maxLines = 1)
                        Text(item.value, style = style, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(item.kind.label, style = style, color = dim, modifier = Modifier.widthIn(min = 130.dp), maxLines = 1)
                        Text(
                            item.value, style = style, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The live items for the user's chosen stats. */
@Composable
fun rememberStatsItems(): List<StatsFormat.Item> {
    val settings by StatsPrefs.settings.collectAsStateWithLifecycle()
    val snapshot by StatsSampler.snapshot.collectAsStateWithLifecycle()
    val tokens by TokenStats.state.collectAsStateWithLifecycle()
    return remember(settings.shown, snapshot, tokens) { StatsFormat.items(settings.shown, snapshot, tokens) }
}

/**
 * The overlay inside Bori: a small draggable glass panel over every screen, while Settings → Stats
 * has it on and it isn't floating over other apps instead. Tap: more detail; long-press: settings.
 */
@Composable
fun InAppStatsOverlay(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { StatsPrefs.init(context) }
    val settings by StatsPrefs.settings.collectAsStateWithLifecycle()
    val floating by com.bori.app.flavor.FloatingStats.running.collectAsStateWithLifecycle()
    val show = settings.overlay && !floating
    if (!show) return
    // Sample only while Bori is on screen; outside it the notification or floating panel takes over.
    LifecycleStartEffect(Unit) {
        StatsSampler.need(context, IN_APP, true)
        onStopOrDispose { StatsSampler.need(context, IN_APP, false) }
    }
    val items = rememberStatsItems()
    var expanded by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        // Where the panel is while dragging; saved as fractions when the drag ends.
        var x by remember(settings.x, maxW) { mutableFloatStateOf(settings.x * maxW) }
        var y by remember(settings.y, maxH) { mutableFloatStateOf(settings.y * maxH) }
        val cx = x.coerceIn(0f, (maxW - size.width).coerceAtLeast(0f))
        val cy = y.coerceIn(0f, (maxH - size.height).coerceAtLeast(0f))
        Box(
            Modifier
                .offset { IntOffset(cx.roundToInt(), cy.roundToInt()) }
                .onSizeChanged { size = it }
                .pointerInput(maxW, maxH) {
                    detectDragGestures(
                        onDragEnd = {
                            StatsPrefs.update {
                                it.copy(
                                    x = (x.coerceIn(0f, maxW - size.width) / maxW).coerceIn(0f, 1f),
                                    y = (y.coerceIn(0f, maxH - size.height) / maxH).coerceIn(0f, 1f),
                                )
                            }
                        },
                    ) { change, drag ->
                        change.consume()
                        x = (x + drag.x).coerceIn(0f, (maxW - size.width).coerceAtLeast(0f))
                        y = (y + drag.y).coerceIn(0f, (maxH - size.height).coerceAtLeast(0f))
                    }
                },
        ) {
            StatsPanel(items, expanded, onTap = { expanded = !expanded }, onLongPress = onOpenSettings)
        }
    }
}

private const val IN_APP = "overlay"
