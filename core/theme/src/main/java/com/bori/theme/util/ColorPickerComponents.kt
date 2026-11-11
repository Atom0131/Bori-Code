package com.bori.theme.util

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.math.*
import androidx.compose.foundation.layout.Box
import com.bori.theme.components.CrystalWindowContent
import com.bori.theme.components.crystalScaffoldColor
import com.bori.theme.components.crystalSliderColors
import com.bori.theme.components.drawCrystalMeshField
import com.bori.theme.AmbientBaseDark
import com.bori.theme.AmbientBaseLight
import com.bori.theme.crystalCustomBlobs
import com.bori.theme.crystalComboBlobs
import com.bori.theme.settings.MIN_CRYSTAL_MESH_CUSTOM_CHROMA
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.luminance

/**
 * The one shell both Crystal colour-picker dialogs wear — [CustomColorPickerDialog] and
 * [CrystalCustomFieldDialog] used to each build this by hand (Dialog → clipped Box →
 * [CrystalWindowContent] → title [Surface]/[Column]) and had drifted apart only in incidental ways
 * (28dp vs no corner-radius constant, a stray comment). Extracted so a future fix to the shell —
 * e.g. the "transparent card on glass" regression documented on [CrystalCustomFieldDialog] — only
 * has to be made once.
 */
@Composable
private fun ColorPickerDialogShell(
    width: Dp,
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        // Another window painting a translucent role with nothing behind it — the same defect as
        // the Journal New Entry screen. Clip on the outside so the field is bounded to the dialog's
        // shape, then let the Surface be transparent and the gradient through.
        Box(modifier = Modifier.width(width).clip(RoundedCornerShape(28.dp))) {
        CrystalWindowContent {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = crystalScaffoldColor(MaterialTheme.colorScheme.surface),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth()
                )

                content()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    content = actions
                )
            }
        } // Surface
        } // CrystalWindowContent
        } // clip Box
    }
}

/**
 * The custom-colour picker: a honeycomb of ready-made colours, one tap per choice.
 *
 * It replaced a hue wheel plus a saturation/value square, which needed two separate drags to reach a
 * colour and made a pleasing one hard to land on (requested 2026-10-09, after the Figma honeycomb
 * picker). The cells are a hexagon of [HONEYCOMB_RINGS] rings: hue runs around the centre, and
 * lightness falls from white at the middle to deep at the rim, so every hue is offered as a pastel, a
 * vivid and a dark. A row of greys sits underneath.
 *
 * Wears [ColorPickerDialogShell] unchanged, which already handles Crystal, Material 3 and Expressive.
 * The cells themselves stay opaque in every theme: their whole job is to show a colour exactly.
 */
@Composable
fun CustomColorPickerDialog(
    initialColor: Color = Color.Blue,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val cells = remember { honeycombCells() }
    var selected by remember { mutableIntStateOf(nearestCellIndex(cells, initialColor)) }
    val selectedColor = cells[selected].color

    ColorPickerDialogShell(
        width = 300.dp,
        title = "Pick a colour",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
            TextButton(onClick = {
                onColorSelected(selectedColor)
                onDismiss()
            }) {
                Text("Select")
            }
        }
    ) {
        HoneycombPicker(
            cells = cells,
            selectedIndex = selected,
            onSelect = { selected = it },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(selectedColor)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Selected colour",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "#%06X".format(selectedColor.toArgb() and 0xFFFFFF),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Rings around the centre cell: 6 gives 127 cells, 13 across. */
private const val HONEYCOMB_RINGS = 6
private const val GREY_STEPS = 9

/**
 * One hexagon. [q]/[r] are axial coordinates for the honeycomb; greys carry `r = Int.MIN_VALUE` and
 * their position in [greyIndex] instead.
 */
private data class HexCell(val q: Int, val r: Int, val greyIndex: Int, val color: Color)

private fun honeycombCells(): List<HexCell> {
    val n = HONEYCOMB_RINGS
    val cells = mutableListOf<HexCell>()
    for (r in -n..n) for (q in -n..n) {
        val ring = maxOf(abs(q), abs(r), abs(q + r))
        if (ring > n) continue
        val color = if (ring == 0) Color.White else {
            // Centre of the cell in unit-hex space, pointy-top: x = √3(q + r/2), y = 1.5r.
            val x = sqrt(3f) * (q + r / 2f)
            val y = 1.5f * r
            val hue = ((Math.toDegrees(atan2(-y, x).toDouble()).toFloat()) + 360f) % 360f
            // White at the centre, deep at the rim: the inner rings are pastels, the middle ones
            // vivid, the outer ones dark — every hue offered at three strengths.
            val lightness = 1f - ring.toFloat() / n * 0.6f
            Color.hsl(hue, 1f, lightness)
        }
        cells += HexCell(q, r, -1, color)
    }
    for (i in 0 until GREY_STEPS) {
        val l = 1f - i.toFloat() / (GREY_STEPS - 1)
        cells += HexCell(0, Int.MIN_VALUE, i, Color.hsl(0f, 0f, l))
    }
    return cells
}

/** The cell whose colour is closest to [color] in RGB, so reopening the picker lands on it. */
private fun nearestCellIndex(cells: List<HexCell>, color: Color): Int =
    cells.indices.minBy { i ->
        val c = cells[i].color
        val dr = c.red - color.red
        val dg = c.green - color.green
        val db = c.blue - color.blue
        dr * dr + dg * dg + db * db
    }

/**
 * Lays the cells out for a canvas [width] px wide and returns each one's centre plus the hex radius.
 * The honeycomb is centred at the top; the grey row sits one cell-height below it.
 */
private fun honeycombLayout(cells: List<HexCell>, width: Float): Pair<List<Offset>, Float> {
    val n = HONEYCOMB_RINGS
    val radius = width / (sqrt(3f) * (2 * n + 1))
    val cx = width / 2f
    val cy = radius * (1.5f * n + 1f)
    val greyY = cy + radius * (1.5f * n + 2.6f)
    val greyStep = sqrt(3f) * radius
    val greyStartX = cx - greyStep * (GREY_STEPS - 1) / 2f
    val centres = cells.map { c ->
        if (c.greyIndex >= 0) Offset(greyStartX + greyStep * c.greyIndex, greyY)
        else Offset(cx + radius * sqrt(3f) * (c.q + c.r / 2f), cy + radius * 1.5f * c.r)
    }
    return centres to radius
}

private fun hexPath(centre: Offset, radius: Float): Path = Path().apply {
    for (i in 0 until 6) {
        // Pointy-top: the first corner is straight up.
        val angle = Math.toRadians((60.0 * i) - 90.0)
        val x = centre.x + radius * cos(angle).toFloat()
        val y = centre.y + radius * sin(angle).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun HoneycombPicker(
    cells: List<HexCell>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    val layout = remember(widthPx) { if (widthPx > 0f) honeycombLayout(cells, widthPx) else null }
    val rimOuter = MaterialTheme.colorScheme.onSurface
    val rimInner = MaterialTheme.colorScheme.surface

    // Whichever centre is closest to the finger, so a drag sweeps across cells instead of having
    // to land inside the small hexagons exactly.
    fun pick(at: Offset) {
        val (centres, radius) = layout ?: return
        val i = centres.indices.minBy { (centres[it] - at).getDistanceSquared() }
        if ((centres[i] - at).getDistance() <= radius * 1.6f) onSelect(i)
    }

    BoxWithConstraints(modifier = modifier) {
        val w = maxWidth
        // The honeycomb is 3n + 2 radii tall; the grey row's centre sits 1.6 radii below its last
        // row (see [honeycombLayout]), plus one radius for the row itself.
        val n = HONEYCOMB_RINGS
        val radiusDp = w / (sqrt(3f) * (2 * n + 1))
        val heightDp = radiusDp * (3f * n + 4.6f)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(layout) {
                    detectTapGestures { pick(it) }
                }
                .pointerInput(layout) {
                    detectDragGestures(onDragStart = { pick(it) }) { change, _ -> pick(change.position) }
                }
        ) {
            val (centres, radius) = layout ?: return@Canvas
            cells.forEachIndexed { i, cell ->
                // 0.92: a hairline of the dialog shows between cells, which is what reads as a
                // honeycomb rather than a smeared colour field.
                drawPath(hexPath(centres[i], radius * 0.92f), cell.color)
            }
            val sel = centres[selectedIndex]
            drawPath(hexPath(sel, radius * 1.02f), rimOuter, style = Stroke(width = 3.dp.toPx()))
            drawPath(hexPath(sel, radius * 0.80f), rimInner, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

@Composable
fun ColorWheel(
    hue: Float,
    onHueChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onHueChanged(calculateHue(offset, size))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onHueChanged(calculateHue(change.position, size))
                }
            }
    ) {
        val radius = size.minDimension / 2f
        val center = size.center
        val strokeWidth = 20.dp.toPx()

        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                    Color.Blue, Color.Magenta, Color.Red
                ),
                center = center
            ),
            radius = radius - strokeWidth / 2,
            style = Stroke(width = strokeWidth)
        )

        // Hue indicator
        val angle = (hue / 360f) * 2 * PI
        val x = center.x + (radius - strokeWidth / 2) * cos(angle).toFloat()
        val y = center.y + (radius - strokeWidth / 2) * sin(angle).toFloat()

        drawCircle(
            color = Color.White,
            radius = 8.dp.toPx(),
            center = Offset(x, y),
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = Color.Black,
            radius = 7.dp.toPx(),
            center = Offset(x, y),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

private fun calculateHue(offset: Offset, size: IntSize): Float {
    val center = Offset(size.width / 2f, size.height / 2f)
    val dx = offset.x - center.x
    val dy = offset.y - center.y
    var angle = atan2(dy, dx) * 180f / PI.toFloat()
    if (angle < 0) angle += 360f
    return angle
}

@Composable
fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onValueSelected: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                    onValueSelected(s, v)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val s = (change.position.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    onValueSelected(s, v)
                }
            }
    ) {
        // Hue background
        drawRect(color = Color.hsv(hue, 1f, 1f))

        // White to transparent (saturation)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.White, Color.Transparent)
            )
        )

        // Black to transparent (value)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black)
            )
        )

        // Selection indicator
        val x = saturation * size.width
        val y = (1f - value) * size.height

        drawCircle(
            color = Color.White,
            radius = 6.dp.toPx(),
            center = Offset(x, y),
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = Color.Black,
            radius = 5.dp.toPx(),
            center = Offset(x, y),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

/**
 * Builds a [com.bori.theme.settings.CrystalMesh.CUSTOM] field from one colour.
 *
 * **Only the hue is taken, and the preview says so.** The five stops are solved by
 * `crystalCustomBlobs` at Aurora's tones and under Aurora's chroma caps — see `CrystalCustomMesh.kt`
 * for why the tones are not the user's to pick. Someone dragging the colour wheel's radius (its
 * saturation) will see the preview barely move, which is honest: that does not change the field.
 *
 * The preview is drawn with **`drawCrystalMeshField`, the same function the real field uses**, so it
 * cannot drift from what tapping Save produces — the same rule the ten shipped swatches follow.
 *
 * **The picker sits on an opaque card, and in a colour picker that is not a style choice.** The first
 * version put the preview and the wheel straight onto the dialog's own glass, which under a mesh is
 * `crystalScaffoldColor` — transparent — so both were judged against the *live field showing
 * through them*, in the same pale lavender family as the thing being previewed. Reported as
 * "so transparent… you cannot really see the real hue of the colour you picked", and correctly: a
 * field held at Aurora's tones is a pastel by construction, and a pastel on a coloured translucent
 * backdrop of a similar tone has almost nothing left to read. The shell stays glass; the two surfaces
 * that carry colour information do not. `.copy(alpha = 1f)` is what forces that, because
 * `withTranslucentSurfaces` has already put alpha on every neutral role by the time this runs.
 *
 * The solid disc in the wheel's centre is the other half. The field cannot show the hue at full
 * chroma — that is the whole design — so something on screen has to, or "which colour did I pick"
 * has no answer anywhere in the dialog.
 */
@Composable
fun CrystalCustomFieldDialog(
    initialColors: List<Color>,
    initialChroma: Float,
    onSave: (List<Color>, Float) -> Unit,
    onDismiss: () -> Unit
) {
    // **A gradient of one to four colours, the way wallpaper apps build them.** The field keeps
    // Aurora's tones whatever is picked (see `crystalComboBlobs`), so only each colour's hue — and how
    // colourful it is, for whites and greys — reaches the screen. That is what keeps cards and text
    // at the same contrast on every combo.
    var colors by remember {
        mutableStateOf(initialColors.ifEmpty { listOf(Color(0xFF74D0C4)) }.take(4))
    }
    var selected by remember { mutableIntStateOf(0) }
    var chroma by remember { mutableFloatStateOf(initialChroma) }
    val current = colors[selected.coerceIn(0, colors.lastIndex)]
    val currentHue = remember(current) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(current.toArgb(), it) }[0]
    }

    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val base = if (dark) AmbientBaseDark else AmbientBaseLight
    val blobs = crystalComboBlobs(colors, chroma, dark)

    ColorPickerDialogShell(
        width = 320.dp,
        title = "Custom Field",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { onSave(colors, chroma); onDismiss() }) { Text("Save") }
        }
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // The real field, at swatch size.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .drawBehind { drawCrystalMeshField(base, blobs, dark) }
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 1f), RoundedCornerShape(16.dp))
                )

                // The colours, in order from the top of the screen to the bottom. Tap one to edit it
                // on the wheel; long-press removes it; + adds one (up to four).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colors.forEachIndexed { i, c ->
                        val isSel = i == selected
                        Box(
                            modifier = Modifier
                                .size(if (isSel) 38.dp else 32.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    if (isSel) 3.dp else 1.dp,
                                    if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                                .pointerInput(i, colors.size) {
                                    detectTapGestures(
                                        onTap = { selected = i },
                                        onLongPress = {
                                            if (colors.size > 1) {
                                                colors = colors.toMutableList().also { it.removeAt(i) }
                                                selected = selected.coerceAtMost(colors.lastIndex)
                                            }
                                        }
                                    )
                                }
                        )
                    }
                    if (colors.size < 4) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .pointerInput(colors.size) {
                                    detectTapGestures {
                                        colors = colors + Color.hsv((currentHue + 60f) % 360f, 0.7f, 0.9f)
                                        selected = colors.lastIndex
                                    }
                                }
                        ) {
                            Text("+", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                ColorWheel(
                    hue = currentHue,
                    onHueChanged = { h ->
                        colors = colors.toMutableList().also { it[selected] = Color.hsv(h, 0.75f, 0.95f) }
                    },
                    modifier = Modifier.size(170.dp)
                )

                // One-tap combos. Borikén is the light-blue flag's celeste, white and red.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CUSTOM_FIELD_PRESETS.forEach { (name, preset) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.pointerInput(name) {
                                detectTapGestures { colors = preset; selected = 0 }
                            }
                        ) {
                            if (name == "Borikén") {
                                // The light-blue flag itself — three plain stripes read as France.
                                CelesteFlag(Modifier.size(36.dp, 22.dp))
                            } else {
                                Row {
                                    preset.forEach { c ->
                                        Box(Modifier.size(12.dp, 22.dp).background(c))
                                    }
                                }
                            }
                            Text(name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Depth", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = chroma,
                onValueChange = { chroma = it },
                valueRange = MIN_CRYSTAL_MESH_CUSTOM_CHROMA..1f,
                colors = crystalSliderColors()
            )
        }
    }
}

/** Preset combos for the custom field, top-to-bottom order. */
private val CUSTOM_FIELD_PRESETS = listOf(
    "Borikén" to listOf(Color(0xFF3EA6F2), Color(0xFFFFFFFF), Color(0xFFE4002B)),
    "Sunset" to listOf(Color(0xFFF4A340), Color(0xFFE5537A), Color(0xFF7B4FD6)),
    "Ocean" to listOf(Color(0xFF2EC4B6), Color(0xFF3A86FF), Color(0xFF4B3FB5)),
    "Forest" to listOf(Color(0xFF4CAF50), Color(0xFF26A69A))
)

/**
 * A tiny Puerto Rico flag in its light-blue form (Las Celestes): five red and white stripes, a
 * celeste triangle at the hoist, and a white five-pointed star in it.
 */
@Composable
private fun CelesteFlag(modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(2.dp))) {
        val stripe = size.height / 5f
        for (i in 0 until 5) {
            drawRect(
                if (i % 2 == 0) Color(0xFFE4002B) else Color.White,
                topLeft = Offset(0f, stripe * i),
                size = androidx.compose.ui.geometry.Size(size.width, stripe)
            )
        }
        val triW = size.width * 0.45f
        drawPath(
            Path().apply { moveTo(0f, 0f); lineTo(triW, size.height / 2f); lineTo(0f, size.height); close() },
            Color(0xFF3EA6F2)
        )
        // Star centred in the triangle's inscribed area.
        val cx = triW * 0.36f
        val cy = size.height / 2f
        val r = size.height * 0.17f
        val star = Path()
        for (k in 0 until 10) {
            val rad = if (k % 2 == 0) r else r * 0.42f
            val ang = Math.toRadians(-90.0 + k * 36.0)
            val x = cx + rad * cos(ang).toFloat()
            val y = cy + rad * sin(ang).toFloat()
            if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, Color.White)
    }
}
