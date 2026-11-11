package com.bori.theme.components

import com.bori.theme.LocalBoriken
import androidx.compose.ui.graphics.Path
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.dp
import com.bori.theme.LocalCrystalRoom
import com.bori.theme.settings.CrystalBackground
import com.bori.theme.settings.CrystalMesh
import com.bori.theme.settings.CrystalStyle
import com.bori.theme.settings.ThemeStyle
import com.bori.theme.AmbientBaseDark
import com.bori.theme.AmbientBaseLight
import com.bori.theme.crystalFieldBase
import com.bori.theme.crystalFieldDilution
import com.bori.theme.CrystalSolidLight
import com.bori.theme.LocalCrystalBackground
import com.bori.theme.LocalCrystalIntensity
import com.bori.theme.LocalCrystalMesh
import com.bori.theme.crystalFieldBlobs
import com.bori.theme.ambientBlobs
import com.bori.theme.LocalCrystalStyle
import com.bori.theme.LocalThemeStyle
import com.bori.theme.crystalSurfaceAlpha
import com.bori.theme.crystalWindowSurfaceAlpha
import com.bori.theme.withTranslucentSurfaces
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.channels.Channel
import kotlin.math.ceil

// =================================================================================================
// The Crystal component kit.
//
// Crystal is a field of soft colour with translucent panels floating over it. A panel does a real
// backdrop blur of that field, tints it lightly, and finishes with a bright rim along its top-left
// edge — light catching glass — plus a soft shadow so it reads as floating rather than printed.
//
// **Every entry point here works in all three theme styles.** Under Material 3 and Material 3
// Expressive the same functions render ordinary flat Material surfaces. That is the whole design of
// this file: a screen names a *tier* — `Modifier.crystalCardSurface()` — and never asks which theme
// is active, which is what makes theming ~80 surfaces across the app a mechanical job instead of ~80
// conditionals that will drift apart.
//
// **A screen picks a tier, never ingredients.** See the tier table below `crystalStickyHeader`. The
// underlying `crystalSurface` is private on purpose: when it was public, every call site chose its
// own shape, fill and elevation, and "why doesn't this box match that box" was reported five rounds
// running. If a new surface does not fit a tier, add a tier here — do not widen the private function.
//
// **The blur is computed once per field, not once per panel per frame.** Panels used to do a live
// backdrop blur each (Haze). The field behind them never moves, so every one of those blurs produced
// the same pixels frame after frame — and with a screen of cards scrolling it was 6-8 of them per
// frame. Measured on the Fold 8 at 120 Hz on 2026-10-11, scrolling the notebook grid: GPU p50 8.3 ms
// (the whole 8.3 ms budget) and 34-56 of 120 frames over 16.6 ms, against 6 ms and 10-12 with panel
// blur switched off. Haze's half-resolution input was tried first and changed nothing. Now the field
// is blurred into one bitmap whenever it changes ([blurredFieldSource]) and each panel draws its
// window of that bitmap ([blurredFieldPanel]) — about the cost of no blur at all.
// =================================================================================================

/**
 * The ambient field, blurred once, as a bitmap a panel can draw its own patch of.
 *
 * [snapshot] is replaced whenever the field changes (theme, mesh, page mark, size, Glass Intensity)
 * and is read **only while drawing**, so a new snapshot repaints panels without recomposing them.
 * [ready] is the composition-facing half: it flips to true once, when the first snapshot lands, and
 * never back — so a panel recomposes exactly once, out of the not-ready fallback.
 */
@Stable
internal class BlurredField {
    var snapshot by mutableStateOf<BlurredFieldSnapshot?>(null)
    var ready by mutableStateOf(false)
}

/**
 * One capture. [origin] is where the field's top-left was **on screen** when it was captured, and
 * [scale] is bitmap pixels per field pixel — kept together so a stale snapshot still lines up: the
 * field is drawn in screen space, so the same screen point always shows the same colour.
 */
internal class BlurredFieldSnapshot(val image: ImageBitmap, val origin: Offset, val scale: Float)

/**
 * The blurred field for panels in scope, provided by [CrystalRoot] and [CrystalWindowContent]. Null
 * means no blur source — a panel then falls back to a translucent fill (Solid, API < 32, a menu).
 */
internal val LocalBlurredField = staticCompositionLocalOf<BlurredField?> { null }

/**
 * Resolution the blurred field is kept at. A 8-24 dp blur hides the scale completely, and half size
 * is a quarter of the memory (≈2.5 MB on the Fold's cover screen rather than ≈10 MB).
 */
private const val BLURRED_FIELD_SCALE = 0.5f

/**
 * Marks this node's content as the field and keeps [field] holding a blurred copy of it.
 *
 * Every time the node draws, its content is recorded into a layer and a capture is requested; the
 * capture blurs that layer at [BLURRED_FIELD_SCALE] and snapshots it. The node draws **only when
 * the field itself changes** — it sits in its own `graphicsLayer`, so the content scrolling above it
 * never re-runs this — which is what makes the capture rare. A changed [blurRadius] (the Glass
 * Intensity slider) re-captures without a redraw.
 */
@Composable
private fun Modifier.blurredFieldSource(field: BlurredField, blurRadius: Dp): Modifier {
    val fieldLayer = rememberGraphicsLayer()
    val blurLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val radiusPx = with(density) { blurRadius.toPx() }
    val requests = remember { Channel<Unit>(Channel.CONFLATED) }
    val geometry = remember { FieldGeometry() }
    LaunchedEffect(field, radiusPx) {
        if (geometry.size != IntSize.Zero) requests.trySend(Unit)
        for (request in requests) {
            val size = geometry.size
            if (size == IntSize.Zero) continue
            captureBlurred(field, fieldLayer, blurLayer, size, geometry.origin, radiusPx, density, layoutDirection)
        }
    }
    return this
        // Offscreen, so the platform keeps the field as a texture and reuses it until the field
        // itself changes. Otherwise it repaints five full-screen radial gradients every frame of a
        // scroll for pixels that never change.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .onGloballyPositioned { geometry.origin = it.positionOnScreen() }
        .drawWithContent {
            fieldLayer.record { this@drawWithContent.drawContent() }
            drawLayer(fieldLayer)
            geometry.size = IntSize(size.width.toInt(), size.height.toInt())
            requests.trySend(Unit)
        }
}

/** Blurs [fieldLayer] (a field of [size] whose top-left is at [origin] on screen) into [field]. */
private suspend fun captureBlurred(
    field: BlurredField,
    fieldLayer: GraphicsLayer,
    blurLayer: GraphicsLayer,
    size: IntSize,
    origin: Offset,
    radiusPx: Float,
    density: androidx.compose.ui.unit.Density,
    layoutDirection: androidx.compose.ui.unit.LayoutDirection
) {
    val s = BLURRED_FIELD_SCALE
    val scaled = IntSize(ceil(size.width * s).toInt(), ceil(size.height * s).toInt())
    blurLayer.renderEffect = BlurEffect(radiusPx * s, radiusPx * s, TileMode.Clamp)
    blurLayer.record(density, layoutDirection, scaled) {
        scale(s, s, pivot = Offset.Zero) { drawLayer(fieldLayer) }
    }
    field.snapshot = BlurredFieldSnapshot(blurLayer.toImageBitmap(), origin, s)
    field.ready = true
}

/**
 * Keeps [field] holding a blur of the **whole screen's** mesh, for a sheet or dialog.
 *
 * A window paints the screen's mesh offset by its own position (see [CrystalWindowContent]), so the
 * patch it shows changes as it slides in, but the screen-space mesh behind it does not. Capturing
 * the window's own field would re-blur on every frame of that slide — 19 captures for one sheet
 * opening, measured. Blurring the full-screen mesh once covers every position the window can be at.
 */
@Composable
private fun BlurredScreenMesh(
    field: BlurredField,
    blurRadius: Dp,
    base: Color,
    blobs: List<Color>,
    dark: Boolean,
    dilution: Float,
    screenSize: Size
) {
    val fieldLayer = rememberGraphicsLayer()
    val blurLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val radiusPx = with(density) { blurRadius.toPx() }
    LaunchedEffect(field, radiusPx, base, blobs, dark, dilution, screenSize) {
        val size = IntSize(screenSize.width.toInt(), screenSize.height.toInt())
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        fieldLayer.record(density, layoutDirection, size) {
            drawCrystalMeshField(base, blobs, dark, fieldSize = screenSize, origin = Offset.Zero, dilution = dilution)
        }
        captureBlurred(field, fieldLayer, blurLayer, size, Offset.Zero, radiusPx, density, layoutDirection)
    }
}

/**
 * A [BlurredField] for this window, or null when [wanted] is false or the device cannot blur (API <
 * 32 — the same line the rest of this file draws for "can blur"; see [fieldMissingUnexpectedly]).
 */
@Composable
private fun rememberBlurredFieldOrNull(wanted: Boolean): BlurredField? {
    val field = remember { BlurredField() }
    return if (wanted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) field else null
}

/** Plain holder, not state: written from layout and draw, read by the capture. */
private class FieldGeometry {
    var size = IntSize.Zero
    var origin = Offset.Zero
}

/**
 * A panel's glass: its window of [field] (the blurred backdrop), then [tint], then the grain — the
 * three layers a Haze panel used to composite. Redraws itself when it moves
 * ([GlobalPositionAwareModifierNode]), because moving a layer does not re-run its draw.
 */
private fun Modifier.blurredFieldPanel(field: BlurredField, tint: Color): Modifier =
    this then BlurredFieldPanelElement(field, tint)

private data class BlurredFieldPanelElement(
    val field: BlurredField,
    val tint: Color
) : ModifierNodeElement<BlurredFieldPanelNode>() {
    override fun create() = BlurredFieldPanelNode(field, tint)
    override fun update(node: BlurredFieldPanelNode) {
        node.field = field
        node.tint = tint
        node.invalidateDraw()
    }
}

private class BlurredFieldPanelNode(
    var field: BlurredField,
    var tint: Color
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var positionOnScreen = Offset.Unspecified

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionOnScreen()
        if (p != positionOnScreen) {
            positionOnScreen = p
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val snap = field.snapshot
        if (snap != null && !positionOnScreen.isUnspecified) {
            val offset = positionOnScreen - snap.origin
            // The whole bitmap, translated and clipped to this panel: float-precise, so the glass
            // doesn't step by a bitmap pixel as a card scrolls, and the GPU only shades the clip.
            clipRect {
                translate(-offset.x, -offset.y) {
                    scale(1f / snap.scale, 1f / snap.scale, pivot = Offset.Zero) {
                        drawImage(snap.image)
                    }
                }
            }
        }
        drawRect(tint)
        drawRect(GrainBrush, alpha = GRAIN_ALPHA)
        drawContent()
    }
}

/** Haze's `noiseFactor`, kept: the grain is what stops a blurred gradient banding. */
private const val GRAIN_ALPHA = 0.05f

/**
 * A 64x64 tile of random grey with random alpha, tiled at one texel per pixel — the same recipe as
 * the noise texture Haze shipped. Seeded, so the grain is identical on every launch.
 */
private val GrainBrush: ShaderBrush by lazy {
    val n = 64
    val rnd = java.util.Random(0x0B0B1)
    val pixels = IntArray(n * n) {
        val v = rnd.nextInt(256)
        (rnd.nextInt(256) shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bitmap = android.graphics.Bitmap.createBitmap(pixels, n, n, android.graphics.Bitmap.Config.ARGB_8888)
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/** True when the Crystal theme is active. The kit's single branch point. */
@Composable
@ReadOnlyComposable
fun isCrystal(): Boolean = LocalThemeStyle.current == ThemeStyle.CRYSTAL

private val isDark: Boolean
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

private val isSheer: Boolean
    @Composable @ReadOnlyComposable
    get() = LocalCrystalStyle.current == CrystalStyle.SHEER

/**
 * True inside a window that paints its **own** field — a bottom sheet or a modal dialog — as opposed
 * to a screen sitting on the app's ambient field.
 *
 * Panels are denser in here, and that is not a preference, it is a consequence of what is behind
 * them. On a screen a control is translucent over the ambient *mesh*, which has enough variation
 * that a light tint still separates from it. In a sheet the backdrop is that sheet's own smooth
 * gradient, so a control tinted for the screen dissolves into it — which is exactly how the timer
 * and journal pickers came out reading as ghosts. Same Glass Intensity slider, denser band.
 */
private val inOwnWindow: Boolean
    @Composable @ReadOnlyComposable
    get() = LocalCrystalInWindow.current

private val LocalCrystalInWindow = staticCompositionLocalOf { false }

/**
 * True when Crystal is drawing one flat colour behind its panels rather than the five-colour mesh.
 *
 * **The blur is skipped entirely in this mode**, and that is not an optimisation so much as an
 * identity: a blur of a flat colour returns that same flat colour, so there is nothing to capture.
 * No [BlurredField] is published and panels draw their pre-composited fill.
 *
 * **The identity is about the blur and not about the opacity, and conflating the two was a bug.**
 * The original note here finished "…to produce a value the translucent fill already gives for free",
 * which quietly assumed the fill's *alpha* was also right. It was not. Skipping the blur dropped panels
 * into the API < 32 fallback branch, whose alpha exists to compensate for missing frosted glass —
 * so a panel came out roughly 74% surface over 26% room where a blurred one is 26% tint over the
 * field, and a coloured room read as a near-black slab. Solid now pre-composites its surface roles
 * instead (`withSolidGlassSurfaces` in `Theme.kt`), which is available precisely *because* the field
 * is one flat known colour: the result can be computed rather than composited at draw time.
 */
private val isSolidField: Boolean
    @Composable @ReadOnlyComposable
    get() = LocalCrystalBackground.current == CrystalBackground.SOLID

/**
 * The flat field's colour.
 *
 * A chosen room colour wins outright — and because every other consumer of the flat field
 * (`AmbientBackground`'s solid branch, `crystalSheetColor`, the no-blur rule) reads through this
 * one function, sheets and dialogs inherit the room with no further work.
 *
 * With no colour chosen, light gets a dedicated tone pitched a clear step below the cards — see [CrystalSolidLight] for why
 * that step is the whole difficulty of a solid Crystal. Dark reads `background`, which is already a
 * real step below its cards *and* is the value AMOLED replaces with true black; a constant here
 * would quietly break that preference.
 */
@Composable
@ReadOnlyComposable
private fun solidFieldColor(): Color =
    LocalCrystalRoom.current
        ?: if (isDark) MaterialTheme.colorScheme.background else CrystalSolidLight

/**
 * The container colour a `Scaffold` should use. Transparent under Crystal so the one ambient field
 * behind the whole app shows through; the normal background otherwise.
 *
 * Every `Scaffold` in the app passes this. A Scaffold left on its default paints an opaque
 * background over the field and the screen simply looks flat — with nothing to indicate why.
 */
@Composable
@ReadOnlyComposable
fun crystalScaffoldColor(fallback: Color = Color.Unspecified): Color = when {
    isCrystal() -> Color.Transparent
    // Borikén in a flat style paints the page and its petroglyphs once, behind every screen
    // (CrystalRoot), so a screen's own background steps aside. Screens only: sheets and dialogs
    // pass a fallback and keep their opaque surface.
    LocalBoriken.current && !fallback.isSpecified -> Color.Transparent
    fallback.isSpecified -> fallback
    else -> MaterialTheme.colorScheme.background
}

/**
 * The content colour to pass alongside [crystalScaffoldColor]. **Passing it is not optional.**
 *
 * `Scaffold` and `Surface` default `contentColor` to `contentColorFor(containerColor)`, and
 * `contentColorFor(Color.Transparent)` is `Color.Unspecified` — Material has no content colour for a
 * colour that isn't one of its roles. That Unspecified is then published as `LocalContentColor` for
 * the entire screen, overriding the one the theme anchors at its root, and every `Text` that doesn't
 * name a colour renders black. In light Crystal that looks fine; in dark Crystal the app's text
 * disappears. Nothing warns, and it compiles.
 *
 * Under the two Material styles this returns exactly what the default would have resolved to, so
 * passing it changes nothing there.
 */
@Composable
@ReadOnlyComposable
fun crystalScaffoldContentColor(): Color = MaterialTheme.colorScheme.onBackground

/**
 * A **sticky list header** as a floating glass panel — inset and rounded, matching the cards it sits
 * above, rather than a bar ruled across the screen.
 *
 * This started life as a near-opaque slab, on the assumption that a glass panel could not hide the
 * rows sliding under a sticky header. **It can, and that assumption is worth correcting out loud:**
 * a panel draws its patch of the blurred field ([blurredFieldPanel]), and that field is the opaque
 * ambient field — so a Crystal panel is opaque by construction and covers whatever passes behind it. The
 * only case where it does not is API < 32, where there is no blur and the fallback tint is
 * translucent; the raised [stickyFallbackBoost] compensates there and nowhere else.
 *
 * **"Nowhere else" is now true.** It used to fire under Solid as well, on every device, because
 * `crystalSurface` chose that branch on "no blur" rather than on "old API" and Solid also has no
 * blur. The cost was visible and was measured on a device: sticky headers rendered at 0.96 while the
 * cards beside them sat near 0.74, landing 1.4 tones lighter and noticeably greyer — the one part of
 * the list that did not take the room's colour. Solid panels are opaque by construction now, so
 * there is nothing to ghost through and nothing to compensate for.
 *
 * Under the two Material styles this is the full-bleed `background` fill these headers have always
 * had, unchanged.
 */
@Composable
fun Modifier.crystalStickyHeader(
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 4.dp
): Modifier = if (isCrystal()) {
    this
        .padding(horizontal = horizontalPadding, vertical = verticalPadding)
        .crystalSurface(
            shape = MaterialTheme.shapes.large,
            fill = MaterialTheme.colorScheme.surfaceContainer,
            crystalElevation = SECTION_ELEVATION,
            stickyFallbackBoost = true
        )
} else {
    this.background(MaterialTheme.colorScheme.background)
}

// =================================================================================================
// The tiers.
//
// **[crystalSurface] is private, and that is the point of this block.** It takes six free parameters,
// and for five rounds every call site picked its own: eight fill roles, five elevations, five shapes,
// one of them a hardcoded `RoundedCornerShape(16.dp)` that bypassed the theme scale entirely. Nothing
// was wrong at any single site — the set simply never agreed, and "why doesn't this box match that
// box" kept being answerable. The tiers below are the closed set that stops it being expressible.
//
// | tier                        | shape         | fill                     | crystalElev | flat elev | rim |
// |-----------------------------|---------------|--------------------------|-------------|-----------|-----|
// | [crystalStickyHeader]       | `large`       | `surfaceContainer`       | 4dp         | full-bleed| yes |
// | [crystalCardSurface]        | `large`       | `surfaceContainer`       | 3dp         | 0         | yes |
// | [crystalControlSurface]     | `small`       | `surfaceContainerHighest`| 2dp         | 2dp       | yes |
// | [crystalTileSurface]        | `medium`      | caller's                 | 2dp         | 0         | yes |
// | [crystalChipSurface]        | `CircleShape` | caller's                 | 2dp         | 0         | yes |
// | [crystalIconButtonSurface]  | `CircleShape` | `surfaceContainerHighest`| 1dp         | 0         | yes |
// | [crystalFabSurface]         | `CircleShape` | `surfaceContainerHighest`| 3dp         | 0         | yes |
// | [crystalChromeSurface]      | `Rectangle`   | `surfaceContainer`       | 0           | 0         | no  |
//
// **Shape, elevation and rim are closed; `fill` is not, and that asymmetry is deliberate.** Colour
// carries meaning in this app and has to survive into the flat themes — a habit's own colour, a
// completed step's wash, an alarm's enabled state, the add-menu tile's `primaryContainer`. Shape and
// shadow carry none, and they are exactly what made surfaces fail to match. So a caller may still say
// *what colour*, and may no longer say *what shape* or *how much shadow*.
//
// **The flat-theme shadow is 0 on every tier that is not already a control**, per `DESIGN.md`'s
// "prefer tonal separation over divider lines and shadows". The two cards that carried a 2dp
// `ElevatedCard` shadow (the notebook card and the journal entry) lose it here; their separation
// comes from the tone step `surfaceContainer` (94) makes against `surface` (98), which is the same
// argument `EditorSection` used when it moved off `surfaceContainerLow` (96).
// =================================================================================================

/** A section heading's shadow — the reference every other tier is pitched against. */
private val SECTION_ELEVATION = 4.dp

/** A content card: one step quieter than a section, so a heading still reads as containing the rows
 * beneath it. Same fill and same shape — the 1dp is the whole of the difference. */
private val CARD_ELEVATION = 3.dp

/** Tiles and chips — small objects, so a section's shadow would read as a drop-shadow sticker. */
private val SMALL_OBJECT_ELEVATION = 2.dp

/**
 * A **content card** — a notebook card, a journal entry, a habit card, the note editor's page, an
 * alarm or timer card, the two counters at the top of Today.
 *
 * One step quieter than [crystalStickyHeader] and otherwise identical to it. That is what makes a
 * Today heading and the cards under it read as the same material with a hierarchy, rather than as
 * two different materials — the defect this tier exists to end.
 *
 * [fill] is open because a card is the surface most likely to carry meaning in its colour. Pass a
 * habit's tinted `cardColor` or an alarm's enabled/disabled role; leave it alone otherwise.
 */
@Composable
fun Modifier.crystalCardSurface(
    fill: Color = MaterialTheme.colorScheme.surfaceContainer
): Modifier = this.crystalSurface(
    shape = MaterialTheme.shapes.large,
    fill = fill,
    crystalElevation = CARD_ELEVATION
)

/**
 * A **square tile** — the add-menu's 48dp type pickers.
 *
 * `medium` rather than `large` because the tier's radius has to be proportionate to the object:
 * Crystal's `large` is 28dp, which on a 48dp box is very nearly a circle, and Expressive's is 40dp,
 * which is more than the box. This is the one place the shape genuinely follows the size rather than
 * the role, which is why it is its own tier and not a `shape` parameter on [crystalCardSurface] —
 * a parameter would be available to every card, and that is how the free-for-all started.
 */
@Composable
fun Modifier.crystalTileSurface(
    fill: Color = MaterialTheme.colorScheme.surfaceContainer
): Modifier = this.crystalSurface(
    shape = MaterialTheme.shapes.medium,
    fill = fill,
    crystalElevation = SMALL_OBJECT_ELEVATION
)

/**
 * A **selection pill** — a selected day in a week strip or a month grid.
 *
 * [fill] is required rather than defaulted: every caller is drawing a chip whose colour *is* its
 * state, so there is no sensible neutral to fall back to and a default would only invite one.
 *
 * Note the call sites route only the **selected** chip through here. An unselected chip has a
 * transparent fill, and painting a rim and a blur around nothing puts a visible pill on all seven
 * days.
 */
@Composable
fun Modifier.crystalChipSurface(fill: Color): Modifier = this.crystalSurface(
    shape = CircleShape,
    fill = fill,
    crystalElevation = SMALL_OBJECT_ELEVATION
)

/**
 * **Chrome fixed to the screen edge** — the navigation rail.
 *
 * The two departures from every other tier are the same single reason: this is not a floating panel.
 * It gets **no rim**, because a specular edge on something bolted to the display edge reads as a seam
 * rather than as light catching glass; and **no shadow**, because there is nothing for it to float
 * above. `RectangleShape` for the same reason — a rounded corner against the screen edge is a gap.
 *
 * [CrystalBar] is the other member of this family and is deliberately *not* routed through here: a
 * bar reads [LocalBlurredField] itself so that it can take the three-way blur/Solid/old-API split, which
 * is the one Crystal surface that does not go through [crystalSurface] at all.
 */
@Composable
fun Modifier.crystalChromeSurface(
    fill: Color = MaterialTheme.colorScheme.surfaceContainer
): Modifier = this.crystalSurface(
    shape = RectangleShape,
    fill = fill,
    crystalElevation = 0.dp,
    rim = false
)

/**
 * The base colour of a window that paints its own field — a bottom sheet or a modal dialog.
 *
 * **This is a fallback, not a way to continue the field, and treating it as the latter was a bug.**
 * It returns the panel gradient's *first stop*, which under [CrystalFieldStyle.Panel] is the colour
 * at the field's top-**left** corner only, because that field is a diagonal gradient. Painting a
 * strip of chrome with it therefore matches the field along one edge and drifts from it across the
 * width: a sheet's 40dp handle strip measured a constant (184,226,224) against a field running
 * (184,224,226) to (194,201,245) one pixel below, and read as a mint band across the top of every
 * sheet and both ends of every menu.
 *
 * The fix is to make the field reach the chrome — see [crystalMenuField], which paints it from
 * outside the shell's own padding, and
 * `BoriSheet`, which moved its handle inside the field and needs no colour here at all. What is
 * left for this function is the honest job: **the flat colour a window shows under Material 3 and
 * Expressive**, where no field is drawn, and the Solid branch, where the field genuinely *is* one
 * flat colour and so a flat fill matches it exactly.
 */
@Composable
@ReadOnlyComposable
fun crystalSheetColor(fallback: Color = Color.Unspecified): Color = when {
    isCrystal() && isSolidField -> solidFieldColor()
    isCrystal() -> panelFieldStops(LocalCrystalMesh.current, isDark).first()
    fallback.isSpecified -> fallback
    else -> MaterialTheme.colorScheme.surfaceContainerLow
}

/**
 * **The icon tint rule, recorded here because it is the thing that drifted.**
 *
 * There is no icon *asset* problem in this app — there are zero `painterResource(R.drawable…)` calls
 * anywhere under `ui/`, so every in-app icon is already a Material built-in. What was reported as
 * "the icons don't match" was entirely a tint choice made ad hoc at ~116 call sites, and the tell was
 * that the **same glyph doing the same job** was `primary` in one place and `onSurfaceVariant` in
 * three others — the edit pencil was accent on a timer card and grey on a habit row, a to-do row and
 * the habit detail sheet.
 *
 * The rule, which is also how iOS tints a navigation bar:
 *
 * - **`primary`** — anything that identifies a screen or performs an action: a header's Settings
 *   gear, a view toggle, an edit pencil, a screen's own glyph.
 * - **`onSurfaceVariant`** — chevrons and expand arrows, status glyphs (the privacy padlock), and
 *   inline metadata. These are furniture, not actions, and accenting them makes the real actions
 *   stop reading as actions.
 * - **`error`** — destructive confirmation only.
 * - **No tint at all** is correct for the ~105 icons inside a `Button`, `IconButton` or list row that
 *   should follow `LocalContentColor`; don't pin those.
 *
 * A new icon picks one of these rather than a colour that looks right in the screen being written.
 */
/**
 * How much of a control's own fill survives under Crystal. The checklist "Add item" row arrived at
 * this number by hand (`surfaceContainerHighest.copy(alpha = 0.5f)`) and is the one control in the
 * pickers nobody complained about, so it is the value the rest are being brought to.
 */
private const val CONTROL_FILL_SCALE = 0.5f

/**
 * A **tint**, and — since the tiers landed — no longer the way to build a control.
 *
 * **This returns an alpha and nothing else: no backdrop blur, no specular rim.** That is why every
 * `Surface(color = crystalControlColor(…))` row read as flat beside the panels around it, reported
 * across four rounds as the editor sheets, the picker triggers, the template rows and the move-note
 * rows. Those now take [Modifier.crystalControlSurface], which is real glass. **Reach for a tier
 * first; this function is not a substitute for one.**
 *
 * Two jobs are genuinely left, and both are about colour rather than material:
 *
 * - **Accent-container roles**, the reason it must not be deleted. `withTranslucentSurfaces` and
 *   `withSolidGlassSurfaces` rewrite the nine neutral `surface*` roles and **nothing else**, so
 *   `primaryContainer` / `secondaryContainer` / `errorContainer` are permanently opaque in every
 *   theme unless something halves them — which is what this does under a mesh, while returning
 *   Material's own value under the flat themes. Seven call sites, and they are the whole remaining
 *   justification for the function existing.
 * - **Small decorative shapes that are not panels** — a 16dp lock badge, the backdrop behind an app
 *   icon preview, a keypad key. Putting a specular rim and a floating shadow on a 16dp dot is worse
 *   than the tint it replaces, so these deliberately stay.
 *
 * Pair it with [crystalControlElevation], the same way [crystalScaffoldColor] pairs with
 * [crystalScaffoldContentColor].
 *
 * **This exists because "the roles carry alpha, so untouched call sites convert for free" stops
 * being true inside a sheet.** `CrystalWindowContent` deliberately re-derives the surface roles far
 * denser in a sheet or dialog — up to 0.84–0.88 — so that controls do not dissolve into the sheet's
 * own gradient. That rescued the pickers from reading as ghosts and overshot in the other direction:
 * a control asking for `surfaceContainerHighest` at full density renders as a flat opaque grey box,
 * which is what made the New Timer and New To-Do sheets look like stock Material sitting on a
 * Crystal background. Scaling that role back down is what turns them into glass.
 *
 * **Solid is exempt, and re-applying alpha there would be a real bug.** Under `CrystalBackground.SOLID`
 * the roles are pre-composited opaque against a known flat field (`withSolidGlassSurfaces`), so
 * multiplying an alpha back in would composite the field into itself a second time — the same trap
 * `CrystalWindowContent` documents for `crystalWindowSurfaceAlpha`.
 */
@Composable
@ReadOnlyComposable
fun crystalControlColor(fallback: Color): Color = when {
    !isCrystal() -> fallback
    isSolidField -> fallback
    else -> fallback.copy(alpha = fallback.alpha * CONTROL_FILL_SCALE)
}

/**
 * A glyph **etched into** the glass rather than printed on it.
 *
 * **The tile converted and the icon inside it did not.** The add-menu tiles got real glass — blur,
 * rim, shadow — and the user's reaction was precise: *"the outside of the button looks more like a 3D
 * shadow… but the actual icons within themselves look flat."* Correct, and it is the same shape of
 * defect as the nav pill and the selected day circle: a container converts, and the thing sitting on
 * top of it is still stock Material.
 *
 * **Swapping to `Icons.Outlined` was the obvious fix and it does not work here.** Checked with the
 * point-count method `MainActivity`'s `navTabs` documents, against material-icons-extended: of the
 * seven glyphs these two pickers use, only **CheckCircle** (32 → 49) and **Timer** (50 → 68) have a
 * genuinely distinct outlined variant. `Add` is 16 → 16, `AccessTime` 49 → 50, `Event` 47 → 54,
 * `Alarm` 75 → 69 — all of them already outline drawings. Swapping would have restyled two icons out
 * of seven and left a set that reads as inconsistent rather than as converted.
 *
 * So the glyph keeps its shape and gains depth instead: a dark copy offset down-right and a light
 * copy offset up-left, both behind the accent-tinted glyph. That is the bevel a real piece of etched
 * glass has, it works identically for all seven, and it needs no icon to have a variant it does not
 * have.
 *
 * Under the two Material styles this is a plain `Icon` — those themes are flat on purpose.
 */
@Composable
fun CrystalGlyph(
    imageVector: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    contentDescription: String? = null
) {
    if (!isCrystal()) {
        Icon(imageVector, contentDescription, modifier.size(size), tint = tint)
        return
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // Offsets stay sub-pixel-ish on purpose. Anything past about 1dp stops reading as a bevel and
        // starts reading as a badly registered double-print.
        Icon(
            imageVector, null,
            Modifier.size(size).offset(x = GLYPH_BEVEL, y = GLYPH_BEVEL),
            tint = Color.Black.copy(alpha = 0.30f)
        )
        Icon(
            imageVector, null,
            Modifier.size(size).offset(x = -GLYPH_BEVEL, y = -GLYPH_BEVEL),
            tint = Color.White.copy(alpha = 0.34f)
        )
        Icon(imageVector, contentDescription, Modifier.size(size), tint = tint)
    }
}

private val GLYPH_BEVEL = 0.75.dp

/**
 * A **control** as real glass — a picker trigger, a text field's box, a sound or vibration row.
 *
 * **This replaces the `Surface(color = crystalControlColor(...))` idiom, which was an alpha and
 * nothing else.** Under Crystal that gave a flat translucent fill: no backdrop blur, no specular rim.
 * Beside the Today screen's cards — which go through [crystalSurface] and get both — every editor
 * sheet read as an older, flatter Crystal, and the user reported exactly that: the habit picker "still
 * has that semi-transparent look from our original crystal theme… it still doesn't match".
 *
 * The density worry that put controls on [crystalControlColor] originally is handled by
 * `crystalGlassTint`, not by pre-scaling the colour — and a sheet does have a blurred field of its
 * own (`CrystalWindowContent` installs one per window), so this genuinely blurs rather than falling
 * through to a fallback.
 *
 * Under the two Material styles it is `shadow` + `clip` + `background` plus the hairline these
 * controls have always had, i.e. byte-identical to what the old `Surface` produced.
 */
@Composable
fun Modifier.crystalControlSurface(
    fill: Color = MaterialTheme.colorScheme.surfaceContainerHighest
): Modifier {
    val shape = MaterialTheme.shapes.small
    return this
        .crystalSurface(
            shape = shape,
            fill = fill,
            elevation = SMALL_OBJECT_ELEVATION,
            crystalElevation = SMALL_OBJECT_ELEVATION
        )
        // The rim is Crystal-only, so the flat themes need their own hairline back. This is the one
        // tier that keeps a flat-theme shadow: a control is a thing you reach for, and the hairline
        // plus the 2dp is what has always separated a field from the panel it sits on.
        .then(
            if (isCrystal()) Modifier
            else Modifier.border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = shape
            )
        )
}

/**
 * The fill a **selected** chip takes — a day circle in a repeat picker, a mood chip, a segmented
 * option. Pair it with [crystalSelectedChipContentColor].
 *
 * **This is the other half of a control the kit had only half-converted.** The day pickers in the
 * habit and alarm editors already routed their *unselected* circles through [crystalControlColor] and
 * their shadows through [crystalControlElevation] — so they looked converted — while the **selected**
 * circle stayed a solid opaque `primaryContainer` disc. That is the same defect `crystalNavItemColors`
 * and the week strip's `DayChip` were written for, stated once in `CLAUDE.md` as *"the fill was themed
 * and the selected state was not"*, surviving in the one control nobody re-checked. The user reported
 * it as the daily/weekly/monthly sections still not having the theme applied.
 *
 * Under Crystal the accent moves **from the fill to the content**, matching the approved Today chip
 * exactly: a neutral frosted circle with accent text. Under the two Material styles this returns the
 * caller's own `primaryContainer`, so those themes are untouched — which matters, because the user
 * has said the flat Material 3 look is finished.
 */
@Composable
@ReadOnlyComposable
fun crystalSelectedChipColor(fallback: Color): Color =
    if (isCrystal()) MaterialTheme.colorScheme.surfaceContainerHighest else fallback

/**
 * The fill for a 48dp add-menu tile: **neutral glass, with the accent on the glyph** — the app's
 * standard rule, in both modes.
 *
 * This carried a light/dark branch and a long argument for keeping `primaryContainer` under Crystal:
 * inside a window `tintAlpha` was 0.44+, and a neutral role at that density over a sheet's own pale
 * gradient is milk, so only an accent fill survived. **Both halves of that premise are gone.** The
 * window band now equals the screen band, and a sheet's backdrop is the real mesh rather than a
 * gradient — so a neutral role here is a frosted disc with the field reading through it, which is
 * what was asked for and what every other converted control does. The dark branch added earlier the
 * same day (to stop the tiles being saturated blue slabs) is subsumed by this: neither mode needs a
 * special case now.
 */
@Composable
@ReadOnlyComposable
fun crystalAddTileFill(): Color =
    if (isCrystal()) MaterialTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)

/**
 * The content colour that pairs with [crystalSelectedChipColor]. Accent under Crystal, the caller's
 * own `on*` role otherwise.
 *
 * Both must move together. Leave the label on `onPrimaryContainer` over a neutral frosted fill and it
 * reads as dark text on glass — legible, but nothing about it says *selected*.
 */
@Composable
@ReadOnlyComposable
fun crystalSelectedChipContentColor(fallback: Color): Color =
    if (isCrystal()) MaterialTheme.colorScheme.primary else fallback

/**
 * The shadow a control drawn with [crystalControlColor] should cast: **none**, under a mesh.
 *
 * Not a taste call. Android tessellates a shape's shadow outline into a coarse convex polygon, and
 * an opaque fill hides it — but a Crystal control's fill is translucent, so the polygon shows
 * *through* the control. On the 48dp circular icon chips in the To-do sheet this rendered as a
 * visible **octagon inside each circle**, reported as "jagged edges on the ones I can't select";
 * the selected chip looked clean only because `primaryContainer` is dense enough to hide it. Both
 * branches had asked for `CircleShape` all along, so nothing about the shape was ever wrong.
 *
 * Solid keeps its shadow: its fills are opaque, so there is nothing to see through.
 */
@Composable
@ReadOnlyComposable
fun crystalControlElevation(fallback: Dp): Dp =
    if (isCrystal() && !isSolidField) 0.dp else fallback

/**
 * Position [t] within the band `[low, high]`. Every band in this file is written so that **`t = 0.5`
 * lands on the value that shipped before the Glass Intensity slider existed**, which is what makes
 * the slider's default a no-op for anyone updating — and the cheapest way to check the bands are
 * right is that 50% is indistinguishable from the previous build.
 */
private fun band(low: Float, high: Float, t: Float): Float = low + (high - low) * t.coerceIn(0f, 1f)

/**
 * The tint a blurred panel lays over its patch of field, for the active Sheer/Tinted preference.
 * Dark mode gets a lifted tint because at the light values a panel is nearly indistinguishable from
 * the twilight field behind it.
 *
 * Tint and blur ([crystalBlurRadius]) move together with Glass Intensity, because that is how frosted
 * glass behaves — denser glass is both more tinted and more diffuse. Splitting them would make the
 * slider read as two settings fused into one control. Midpoints: 0.10 / 0.16 / 0.22 / 0.26.
 *
 * **One band, everywhere.** There used to be a second, much denser set for `inOwnWindow` —
 * 0.34–0.70 against 0.08–0.24 at Sheer/dark — because a sheet painted its own smooth gradient and
 * blurring a smooth gradient returns the same gradient, so a screen-weight tint dissolved into it.
 * `CrystalWindowContent` now paints the **screen's** mesh, offset to the patch the window covers, so
 * a panel in a sheet has exactly what a panel on a screen has to separate against. Keeping the dense
 * band on top of a real field is what made every sheet read as a solid slab — it did not compensate
 * for a missing backdrop, it hid the one that was there.
 *
 * The colour is forced opaque first. Crystal's surface *roles* already carry alpha (see
 * `withTranslucentSurfaces`), so a caller handing one of them straight in would multiply the two and
 * produce a panel with almost no tint left — the glass would vanish under Sheer. The tint alpha is
 * the only opacity a blurred panel should have; the blurred field under it is opaque.
 */
@Composable
@ReadOnlyComposable
private fun crystalGlassTint(container: Color): Color {
    val t = LocalCrystalIntensity.current
    val tintAlpha = when {
        isSheer && isDark -> band(0.08f, 0.24f, t)
        isSheer           -> band(0.04f, 0.16f, t)
        isDark            -> band(0.16f, 0.36f, t)
        else              -> band(0.14f, 0.30f, t)
    }
    return container.copy(alpha = tintAlpha)
}

/** How far the field is blurred under glass, for the active Sheer/Tinted preference. See [crystalGlassTint]. */
@Composable
@ReadOnlyComposable
private fun crystalBlurRadius(): Dp {
    val t = LocalCrystalIntensity.current.coerceIn(0f, 1f)
    return if (isSheer) lerp(4.dp, 16.dp, t) else lerp(8.dp, 24.dp, t)
}

/**
 * How the ambient field is drawn. The two are the same five hues arranged for two very different
 * shapes of container.
 */
enum class CrystalFieldStyle {
    /** A whole screen: five corner-anchored radial blobs. */
    Screen,

    /**
     * A sheet or dialog: a diagonal gradient through the same hues.
     *
     * Not the blob mesh, for two reasons. A sheet is a short wide box, so five corner blobs compress
     * into something that reads nothing like the screen behind it. And a sheet's drag handle sits
     * *outside* its content, so the container colour has to be set separately — which needs the
     * field to have a predictable colour at its top edge, and a corner blob does not.
     */
    Panel
}

/**
 * **The two fields must land on the same tone, and for a long time they did not.**
 *
 * A screen draws [CrystalFieldStyle.Screen] — five blobs at alpha 0.85 over the base — while a sheet
 * or dialog draws [CrystalFieldStyle.Panel], a gradient whose stops are each hue *lerped* toward that
 * same base. Two different dilutions of one palette, chosen independently, so they were never going
 * to agree: measured across all five fields the sheet came out **+3.7 L\* lighter than the screen in
 * light mode and 1.4 darker in dark**. Reported as one field looking brighter in the notebook and
 * reminder pickers than on the home screen — noticed on Verdant, but it was every field, because the
 * gap is in the *drawing*, not the palette.
 *
 * They are now tied together at the lighter of the two in each mode, which is what was asked for:
 * light meets the panel at L\* ~84 (see [MESH_BLOB_DILUTION]), and dark raises the panel's lerp to
 * meet the screen at **L\* ~41.8**.
 *
 * **The dark tie was never as tight as this doc claimed.** It read "~24.5" as though the two met
 * there; measured on 2026-08-23 the panel was **4.4 L\* below** the screen at `PANEL_LERP_DARK`
 * 0.62, and lifting the dark palettes 40% widened that to 6.1 — the same defect this KDoc exists to
 * describe, surviving inside it. Solved rather than re-estimated: 0.82 is the value that minimises
 * the worst |panel − screen| across **all five** meshes, landing every one within **0.16 L\***
 * (Aurora +0.01, Ember +0.02, Verdant −0.01, Rose +0.16, Mist +0.05). Re-derive with the same sweep
 * if either the palettes or the blob alphas move; do not nudge it by eye.
 *
 * **A single global knob cannot do this**, which is worth stating so it is not "simplified" later:
 * scaling the blob alphas by 0.68 lands light exactly on 84.1 and simultaneously drags *dark* from
 * 24.5 to 21.1, because the same scale shows more of a base that is nearly white in one mode and
 * nearly black in the other. Every dilution constant here is mode-aware for that reason.
 */
private const val PANEL_LERP_LIGHT = 0.45f
private const val PANEL_LERP_DARK = 0.82f

/**
 * **In dark, the panel is re-anchored from the field's *brightest* tone to its *general* tone, and
 * desaturated. This is a deliberate change of what the tie above is solved against.**
 *
 * The 0.82 solve is not wrong — it ties the panel's mean to the composited field tone **at each blob
 * centre**, which is the brightest the screen ever gets. Perceptually that is the wrong anchor: a
 * sheet then sits at the peak of the room it is covering, so it reads as *brighter than the room*
 * rather than as glass over it. Measured on the Fold on 2026-08-26 (dark, Orchid), like-for-like
 * area means off one screenshot:
 *
 * | | L\* | C\* |
 * |---|---|---|
 * | screen field (p50 / p90 over a 144-point grid) | 28.4 / 29.9 | 22.3 / 25.3 |
 * | sheet field (mean, controls excluded) | 36.2 | 33.4 |
 *
 * So the sheet was **~7 L\* lighter than the room's ninetieth percentile and more saturated than its
 * most saturated point** — reported as the add menus looking like an "ugly dark blue configuration",
 * and asked to read like iOS night mode instead, where a sheet is a dark, nearly colourless pane and
 * the blur does the work.
 *
 * **Normalising the tone rather than re-solving the lerp is what makes this hold for all ten meshes.**
 * A single lerp constant cannot land every palette on one tone — at 0.82 Aurora's stops mean L\* 34.5
 * while Orchid's mean 42.1, because the palettes differ in lightness — which is why the original
 * solve could only minimise the *worst* case. Shifting every stop by one delta in HCT lands the mean
 * exactly, for any palette, and preserves the per-stop distribution [PANEL_STOP_SHAPE] creates.
 * **HCT tone is CIE L\***, so this is exact rather than approximate, and scaling chroma at a fixed
 * tone cannot move the tone back.
 *
 * Light is untouched: the complaint was night mode, and the light panel already sits below its field.
 */
private const val PANEL_TONE_DARK = 30.0
private const val PANEL_CHROMA_DARK = 0.5f

/** Restate [this] at an explicit HCT tone with its chroma scaled. Tone is CIE L\*, so tone is exact. */
private fun Color.atToneWithChroma(tone: Double, chromaScale: Float): Color {
    val hct = Hct.fromInt(this.toArgb())
    return Color(Hct.from(hct.hue, hct.chroma * chromaScale, tone.coerceIn(0.0, 100.0)).toInt())
}

/**
 * The panel gradient's per-stop shape, normalised to a mean of 1.
 *
 * **Matching the mean was necessary and not sufficient, and this is the part that was missing.** The
 * lerps above are *averages*: solved so the panel's mean tone lands on the screen's, and they do, to
 * within 0.16 L\*. But a mean is the wrong statistic when one surface is uniform and the other is
 * not. [drawCrystalMeshField] anchors four blobs at the corners at alpha **0.85** and puts the fifth
 * in the middle at **0.64**, dimmed and shrunk — so a screen is bright at its edges and quieter
 * through the centre, while the panel applied one flat lerp to all five stops and was equally vivid
 * everywhere. Two fields with the same average tone and different distributions, and a sheet is a
 * short wide box whose centre is most of what you see. The user read it exactly that way: *"the
 * background one is darker and matches more night mode and this one's a little too bright."*
 *
 * So the panel now borrows the screen's own alphas as its shape — `[0.85, 0.85, 0.64, 0.85, 0.85]`,
 * divided by their mean (0.808). **Normalising is what makes this safe**: multiplying a
 * mean-1 shape by either lerp above leaves that lerp's mean untouched, so the tie those two constants
 * were solved for survives by construction rather than needing re-solving. Only the *distribution*
 * moves — the centre stop drops from 0.82 to ~0.65 in dark, which is the visible change.
 *
 * Read the alphas out of [drawCrystalMeshField] if they ever move; they are the definition and this
 * is derived from them.
 */
private val PANEL_STOP_SHAPE = floatArrayOf(0.85f, 0.85f, 0.64f, 0.85f, 0.85f)
    .let { shape -> val mean = shape.average().toFloat(); shape.map { it / mean } }

/**
 * The Panel gradient's stops. Ordered so the first is the colour a sheet's container takes.
 *
 * `@Composable` since the custom field arrived: its five stops are solved from a seed that lives in
 * composition rather than declared as literals, so anything reading a field's colours has to be able
 * to see it. Every caller was already composable.
 */
@Composable
@ReadOnlyComposable
private fun panelFieldStops(mesh: CrystalMesh, dark: Boolean): List<Color> {
    val blobs = crystalFieldBlobs(mesh, dark)
    val base = crystalFieldBase(dark)
    val lerpMean = if (dark) PANEL_LERP_DARK else PANEL_LERP_LIGHT
    // Each hue is pulled toward the base so the gradient stays a wash rather than five stripes, and
    // by how much depends on where it sits — see PANEL_STOP_SHAPE.
    val stops = blobs.mapIndexed { i, blob ->
        // `getOrElse { 1f }` rather than `[i]`: all twenty palettes are five long today (checked),
        // but `scripts/gen_mesh_palette.py` generates half of them, so a sixth slot arriving here
        // should fall back to the flat mean rather than crash the theme.
        val t = (lerpMean * PANEL_STOP_SHAPE.getOrElse(i) { 1f }).coerceIn(0f, 1f)
        lerp(base, blob, t)
    }
    // **No tone re-anchoring here any more, and that is a revert.** Earlier on 2026-08-26 this
    // shifted the dark stops down to a mean tone of 30 — which matched the screen field's *mean* but
    // pushed the darkest stop to L* 18.8 against a room at ~28, so parts of a sheet came out visibly
    // darker than the screen behind them. That was a real regression and it was reported as such.
    // The gradient is now a **fallback only** — Solid, and the frame before a window knows its own
    // position — so it has no business carrying a hand-tuned offset solved against the mesh.
    return stops
}

/**
 * What the screen field's blob alphas are multiplied by, per mode, so the mesh lands on the same
 * tone as the panel gradient above. See [panelFieldStops] for the measurement and for why this
 * cannot be one number.
 *
 * **Dark is deliberately 1.0** — the screen mesh was already the lighter of the two there, so it is
 * the panel that moved instead. Changing this changes the composited tone the mesh gate reports, and
 * `scripts/verify_mesh_palettes.py` reads both this and the alphas straight out of this file so the
 * two cannot drift; it does not keep its own copy.
 */
const val MESH_BLOB_DILUTION_LIGHT = 0.68f
const val MESH_BLOB_DILUTION_DARK = 1.0f

/**
 * How far a blob reaches, as a fraction of **each** axis — so it is an ellipse on a non-square
 * surface, not a circle.
 *
 * **This being per-axis is the whole point, and a single scalar here was a real bug.** It used to be
 * `maxOf(size.width, size.height) * 0.72f`: the blob *centres* are fractions of width and height, so
 * they follow the aspect ratio correctly, but one radius off the **long** edge does not. On the
 * square picker swatch that was 0.72 of the width and the five blobs blended into the intended 2-D
 * diagonal mesh; on a 968x2376 phone the same expression is **1.77x the width**, so every blob
 * overshot sideways and the mesh collapsed into horizontal bands stacked top to bottom. Same code,
 * genuinely different picture — which is exactly the drift that sharing this function was supposed to
 * prevent, so sharing the code turned out to be necessary and not sufficient: the geometry has to be
 * aspect-invariant too. Reported by the user as the swatch not matching what the bottom of the screen
 * shows. With the extent expressed per-axis, a tall screen is the square swatch **stretched**, and
 * the two agree at every normalised point by construction.
 *
 * **1.14 rather than 0.72, and that value is derived rather than picked.** Making the extent
 * per-axis shrinks a blob's horizontal reach on a tall screen (1.77 -> 0.72 of the width), which
 * lightens the composited field by ~4 L* and, in dark, drops the worst-case tone gap between a card
 * and the field beneath it from 4.21 to **0.96 L\*** — a card nearly dissolving into the field, the
 * same failure class as the black-cards-on-black bug. 1.14 restores the coverage: it puts the field's
 * mean tone within **1 L\*** of the geometry already verified on hardware in both modes (light −0.97,
 * dark +0.24) while leaving worst-case separation strictly *better* than before (light +0.19, dark
 * +0.98). `scripts/verify_mesh_aspect.py` computes all of that and is the gate for changing it.
 *
 * Note `MESH_BLOB_DILUTION_LIGHT`/`_DARK` were checked against the new overlap and **do not move** —
 * one number absorbed the whole change.
 */
private const val BLOB_EXTENT = 1.14f

/**
 * The blob mesh itself, as a draw call.
 *
 * Extracted so the Settings preview swatches and the real field are **the same code** — a preview
 * that redraws the geometry by hand is a second copy that silently stops matching the first. Takes
 * its two colours rather than reading the theme, so a swatch can render a field that is not the one
 * currently applied. See [BLOB_EXTENT] for why sharing the code was not on its own enough to stop
 * the swatch and the screen drawing different pictures.
 */
fun DrawScope.drawCrystalMeshField(
    base: Color,
    blobs: List<Color>,
    dark: Boolean,
    /**
     * The box the field's geometry is laid out in. Defaults to the drawn box, which is the screen
     * case and the swatch case. A **popup** passes the screen here and its own screen position as
     * [origin], so it shows the patch of field it covers instead of a miniature of the whole thing.
     */
    fieldSize: Size = size,
    /** Where the drawn box sits inside [fieldSize]. */
    origin: Offset = Offset.Zero,
    /** How strongly the blobs are painted; Borikén by day passes its own (see `crystalFieldDilution`). */
    dilution: Float = if (dark) MESH_BLOB_DILUTION_DARK else MESH_BLOB_DILUTION_LIGHT
) {
    drawRect(base)
    val r = BLOB_EXTENT
    // Mode-aware, so the mesh lands on the same tone as the sheet's panel gradient. See
    // MESH_BLOB_DILUTION_LIGHT for the measurement and for why one number cannot serve both modes.
    val dilute = dilution
    // `rad` is a fraction of each axis rather than a pixel length — see BLOB_EXTENT.
    fun blob(color: Color, cx: Float, cy: Float, alpha: Float, rad: Float) {
        // Centres are in FIELD space; shifting by -origin puts them in the drawn box's space.
        val center = Offset(cx - origin.x, cy - origin.y)
        val rx = fieldSize.width * rad
        val ry = fieldSize.height * rad
        if (rx <= 0f || ry <= 0f) return
        // Compose has no elliptical radial gradient, so the blob is drawn as a circle of radius `ry`
        // and squashed to `rx` about its own centre. Squashing the draw rather than the brush keeps
        // the gradient radial in the scaled space, which is what makes the result a true ellipse.
        //
        // **The pivot is this blob's own centre, not `DrawScope.center`.** They are the same thing
        // only when the drawn box *is* the field; inside a popup `center` is the popup's middle, and
        // using it stretches every blob about the wrong point — which looks plausible on a large
        // sheet and obviously wrong on a small menu.
        scale(scaleX = rx / ry, scaleY = 1f, pivot = center) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha * dilute), Color.Transparent),
                    center = center,
                    radius = ry
                ),
                radius = ry,
                center = center
            )
        }
    }
    // Corner-anchored so the field reads as continuous rather than as five circles, with the centre
    // blob dimmed and shrunk so the middle of the screen — where content sits — stays the calmest
    // part of it.
    blob(blobs[0], fieldSize.width * 0.02f, fieldSize.height * 0.04f, 0.85f, r)
    blob(blobs[1], fieldSize.width * 1.00f, fieldSize.height * 0.10f, 0.85f, r)
    blob(blobs[2], fieldSize.width * 0.50f, fieldSize.height * 0.42f, 0.64f, r * 0.9f)
    blob(blobs[3], fieldSize.width * 0.06f, fieldSize.height * 0.98f, 0.85f, r)
    blob(blobs[4], fieldSize.width * 0.98f, fieldSize.height * 1.00f, 0.85f, r)
}

/**
 * True when there is no blur source **and the device could have had one**.
 *
 * "No blur" has never been one condition, and this is the third distinct thing hiding inside it. The
 * first two are settled: Solid registers no source on purpose, and API < 32 cannot blur at all —
 * `crystalSurface` splits those and `CrystalBar` follows it. This is the case neither of them names:
 * a screen on a modern device, under a mesh, where `LocalBlurredField` is momentarily **null**.
 *
 * It happened for real. `CrystalRoot` used to take an early-return path when the style was not
 * Crystal, so switching theme style away and back **disposed that subtree** — the state was rebuilt
 * and there was a frame before a source had been captured. The user saw the result and described it
 * exactly: *"even in night mode for crystal the bottom bar looks very material and completely gets
 * rid of the crystal theme"*, followed by *"it actually kind of just fixed itself… maybe just
 * between switching themes"*.
 *
 * **That trigger is gone as of 2026-08-26.** `CrystalRoot` no longer relocates its content between
 * branches (it was disposing the whole NavHost too, which is what threw the user out of Settings on
 * a theme change), so the subtree survives a style switch and the state is never rebuilt underneath
 * it. This branch is therefore expected to be **unreachable** now. It is kept rather than deleted:
 * it costs one boolean, it is the honest answer if a future change reintroduces a disposal, and a
 * flat bar is a defect this project has paid for repeatedly. **Do not cite it as the explanation for
 * a flat surface without checking `CrystalDiag`'s log first** — the branch that actually painted is
 * recorded there.
 *
 * The damage came from falling into the API < 32 branch, whose alpha reaches **0.90 in dark** because
 * it is compensating for glass that will never arrive. On a tinted room `surface` is
 * `room.asRoomTone(18.0, 6.0)` — a colour — so 90% of it is a painted accent slab, which is precisely
 * "a solid Material blue bar".
 *
 * **A sheet or dialog is not this case.** Those provide `LocalBlurredField = null` deliberately and do
 * want the dense band, which is why `inOwnWindow` is part of the test rather than just the API level.
 */
@Composable
@ReadOnlyComposable
private fun fieldMissingUnexpectedly(field: BlurredField?): Boolean =
    field == null &&
        !isSolidField &&
        !inOwnWindow &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2

/**
 * True when a blurred field is in scope but its first snapshot hasn't landed **yet** — the frame or
 * two between a window first drawing its field and the capture finishing. Panels take the light
 * [NO_FIELD_FALLBACK_ALPHA] fill for that gap.
 *
 * Under Haze the same gap reopened at every `onStop` (`clearHazeAreaLayerOnStop` released the
 * captured layer, and every reported gray-box flicker logged `BLUR areas=1 withLayer=0` until it was
 * recaptured — confirmed 2026-10-04). A snapshot is an ordinary bitmap that nothing releases, so now
 * the gap only exists once per window.
 *
 * Read with ordinary composition observation so a panel recomposes the instant the field is ready.
 * [BlurredField.ready] only ever goes false → true, so that is one recomposition, not one per capture.
 */
@Composable
@ReadOnlyComposable
private fun fieldNotReady(field: BlurredField): Boolean = !field.ready

/**
 * What to paint when [fieldMissingUnexpectedly]. Low enough that a tinted `surface` reads as glass
 * rather than as a painted bar, since this only ever shows for a frame or two before the field
 * arrives and the blur branch takes over. Deliberately **not** [fauxFillAlpha] — that band exists to
 * stand in for glass permanently, and standing in loudly is the whole defect here.
 */
private const val NO_FIELD_FALLBACK_ALPHA = 0.30f

/** The translucent-fill alpha used when there is no blur source in scope — sheets, dialogs, and any
 * panel composed outside a [CrystalRoot]. */
@Composable
@ReadOnlyComposable
private fun fauxFillAlpha(): Float {
    val t = LocalCrystalIntensity.current
    // Deliberately the same bands as `crystalSurfaceAlpha` / `crystalWindowSurfaceAlpha` in
    // Theme.kt, which do this for the translucent surface *roles*. Move one without the other and a
    // Crystal card stops matching the ordinary `Surface()` next to it.
    return if (inOwnWindow) {
        crystalWindowSurfaceAlpha(LocalCrystalStyle.current, isDark, t)
    } else {
        crystalSurfaceAlpha(LocalCrystalStyle.current, isDark, t)
    }
}

/**
 * The specular rim — a soft white edge that reads as light catching the lip of a piece of glass.
 *
 * This is **a highlight on a translucent panel, not a sectioning line**, so it is not the kind of
 * border `DESIGN.md` prohibits: it does not divide content, it describes an edge. It exists only
 * under Crystal, and the flat styles keep the app's tonal layering with no borders at all.
 *
 * Flat rather than the diagonal gradient the source design used on its largest panels: Rhythm puts
 * far more panels on screen at once — a habit list is a dozen — and a directional highlight repeated
 * a dozen times reads as a pattern rather than as light.
 */
@Composable
@ReadOnlyComposable
private fun rimColor(): Color = Color.White.copy(alpha = if (isDark) 0.30f else 0.45f)

/**
 * The ambient colour field: five overlapping radial blobs — teal, sky, indigo, violet, coral — over
 * a flat base. Deliberately vivid rather than a near-white wash, because clear glass shows and tints
 * this; a subtle field blurs into nothing and the theme looks like plain grey Material.
 *
 * Fills its bounds. Place it as the lowest layer of the region marked [blurredFieldSource]. Under the two
 * Material styles it is just the solid background colour.
 */
@Composable
fun AmbientBackground(
    modifier: Modifier = Modifier,
    style: CrystalFieldStyle = CrystalFieldStyle.Screen
) {
    if (!isCrystal()) {
        Box(modifier.background(MaterialTheme.colorScheme.background))
        return
    }
    // One flat tone, for both a screen and a sheet — the point of the option is that there is no
    // gradient anywhere, so the Panel/Screen distinction stops mattering.
    if (isSolidField) {
        Box(modifier.background(solidFieldColor()))
        return
    }
    val dark = isDark
    val mesh = LocalCrystalMesh.current
    val base = crystalFieldBase(dark)
    val blobs = crystalFieldBlobs(mesh, dark)
    val dilution = crystalFieldDilution(dark)
    if (style == CrystalFieldStyle.Panel) {
        Box(
            modifier.background(
                Brush.linearGradient(
                    colors = panelFieldStops(mesh, dark),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            )
        )
        return
    }
    // `dark` is the hoisted value from above — `isDark` is a @Composable property and a drawBehind
    // lambda is not a composable scope.
    Box(modifier.drawBehind { drawCrystalMeshField(base, blobs, dark, dilution = dilution) })
}

/**
 * Crystal panel styling as a [Modifier] — the workhorse of this file, and what nearly every card,
 * row and container in the app uses.
 *
 * Under Crystal: the panel's patch of the blurred ambient field when one is in scope (see
 * [blurredFieldPanel]), a translucent fill when it is not, then the specular rim and a floating
 * shadow. Under the two Material styles:
 * a clip, [fill], and [elevation]'s shadow — nothing else, so the app keeps looking exactly as it
 * always has.
 *
 * [fill] is the tint colour as well as the flat-theme background, so **a caller that encodes meaning
 * in colour keeps it in both themes** — a habit's own colour, a completed step's accent wash. Pass
 * it rather than letting a coloured card become a neutral pane of glass.
 *
 * **This is `private`, and it must stay that way.** For five rounds it was public and every call site
 * chose its own six parameters; the result was eight fill roles, five elevations and five shapes
 * across 22 surfaces that were all supposed to be the same material, and the user reported the
 * mismatch five times running. The tiers above are the supported vocabulary. Note `internal` would
 * buy nothing here — this is a single-module app, so module-wide visibility is app-wide visibility;
 * file-private is the only scope that actually closes the door.
 */
@Composable
private fun Modifier.crystalSurface(
    shape: Shape = MaterialTheme.shapes.large,
    fill: Color = MaterialTheme.colorScheme.surfaceContainer,
    /** Shadow in the flat Material styles. Defaults to none: `DESIGN.md` asks for tonal layering
     * rather than shadows, and this is what keeps that promise. */
    elevation: Dp = 0.dp,
    /** Shadow under a Crystal panel. Glass floats — without this it reads as a printed rectangle. */
    crystalElevation: Dp = 3.dp,
    rim: Boolean = true,
    /** Only for a sticky header: raises the **no-blur** fill toward opaque so rows scrolling behind
     * it cannot ghost through on API < 32. With a real blur the panel is already opaque. */
    stickyFallbackBoost: Boolean = false
): Modifier {
    if (!isCrystal()) {
        return this
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
            .clip(shape)
            .background(fill)
    }
    val field = LocalBlurredField.current
    // See [fieldNotReady]'s KDoc: a blurred field can be in scope before its first snapshot, a
    // transient gap distinct from [fieldMissingUnexpectedly]'s "no field at all" — both read as
    // "BLUR isn't actually usable right now" and both take the same light fallback below.
    val layerNotReady = field != null && !isSolidField && fieldNotReady(field)
    val fillMod = when {
        field != null && !isSolidField && !layerNotReady ->
            Modifier.blurredFieldPanel(field, crystalGlassTint(fill))

        // **There are two ways to have no blur and they want opposite things.** Both used to land in
        // one branch, which is the bug that made Solid panels read as near-black slabs.
        //
        // Solid has no blur because there is nothing worth blurring, and `fill` has already been
        // resolved to the exact opaque colour a glass panel over this field comes to —
        // `withSolidGlassSurfaces` did the compositing when the scheme was built. Drawing it at any
        // alpha would composite it a second time, over itself. So it is drawn as it is.
        isSolidField -> Modifier.background(fill.copy(alpha = 1f))

        // [layerNotReady]: a source exists but hasn't captured a layer yet. **No sticky boost here.**
        // This gap opens on every cold launch and every resume (`crystal_diag.log` logs it at each
        // one), so boosting it painted every section heading as a 0.96 white slab for the first
        // ~100-250ms of every visit — reported as the Crystal theme "breaking" on launch, and
        // confirmed frame by frame off a screen recording on 2026-10-08. A heading is over the
        // fixed field in that window far more often than over a scrolled row, and a frame of
        // ghosting is the smaller artefact of the two.
        layerNotReady -> Modifier.background(fill.copy(alpha = NO_FIELD_FALLBACK_ALPHA))

        // The same third case [CrystalBar] documents: a modern device, a mesh, and no field in scope.
        // A sticky header still takes its boost, because ghosting is the worse artefact of the two
        // and it is the one thing that alpha is genuinely load-bearing for.
        fieldMissingUnexpectedly(field) -> Modifier.background(
            fill.copy(alpha = if (stickyFallbackBoost) 0.96f else NO_FIELD_FALLBACK_ALPHA)
        )

        // API < 32 has no blur because the device cannot, over a field that genuinely varies. Here
        // the alpha is all the panel has, and `stickyFallbackBoost` is the one thing keeping rows
        // from ghosting through a sticky header. Unchanged, and this is the only path that reaches
        // it — which is what its KDoc always claimed and the old single branch did not honour.
        else -> {
            val alpha = if (stickyFallbackBoost) 0.96f else fauxFillAlpha()
            Modifier.background(fill.copy(alpha = alpha))
        }
    }
    // No shadow while [layerNotReady]: a platform elevation shadow is drawn *under* the panel and
    // shows straight through a translucent fill, so the 0.30 fallback came out as a grey slab rather
    // than glass. A blurred panel is opaque and hides its own shadow, which is why this never showed
    // once the layer arrives. Measured off a cold-launch recording on 2026-10-08.
    return this
        .then(if (layerNotReady) Modifier else Modifier.shadow(crystalElevation, shape, clip = false))
        .clip(shape)
        .then(fillMod)
        .then(if (rim) Modifier.border(1.dp, rimColor(), shape) else Modifier)
}

/**
 * A Crystal panel as a container. Prefer [Modifier.crystalSurface] where the caller already has a
 * layout node to hang it on; this exists for the cases that would otherwise need a bare wrapper
 * `Box` anyway. Anchors [LocalContentColor] so text inside is legible in both modes.
 */
@Composable
fun CrystalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    fill: Color = MaterialTheme.colorScheme.surfaceContainer,
    elevation: Dp = 0.dp,
    crystalElevation: Dp = 6.dp,
    rim: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier.crystalSurface(
            shape = shape,
            fill = fill,
            elevation = elevation,
            crystalElevation = crystalElevation,
            rim = rim
        )
    ) {
        val scope = this
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            scope.content()
        }
    }
}

/**
 * Full-width chrome — a top or bottom bar. Under Crystal it blurs the field behind it and lays a
 * hairline along [dividerEdge]; under the two Material styles it is a solid `surfaceContainer` bar
 * with **no hairline at all**, which is exactly what the app's bars have always been.
 *
 * The hairline being Crystal-only is deliberate on both counts. `DESIGN.md` prohibits lines used to
 * section content, so adding one to the flat themes would be a regression. On glass it is not
 * sectioning anything — it is the lit edge where a translucent bar meets what is behind it, and
 * without it the bar and the field dissolve into each other.
 */
@Composable
fun CrystalBar(
    modifier: Modifier = Modifier,
    dividerEdge: CrystalBarEdge = CrystalBarEdge.Bottom,
    content: @Composable BoxScope.() -> Unit
) {
    val crystal = isCrystal()
    val field = LocalBlurredField.current
    // See [fieldNotReady]: a field can be in scope before its first snapshot has landed. Under Haze
    // this gap reopened at every `onStop` and was the "flat dock" gray-box flicker
    // `crystal_diag.log` confirmed 2026-10-04. Read with ordinary observation so this bar recomposes
    // the instant it heals.
    val layerNotReady = field != null && !isSolidField && fieldNotReady(field)
    // The branch is decided **once**, as a name, and the modifier is derived from that name. It used
    // to be a single `when` producing a Modifier, which is fine until something needs to *report*
    // which branch ran — a second `when` repeating the conditions is free to drift from the first,
    // and a diagnostic that names the wrong branch is worse than none. See [CrystalDiag].
    val branch = when {
        !crystal -> "FLAT_THEME"
        field != null && !isSolidField && !layerNotReady -> "BLUR"
        isSolidField -> "SOLID"
        fieldMissingUnexpectedly(field) -> "FIELD_MISSING"
        layerNotReady -> "LAYER_NOT_READY"
        else -> "OLD_API_FAUX"
    }
    val fill: Modifier = when (branch) {
        "FLAT_THEME" -> Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
        "BLUR" -> Modifier.blurredFieldPanel(field!!, crystalGlassTint(MaterialTheme.colorScheme.surface))

        // **Solid needs the same three-way split [crystalSurface] has, and this branch is why.**
        // Under Solid there is no blur, so without this the `else` below catches it — and `surface`
        // there has *already* been resolved by `withSolidGlassSurfaces` to the exact opaque colour a
        // glass bar over this field comes to. Re-applying `fauxFillAlpha()` composites the field
        // into itself a second time, leaving the bar nearer the field than the cards beside it:
        // measured 1.1–3.7 L\* closer, worst at dark/Sheer/low intensity where the bar's separation
        // falls to +1.22 L\* against a card's +4.30.
        //
        // This is the *same defect* as the 1.85 sticky-header bug, in a second place: `crystalSurface`
        // branched on "no blur" rather than "old API" and Solid took the fallback with it. That one
        // was split three ways on 2026-08-22 and this one was missed, because a bar is the only
        // Crystal surface that does not go through `crystalSurface`.
        "SOLID" -> Modifier.background(
            MaterialTheme.colorScheme.surface.copy(alpha = 1f)
        )

        // The field is missing on a device that can blur — a transient wiring gap, not an old phone.
        // See [fieldMissingUnexpectedly]: this bar is where it was reported, because a nav bar is on
        // screen during a theme switch and a card usually is not.
        //
        // LAYER_NOT_READY is the sibling case: the field exists but its first snapshot hasn't
        // landed. Same light fallback — it is the same "nothing to blur against for a frame or two"
        // situation, just reached a different way. See [fieldNotReady].
        "FIELD_MISSING", "LAYER_NOT_READY" -> Modifier.background(
            MaterialTheme.colorScheme.surface.copy(alpha = NO_FIELD_FALLBACK_ALPHA)
        )

        else -> Modifier.background(
            MaterialTheme.colorScheme.surface.copy(alpha = fauxFillAlpha())
        )
    }
    Box(modifier.fillMaxWidth().then(fill)) {
        content()
        if (crystal) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(
                        if (dividerEdge == CrystalBarEdge.Top) Alignment.TopCenter
                        else Alignment.BottomCenter
                    )
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
        }
    }
}

enum class CrystalBarEdge { Top, Bottom }

/**
 * Colours for a `TopAppBar` sitting inside a [CrystalBar]: transparent under Crystal so the bar's
 * own frosted fill shows, and Material's untouched defaults otherwise.
 *
 * `scrolledContainerColor` matters as much as the base one — leave it defaulted and the bar turns
 * opaque the moment the list under it scrolls, which reads as the glass switching off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun crystalTopAppBarColors(): TopAppBarColors = if (isCrystal()) {
    TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = Color.Transparent
    )
} else {
    TopAppBarDefaults.topAppBarColors()
}

// There is deliberately no separate helper for CenterAlignedTopAppBar. Material's
// `centerAlignedTopAppBarColors` is deprecated in favour of `topAppBarColors` — the two now build
// from the same defaults — and this build is kept clean of deprecation warnings, which is how the
// next round of them gets noticed. Centred bars use [crystalTopAppBarColors] too.

/**
 * Selection colours for a `NavigationBarItem` inside a [CrystalBar].
 *
 * **The bar was converted to glass and the pill on top of it was not**, which is the whole reason
 * this exists. `NavigationBarItem` draws its selected indicator from
 * `NavigationBarItemDefaults.colors()`, whose `indicatorColor` is `secondaryContainer` — a near-white
 * lozenge that sat on the frosted bar looking like the one piece of Material left on the screen. It
 * was invisible in review because no call site passed `colors` at all, so there was nothing to read:
 * before this, `NavigationBarItemDefaults` and `indicatorColor` appeared **nowhere** in the app.
 *
 * Under Crystal the indicator becomes a translucent tint of the accent and the selected icon and
 * label take the accent outright — selection reads as *lit*, not as a painted capsule. Under the two
 * Material styles this returns Material's own defaults untouched, so those themes are unchanged.
 *
 * **The indicator is transparent under Crystal, and the pill is drawn by [CrystalNavIndicator]
 * instead.** A flat accent tint was the first attempt and it is not what the week strip does.
 * Measured on the Fold on 2026-08-24: that tint sat **6.3 L\*** below the bar while the approved day
 * chip sits only **2.3 L\*** below its field — so the pill was already the *more* contrasty of the
 * two, and raising its alpha (the obvious knob, and the one `indicatorColor` exposes) moves it
 * further from the chip rather than closer. What the chip has is a **rim**, +12.5 L\* over the field
 * at its brightest, and a colour cannot express an edge. `indicatorColor` is therefore given up
 * entirely rather than tuned.
 */
@Composable
fun crystalNavItemColors(): NavigationBarItemColors = if (isCrystal()) {
    NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = Color.Transparent,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
} else {
    NavigationBarItemDefaults.colors()
}

/**
 * The selected-tab pill, as **glass** — the same `crystalSurface` call the approved week-strip day
 * chip makes, so the two selection surfaces are one look rather than two approximations of one.
 *
 * Wraps a nav item's `icon` slot. M3 exposes its active indicator as `indicatorColor: Color` and
 * offers **no modifier slot for it**, so a frosted pill cannot be handed to `NavigationBarItem` at
 * all; the only reachable surface is the icon slot, which sits exactly where the indicator is drawn.
 * [crystalNavItemColors] blanks M3's own indicator so the two do not stack.
 *
 * **The box is emitted at a constant size whether or not the tab is selected**, and only the glass
 * is conditional. Sizing it only when selected makes the icon slot change size on selection, and the
 * whole bar's icons and labels jump as you move between tabs.
 *
 * **The measured size must stay icon-sized, and the pill overflows it.** This is the trap, and it
 * cost a visible defect — "a grey touch area that goes beyond the crystal circle". M3 does not lay
 * the indicator out from a token; `NavigationBarItemLayout`'s measure policy computes the
 * indicator-and-ripple box as `iconPlaceable.width + 2 * IndicatorHorizontalPadding` by
 * `iconPlaceable.height + 2 * IndicatorVerticalPadding`, where those two paddings are themselves
 * derived in `NavigationBarKt.<clinit>` as `(ActiveIndicatorWidth - IconSize) / 2` = **16dp** and
 * `(ActiveIndicatorHeight - IconSize) / 2` = **4dp**. That arithmetic is only correct when the thing
 * in the `icon` slot really is a ~24dp icon. Hand it a box already the size of the indicator and
 * **the padding is applied twice**: a 64x32dp box measured out to roughly 96x40dp of ripple around a
 * 64x32dp pill. The ripple was correctly clipped all along — to bounds that were simply too big.
 *
 * So the outer `Box` measures [NAV_ICON_SIZE] and the glass is drawn by an inner box with
 * `wrapContentSize(unbounded = true)`, which lets it paint larger than its parent without
 * contributing to measurement. M3 then sees an icon-sized icon and its own padding lands where it
 * was designed to.
 *
 * The dimensions are mirrored rather than imported because M3 declares those tokens `internal` — the
 * same reason `BoriAlertDialog` mirrors `DialogMinWidth`/`DialogMaxWidth`. If a tab's label ever
 * looks pushed down, this box's height is the first thing to check against M3's own.
 */
@Composable
fun CrystalNavIndicator(selected: Boolean, content: @Composable () -> Unit) {
    if (!isCrystal()) {
        content()
        return
    }
    Box(
        // Icon-sized on purpose — see the KDoc. Widening this re-breaks the ripple.
        modifier = Modifier.size(NAV_ICON_SIZE),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .wrapContentSize(unbounded = true)
                    .size(width = NAV_INDICATOR_WIDTH, height = NAV_INDICATOR_HEIGHT)
                    .crystalSurface(
                        shape = CircleShape,
                        fill = MaterialTheme.colorScheme.surfaceContainerHighest,
                        elevation = 0.dp,
                        crystalElevation = 2.dp
                    )
            )
        }
        content()
    }
}

/**
 * What M3 measures in the `icon` slot. `NavigationBarTokens.IconSize`.
 */
private val NAV_ICON_SIZE = 24.dp

/**
 * The pill's painted size. **56dp, not 64** — `NavigationBarVerticalItemTokens.ActiveIndicatorWidth`.
 * This read 64dp and claimed to mirror `NavigationBarTokens`, but 64dp is that class's
 * `ContainerHeight` — the height of the *bar* — so the pill was drawn 8dp wider than Material's own
 * indicator on every tab.
 */
private val NAV_INDICATOR_WIDTH = 56.dp
private val NAV_INDICATOR_HEIGHT = 32.dp

/**
 * [crystalNavItemColors] for the tablet rail, which had the identical gap — and which has to move
 * with the bar, or the app's two navigation surfaces read as two different designs on a device that
 * can show both.
 */
@Composable
fun crystalNavRailItemColors(): NavigationRailItemColors = if (isCrystal()) {
    NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = Color.Transparent,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
} else {
    NavigationRailItemDefaults.colors()
}

// =================================================================================================
// Colour objects for Material components that own their own container.
//
// **This is the half of the kit that was missing, and it is why "make everything Crystal" kept not
// converging.** A tier is a `Modifier`, so it can only reach a component that accepts one. A
// `Switch`, a `Checkbox`, a `FilterChip`, a `SegmentedButton`, a `Tab`, an `OutlinedTextField` and a
// `Slider` all paint their own container and expose **only** a `colors` object — no modifier can put
// glass on them. A sweep of `ui/` found **390 painted constructs, 304 of them not Crystal-aware**,
// and this class was almost all of it: 24 usages of Switch/Checkbox/RadioButton/Slider passed
// `colors` **zero** times anywhere in the app.
//
// The shape below is the one [crystalTopAppBarColors] and [crystalNavItemColors] already proved:
// `if (isCrystal()) <crystal values> else <Material's own defaults>`, so Material 3 and Expressive
// are untouched by construction rather than by inspection.
//
// **Two rules run through all of them.** A *container* becomes glass — `crystalControlColor`, the
// same halved-alpha tint every converted control row uses. And **selection moves the accent from the
// fill to the content**, which is the rule the week strip, the nav pill and
// [crystalSelectedChipColor] already follow: a selected thing is accent *text* on a frosted fill, not
// a painted accent lozenge. The one deliberate exception is `Switch`, whose checked track **is** its
// only affordance — a switch with no accent fill cannot be read as on.
// =================================================================================================

/**
 * A `Switch`'s colours. The **checked track keeps the accent** — unlike every other selection surface
 * in the app — because a switch has no label, no icon and no second state indicator: the filled track
 * is the entire affordance, and a frosted one reads as off. The *unchecked* track becomes glass, and
 * both thumbs stay opaque so they read as physical objects on it.
 *
 * **The off state's thumb and border are pinned, and that is not a taste call — the previous values
 * measured 1.23:1.** The thumb was left at Material's default (`outline`) and the border at
 * `outlineVariant`; both are quiet greys chosen against an *opaque* Material surface, and on a
 * translucent panel over a light mesh they composite to within a couple of percent of the track. On
 * a real screenshot of a disabled alarm the off thumb came out `(176, 211, 186)` against a track of
 * `(221, 224, 229)` — **1.23:1, where a non-text control needs 3:1** — and the track itself was
 * 1.25:1 against the card behind it. The whole control had effectively vanished, which is what "I
 * can't tell whether the alarm is on" is. `onSurfaceVariant` measures 5.6:1 on that same card.
 *
 * This is the third time this class has bitten (see `InkMutedDark`, and the `onSurfaceVariant`
 * failure at 2.81:1): **an alpha or a quiet grey that is fine on an opaque Material surface is not
 * fine on glass, and the only way to know is to sample a real screenshot.**
 */
@Composable
fun crystalSwitchColors(): SwitchColors = if (isCrystal()) {
    SwitchDefaults.colors(
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = crystalControlColor(MaterialTheme.colorScheme.surfaceContainerHighest),
        uncheckedBorderColor = MaterialTheme.colorScheme.outline
    )
} else {
    SwitchDefaults.colors()
}

/** A `Checkbox`'s colours: glass box when unchecked, accent when checked. */
@Composable
fun crystalCheckboxColors(): CheckboxColors = if (isCrystal()) {
    CheckboxDefaults.colors(
        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedColor = MaterialTheme.colorScheme.primary,
        checkmarkColor = MaterialTheme.colorScheme.onPrimary
    )
} else {
    CheckboxDefaults.colors()
}

/** A `RadioButton`'s colours. Strokes rather than a fill, so this only needs the accent pinned. */
@Composable
fun crystalRadioButtonColors(): RadioButtonColors = if (isCrystal()) {
    RadioButtonDefaults.colors(
        selectedColor = MaterialTheme.colorScheme.primary,
        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
} else {
    RadioButtonDefaults.colors()
}

/**
 * A `Slider`'s colours. The **inactive track is the part that matters** — it is an opaque bar the
 * full width of the control, and left at Material's default it is the flattest object on any Crystal
 * settings screen. (The Crystal *intensity* slider is one of them, which is worth a moment: the
 * control that tunes the glass did not wear it.)
 */
@Composable
fun crystalSliderColors(): SliderColors = if (isCrystal()) {
    SliderDefaults.colors(
        inactiveTrackColor = crystalControlColor(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
} else {
    SliderDefaults.colors()
}

/**
 * One helper for every **selectable** chip — `FilterChip` and `InputChip`.
 *
 * Selection follows the app's rule rather than Material's: the selected container is the same frosted
 * fill [crystalSelectedChipColor] gives a hand-rolled pill, and the accent moves to the **label**.
 * That is what makes an M3 chip and one of the app's own day-circles read as the same object.
 */
@Composable
fun crystalFilterChipColors(): SelectableChipColors = if (isCrystal()) {
    FilterChipDefaults.filterChipColors(
        containerColor = crystalControlColor(MaterialTheme.colorScheme.surfaceContainerHighest),
        selectedContainerColor = crystalSelectedChipColor(MaterialTheme.colorScheme.secondaryContainer),
        selectedLabelColor = MaterialTheme.colorScheme.primary,
        selectedLeadingIconColor = MaterialTheme.colorScheme.primary
    )
} else {
    FilterChipDefaults.filterChipColors()
}

/** The non-selectable chips — `AssistChip` and `SuggestionChip`. Container only; they have no state. */
@Composable
fun crystalAssistChipColors(): ChipColors = if (isCrystal()) {
    AssistChipDefaults.assistChipColors(
        containerColor = crystalControlColor(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
} else {
    AssistChipDefaults.assistChipColors()
}

/**
 * A `SegmentedButton`'s colours — the Pomodoro/Timer switch, the habit frequency picker and the
 * wake-up check difficulty.
 *
 * **The active half is the one that was missing.** `AddTimerSheet` already tinted the *inactive*
 * container and left the active one on Material's opaque `secondaryContainer`, so the control was
 * half converted and the two halves visibly disagreed — which reads worse than not converting it at
 * all. Both halves come from here now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun crystalSegmentedButtonColors(): SegmentedButtonColors = if (isCrystal()) {
    SegmentedButtonDefaults.colors(
        activeContainerColor = crystalSelectedChipColor(MaterialTheme.colorScheme.secondaryContainer),
        activeContentColor = MaterialTheme.colorScheme.primary,
        activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
        inactiveContainerColor = crystalControlColor(MaterialTheme.colorScheme.surfaceContainerHighest),
        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant
    )
} else {
    SegmentedButtonDefaults.colors()
}

/**
 * An `OutlinedTextField`'s colours. The container goes to glass and the indicator follows the accent.
 *
 * Most text entry in the app is `FluidTextField`, which is already a glass `Box` around a transparent
 * `TextField` — this is for the handful of real `OutlinedTextField`s (search fields, the tag entry,
 * account settings) that never went through it and stayed stock Material on the field.
 */
@Composable
fun crystalTextFieldColors(): TextFieldColors = if (isCrystal()) {
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = crystalControlColor(MaterialTheme.colorScheme.surfaceContainerHighest),
        unfocusedContainerColor = crystalControlColor(MaterialTheme.colorScheme.surfaceContainerHighest),
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
    )
} else {
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * A filled `Button`'s colours — **transparent under Crystal**, exactly as [crystalFabContainerColor]
 * is, because the glass is painted by the caller's modifier and the accent moves to the label.
 *
 * This reaches further than any other helper here: `MomentumButton` is the primary action button in
 * **all nine editor sheets**, and it painted an opaque `primary` slab in every one of them.
 */
@Composable
fun crystalButtonColors(): ButtonColors = if (isCrystal()) {
    ButtonDefaults.buttonColors(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
} else {
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * A `FilledIconButton`'s colours, **paired with `Modifier.crystalIconButtonSurface()`** — the same
 * split `crystalFabContainerColor` uses, and for the same reason: a `FilledIconButton` paints its own
 * container over whatever the modifier drew, so the container has to step aside for the glass to be
 * visible at all.
 *
 * Reaching for `crystalControlColor` on the container instead gets you a *tint* and nothing else — no
 * blur, no rim — which is the halfway state the timer's play/reset/delete buttons shipped in and were
 * reported twice as "not Crystal". A tint is right for a small decorative shape sitting **on** a
 * panel; a button that the user presses is an object, and it takes the tier.
 *
 * [accent] marks the one primary action in a cluster (play/pause). Everything else takes the muted
 * role, so a row of three glass discs still has a hierarchy and Delete does not read as the main
 * thing to do.
 *
 * **The disabled pair is stated, and it is a role rather than an alpha.** Material's defaults are
 * `onSurface` at 0.12 and 0.38 — opaque greys computed against a flat surface, which come out as a
 * muddy blob on glass — and an alpha of our own would collapse the same way every other one has
 * (see `crystalSwitchColors`). `outline` is the quiet role that survives a translucent backdrop.
 */
@Composable
fun crystalFilledIconButtonColors(accent: Boolean = false): IconButtonColors = if (isCrystal()) {
    IconButtonDefaults.filledIconButtonColors(
        containerColor = Color.Transparent,
        contentColor = if (accent) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = MaterialTheme.colorScheme.outline
    )
} else {
    IconButtonDefaults.filledIconButtonColors(
        containerColor = if (accent) MaterialTheme.colorScheme.secondaryContainer
                         else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (accent) MaterialTheme.colorScheme.onSecondaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        disabledContentColor = MaterialTheme.colorScheme.outline
    )
}

/**
 * A `ListItem`'s colours. Material paints an opaque `surface` container, which inside a Crystal
 * dialog is a white slab over the field — the calendar picker is the live case.
 */
@Composable
fun crystalListItemColors(): ListItemColors = if (isCrystal()) {
    ListItemDefaults.colors(containerColor = Color.Transparent)
} else {
    ListItemDefaults.colors()
}

/**
 * Container colour for a floating action button: **transparent under Crystal**, because the fill is
 * drawn by [crystalFabSurface] instead.
 *
 * This reverses an earlier decision, deliberately and at the user's direction (2026-08-24). The FAB
 * used to keep the accent in every theme on the argument that it is the one control the eye is
 * supposed to find first, and Crystal only made the `primary` disc slightly translucent. Seen on the
 * device beside a converted week strip, a converted nav bar and converted cards, a tinted accent
 * disc was the last flat Material object on the screen — "it should be a crystal button like a
 * semi-transparent crystal button because that's what we're going for". Findability now comes from
 * the accent *content* and the rim, which is the same trade every other selection surface in the app
 * already makes.
 *
 * Five FABs take this — Today, Clock, Journal, Notes and a notebook — because a glass FAB on one tab
 * beside a flat one on the next is worse than either everywhere.
 */
@Composable
fun crystalFabContainerColor(): Color = if (isCrystal()) {
    Color.Transparent
} else {
    MaterialTheme.colorScheme.primary
}

/**
 * The content colour paired with [crystalFabContainerColor]. Under Crystal the accent moves from the
 * fill to the glyph — `onPrimary` on a frosted panel would be a near-white plus on a pale field.
 */
@Composable
fun crystalFabContentColor(): Color = if (isCrystal()) {
    MaterialTheme.colorScheme.primary
} else {
    MaterialTheme.colorScheme.onPrimary
}

/**
 * A frosted disc behind a header `IconButton` — the iOS-toolbar treatment for the loose icons that
 * sit **directly on the ambient field** rather than inside a [CrystalBar].
 *
 * An `IconButton` has no container of its own: it is a 48dp touch target with a tinted 24dp glyph
 * and nothing behind it. On a flat theme that is right, because the glyph sits on a plain surface.
 * On the mesh it leaves an accent-coloured Material glyph floating on glass with no object under
 * it, which is what reads as "still flat Material" next to converted cards, pills and chips.
 *
 * **Not for an icon inside a `CrystalBar`.** A bar is chrome, and a disc floating on it reads as a
 * bubble pasted onto a window.
 *
 * **It IS for a button on a card, and that restriction used to be over-broad.** This said "only for
 * icons on the raw field", which ruled out the timer cards' play / reset / delete on the reasoning
 * that a card is already glass. They were given a `crystalControlColor` tint instead — colour with no
 * blur and no rim — and the user reported them as still not Crystal, twice. Nested glass is the look
 * this theme is a port *of*; what does not work is glass on **chrome**, which is the bar case above.
 * Pair it with [crystalFilledIconButtonColors] so the component's own container steps aside.
 *
 * **Below 48dp it must not go on an M3 icon button — put it on a `Box` instead.** The glass renders
 * at the button's *layout node*, and `minimumInteractiveComponentSize` floors that at 48dp while the
 * `Modifier.size()` the call site wrote still governs the space reserved in the row. So a 32dp button
 * paints a 48dp disc into a 32dp slot and silently overlaps its neighbours. Measured on hardware
 * 2026-08-27: the timer card's Reset/Delete/Play, declared 32/32/40dp, all drew 126px (48dp) across
 * on 95px centres. `TimerGlassButton` in `ui/reminders/ClockScreen.kt` is the shape of the fix — a
 * `Box` has no minimum-size machinery, so the disc is exactly what it is sized to. Every other call
 * site passes 48dp or more, or no size at all, which is why this only ever showed up there.
 */
@Composable
fun Modifier.crystalIconButtonSurface(): Modifier = if (isCrystal()) {
    this.crystalSurface(
        shape = CircleShape,
        fill = MaterialTheme.colorScheme.surfaceContainerHighest,
        elevation = 0.dp,
        crystalElevation = 1.dp
    )
} else {
    this
}

/**
 * The FAB's glass, as a modifier — `FloatingActionButton` takes `containerColor: Color` and so
 * cannot be handed a blur any more than `NavigationBarItem` can (see [CrystalNavIndicator]). Its
 * `modifier`, though, wraps the whole button, so the panel can be painted there and the container
 * left transparent.
 *
 * Apply it **last**, after any `align`/`padding`: padding before the surface keeps the glass to the
 * button itself, whereas painting first would inflate the panel to include the margin.
 *
 * A slightly deeper `crystalElevation` than a day chip's 2dp — a FAB genuinely floats above the
 * content, and this is the shadow the *panel* casts, not `FloatingActionButton`'s own, which
 * [crystalFabElevation] still zeroes to keep the octagon away.
 */
@Composable
fun Modifier.crystalFabSurface(): Modifier = if (isCrystal()) {
    this.crystalSurface(
        shape = CircleShape,
        fill = MaterialTheme.colorScheme.surfaceContainerHighest,
        elevation = 0.dp,
        crystalElevation = 3.dp
    )
} else {
    this
}

/**
 * The shadow a FAB should cast under Crystal: **none** — and unlike [crystalControlElevation], that
 * includes Solid.
 *
 * The defect is the same one [crystalControlElevation] exists for. Android tessellates a shape's
 * shadow outline into a coarse convex polygon; an opaque fill hides it, a translucent fill lets it
 * show *through*. On a `CircleShape` FAB that renders as a visible **octagon inside the circle**,
 * offset downward with the shadow. It shipped in 1.85's Crystal work because
 * [crystalFabContainerColor] made the fill translucent while all five call sites kept
 * `FloatingActionButton`'s default 6dp.
 *
 * This is a separate function rather than a call to [crystalControlElevation] because the FAB's
 * *fill* follows a separate rule, and elevation has to follow the fill. A control filled by
 * [crystalControlColor] really is opaque under Solid — `withSolidGlassSurfaces` pre-composites the
 * surface roles — which is what earns Solid its shadow there. `primary` is an **accent** role and is
 * never pre-composited, so [crystalFabContainerColor] takes alpha under *every* Crystal background,
 * Solid included. Borrowing the control rule here would leave the octagon on Solid.
 */
@Composable
fun crystalFabElevation(): FloatingActionButtonElevation = if (isCrystal()) {
    FloatingActionButtonDefaults.elevation(
        defaultElevation = 0.dp,
        pressedElevation = 0.dp,
        focusedElevation = 0.dp,
        hoveredElevation = 0.dp
    )
} else {
    FloatingActionButtonDefaults.elevation()
}


/**
 * Installs the ambient field for one window, and publishes it as the blur source for everything
 * inside. Under the two Material styles it is a pass-through that adds no layout node and no
 * overdraw at all.
 *
 * **One root per window, and the field is hoisted as high as it can go.** The design this came from
 * gave every *screen* its own field and its own scaffold; Rhythm puts a single root above the
 * NavHost instead, so the field stays fixed while tabs cross-fade over it — which is what the look
 * is imitating — and every existing `Scaffold`, top bar, FAB and snackbar host keeps working
 * untouched. The cost is that anything with its own window needs its own root: each alert Activity,
 * each widget config Activity, and the journal lock.
 *
 * **A bottom sheet or a dialog must not get a root, and must not inherit one either.** They render
 * in a separate window, so a panel inside one that tried to blur this field would be sampling a
 * source that is not in its window — nothing useful comes back. Those wrappers provide
 * their own field instead ([CrystalWindowContent]), or `LocalBlurredField = null` (a menu), which
 * routes every panel inside to the translucent fill. That fill is a finished look, not a degraded
 * one; it is also exactly what every device below API 32 sees everywhere.
 *
 * **The field is the only blur source, so chrome blurs the field and not the content scrolling under
 * it.** Making a bar blur live content means marking the content region as a source, and the bars
 * live inside that region — a node blurring a source that contains itself smears the previous frame
 * across the current one. This is a deliberate limit, not an oversight.
 */
@Composable
fun CrystalRoot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val crystal = isCrystal()
    // On a flat field there is nothing to blur, so no field is published at all, and panels take the
    // translucent path, which produces exactly the same pixels over a uniform colour. Below API 32
    // there is no blur either: panels take the old-API faux fill, as they always have.
    val field = rememberBlurredFieldOrNull(crystal && !isSolidField)
    val blurRadius = crystalBlurRadius()
    // **`content` is composed from one place in every style, and that is a bug fix rather than a
    // preference.** This used to early-return `content()` bare when `!isCrystal()`, against a
    // `Box { CompositionLocalProvider(content = content) }` under Crystal — two positions in the
    // composition for the same lambda. Compose keys a subtree by *where* it is called, so switching
    // to or from Crystal disposed everything below here and rebuilt it: the `NavHost` lost its back
    // stack and restarted at Today, which is why picking a theme in Settings threw the user out of
    // Settings. `BoriTheme` had the same defect one level up and both had to go.
    //
    // The `AmbientBackground` sibling appearing and disappearing is safe — Compose keys siblings by
    // call site, not by index, so `content`'s identity does not move with it. The flat themes now
    // carry one extra `fillMaxSize` `Box`; `MainActivity` already wraps its content in one, so this
    // is inert, and the flat-theme pixel diff is what proves it.
    Box(modifier.fillMaxSize()) {
        if (crystal) {
            // The Borikén petroglyphs live inside the same blur source as the colour field, so the
            // glass blurs them like the field instead of covering them in hard-edged pieces.
            Box(Modifier.fillMaxSize().let { if (field != null) it.blurredFieldSource(field, blurRadius) else it }) {
                AmbientBackground(Modifier.fillMaxSize())
                BorikenPetroglyphs()
            }
        } else if (LocalBoriken.current) {
            // Material 3 / Expressive under Borikén: the page and its solid petroglyphs, painted
            // once here; the screens above are transparent (see crystalScaffoldColor).
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                BorikenPetroglyphs()
            }
        }
        CompositionLocalProvider(LocalBlurredField provides field, content = content)
    }
}

/**
 * The panel field, painted from **outside** a shell's own padding.
 *
 * This exists for exactly one shape of problem: a shell that wraps our content in padding *and* in a
 * clipping container, so that nothing supplied through its content slot can reach the padding. M3's
 * `DropdownMenu` is that shell — see [CrystalWindowContent] for the disassembly and for the band it
 * produced. The modifier a caller hands to `DropdownMenu` is applied **first**, before the padding
 * and before the clip, so a background drawn here covers the whole menu including the strips.
 *
 * Pair it with `CrystalWindowContent(paintField = false)`, or the window paints a second gradient
 * sized to the inner box and the two disagree where they meet.
 *
 * **It draws the screen's mesh, not the panel gradient, and that is the fix for "too many
 * colours" (2026-08-23).** It used to paint `panelFieldStops` corner-to-corner *of the menu*, so a
 * ~300x420px box traversed the entire five-hue palette while the screen behind it moved barely at
 * all — measured on the notebook menu at hue 273->314 inside against 287->298 outside. A popup is
 * small, so the same code that reads as a wash across a sheet reads as a rainbow in a menu. It now
 * draws [drawCrystalMeshField] at **screen scale**, offset by the menu's own position, so it shows
 * the patch of field it covers — one or two hues, continuous with what surrounds it.
 *
 * This is why the Notes tab was the only place the defect was reported: **six of the app's seven
 * `BoriDropdownMenu` call sites are in it**, and every other Crystal popup is a sheet or dialog
 * big enough to hide the problem. Those still draw `CrystalFieldStyle.Panel`; they are the second
 * half of this change and are deliberately not done here.
 *
 * **The background carries no shape, and it must not be given one.** This looks like a bug — a
 * `background(brush)` with no shape draws `RectangleShape`, so the obvious reading is that the
 * gradient squares off the menu's rounded corners. It does not, and adding
 * `shape = MaterialTheme.shapes.medium` here would be a fix for a defect that does not exist. The
 * caller's modifier does **not** reach M3's `Surface`; it is passed down into that Surface's
 * *content* lambda — `MenuKt.DropdownMenuContent_Qj0Zi0g$lambda$7`, where it is param 0 and the
 * padding is applied on top of it. `Surface` builds its own chain
 * (`graphicsLayer -> border -> background(color, shape) -> clip(shape)`) from a **freshly created**
 * modifier, and its `clip` is therefore an ancestor of everything the content lambda draws. Our
 * gradient is clipped to the menu's shape by that ancestor. Verified against material3
 * 1.5.0-alpha17 bytecode on 2026-08-22, after a review flagged the corners as broken.
 *
 * A no-op off Crystal, and under Solid — there the shell's own flat container colour is already the
 * field, so there is nothing a gradient would add and the band cannot occur.
 */
@Composable
fun Modifier.crystalMenuField(): Modifier {
    if (!isCrystal() || isSolidField) return this
    val dark = isDark
    val base = crystalFieldBase(dark)
    val blobs = crystalFieldBlobs(LocalCrystalMesh.current, dark)
    val dilution = crystalFieldDilution(dark)
    val screen = with(LocalContext.current.resources.displayMetrics) {
        Size(widthPixels.toFloat(), heightPixels.toFloat())
    }
    // Unspecified until the popup has been laid out. `onGloballyPositioned` runs after the first
    // composition, so on the opening frame there is no offset to draw with — and a field that
    // visibly jumps as the menu opens is worse than the miniature it replaces.
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    // The field alone is not a panel. Drawing only the patch of field the menu covers makes the
    // menu **pixel-identical to what is behind it** (measured: within +/-2 per channel on the
    // Fold), so it loses every edge and reads as a hole rather than a surface. The old panel
    // gradient separated by accident — it lerps toward a near-white base, so it came out lighter
    // than the screen mesh — and replacing it removed that accident along with the rainbow.
    // A menu is a panel like any card, so it takes the same tint every card takes.
    val tint = MaterialTheme.colorScheme.surfaceContainer
    val tintAlpha = crystalSurfaceAlpha(
        LocalCrystalStyle.current, dark, LocalCrystalIntensity.current
    )
    return this
        .onGloballyPositioned { origin = it.positionOnScreen() }
        .drawBehind {
            // Skip the frame rather than guess. The menu is animating in over it, so one frame with
            // the shell's own container showing should not be visible — verify on a slowed
            // animation (`settings put global window_animation_scale 10`), not on a screenshot.
            if (origin.isUnspecified) return@drawBehind
            drawCrystalMeshField(base, blobs, dark, fieldSize = screen, origin = origin, dilution = dilution)
            drawRect(tint.copy(alpha = tintAlpha))
        }
}

/**
 * The container colour for a shell whose field is supplied by [crystalMenuField].
 *
 * **Transparent under a mesh, and that is the point.** The shell's container is the one thing that
 * paints its padding strips, and `crystalSheetColor` — which is the panel gradient's *first stop* —
 * can only agree with a diagonal gradient at one corner. Handing the shell nothing lets the field
 * behind it show through instead.
 *
 * Off Crystal, and under Solid, it stays exactly what it was.
 */
@Composable
@ReadOnlyComposable
fun crystalMenuContainerColor(fallback: Color): Color =
    if (isCrystal() && !isSolidField) Color.Transparent else crystalSheetColor(fallback)

/**
 * A Crystal root for a sub-composition that lives in **its own window** — a bottom sheet, a dialog,
 * a popup. Gives that window its own field and its own blur source, so the controls inside are real
 * glass rather than flat boxes.
 *
 * Two things force this to be separate from [CrystalRoot] rather than a reuse of it.
 *
 * **The inherited source is unusable and must be replaced, not just severed.** CompositionLocals do
 * flow into these sub-compositions even though the window does not, so a panel inside a sheet would
 * otherwise sample the screen's [BlurredField], positioned for a window it is not in. Severing it alone was
 * the first attempt: every control then fell back to a translucent fill over the sheet's own dark
 * container, and the sheets came out as flat near-black panels with no glass in them at all.
 *
 * **The field must not claim space.** A sheet wraps its content, so the field is drawn with
 * `matchParentSize` — which contributes nothing to measurement — rather than `fillMaxSize`, which
 * would stretch every sheet to the full window.
 *
 * **Chrome outside the content slot cannot be reached from in here, and [paintField] is how you opt
 * out.** A window's shell sometimes lays out furniture *outside* the content slot — a sheet's drag
 * handle, the vertical padding M3 puts around a menu's items. Painting those strips with one flat
 * colour does not work: the field is a *diagonal* gradient, so a single colour can only match it at
 * one corner. Measured on a Fold cover screen, a sheet's handle strip sat at a constant
 * (184,226,224) while the field one pixel below ran from (184,224,226) on the left to (194,201,245)
 * on the right — a seam of 25 in green across the width, which read as a mint band across the top of
 * every sheet.
 *
 * A `bleed` parameter used to live here that measured the field taller at each end and placed it at
 * `-bleed`, to paint *through* that furniture. **It was removed on 2026-08-22 because it cannot
 * work for a menu, which was its only caller.** M3 builds the menu's content as
 * `modifier.padding(vertical = DropdownMenuVerticalPadding).width(IntrinsicSize.Max)
 * .verticalScroll(scrollState)` — read out of `MenuKt.DropdownMenuContent_Qj0Zi0g$lambda$7`,
 * offsets 51-74, in material3 1.5.0-alpha17 — so the padding is *outermost* and a
 * **clipping** scroll container sits between it and us. The bleed grew the layout exactly as
 * designed and `clipScrollableContainer` then discarded the part that was meant to cover the strips.
 * The container colour showed there instead, and since that colour is the gradient's first stop it
 * rendered as a teal band above and below the notebook menu.
 *
 * The working answer is to paint the field from **outside** the shell's padding, on the modifier the
 * shell applies first — [crystalMenuField] — and to pass `paintField = false` here so the window
 * does not draw a second, differently-sized gradient inside the first.
 *
 * Prefer moving the chrome inside this composable where the shell allows it — that is what
 * [com.bori.theme.util.BoriSheet] does with its handle, and it needs none of this.
 */
@Composable
fun CrystalWindowContent(paintField: Boolean = true, content: @Composable () -> Unit) {
    // **This early return is the same shape that was removed from [CrystalRoot] and `BoriTheme` on
    // 2026-08-26** — `content` composed from two different positions, so flipping `isCrystal()`
    // disposes everything inside. There it destroyed the NavHost's back stack; here it is currently
    // *unreachable*, because nothing can change theme style while a sheet or dialog is open (the
    // picker lives in an inline Settings card, not in a window).
    //
    // It is left alone deliberately rather than fixed on principle. Making it always wrap would put
    // this window's `MaterialTheme(windowScheme)`, its `Box` and its `LocalContentColor` anchor
    // around every sheet in the **flat** themes too, where today it is a pure pass-through — and
    // `windowScheme` re-derives the surface roles denser, so that is a visible change to two themes
    // the project treats as finished. If a way to switch styles from inside a window is ever added,
    // fix this first, and guard the scheme and the content colour on `isCrystal()` so the flat
    // themes keep passing straight through.
    if (!isCrystal()) {
        content()
        return
    }
    // No field means nothing to blur, so there must be no blurred field either — one that is
    // published but never fed is the "severed source" failure this composable exists to avoid, and
    // it renders as flat near-black panels rather than as an error.
    val field = rememberBlurredFieldOrNull(!isSolidField && paintField)
    val blurRadius = crystalBlurRadius()
    // The surface *roles* are re-derived denser for this window. Most of a picker's controls are
    // plain `Surface(color = surfaceContainerHighest)` — text fields, the sound and vibration rows,
    // the duration box — and they never go near this kit, so the only way to reach them is the
    // colour they already ask for. Without this they keep the screen's alpha and dissolve into the
    // sheet's own gradient, which is what made the pickers read as ghosts.
    //
    // **Solid is exempt, and the reason is that the ghosting problem does not exist there.** The
    // denser band buys separation against a sheet's *own smooth gradient*, which differs from the
    // screen's mesh; blurring a smooth gradient returns the same gradient, so a screen-weight tint
    // has nothing to separate against. Under Solid a sheet's field is `solidFieldColor()` — the very
    // same flat colour as the screen's — so the roles the screen already pre-composited are exactly
    // right in here too. Re-deriving them would also be actively wrong: they are opaque now, and
    // putting alpha back on them would composite the field into itself a second time.
    // **The window now takes the SCREEN band, not a denser one, because its backdrop is now the
    // screen's own field.** The dense band existed to buy separation against the sheet's own smooth
    // diagonal gradient — blurring a smooth gradient returns the same gradient, so a screen-weight
    // tint had nothing to separate against and every control was pushed to near-opaque to be seen at
    // all. At Sheer/intensity 1.0 that meant **0.92 alpha inside a sheet against 0.66 on the Today
    // screen**, which is the whole reason "make the boxes Crystal" kept failing: the template rows,
    // the New Notebook field and button, and the add-menu tiles were all correctly wired to the kit
    // and still rendered as solid slabs. Below, the field is the real mesh at screen scale, so the
    // compensation is not just unnecessary — it is what was hiding the field.
    //
    // Solid still short-circuits: there the field genuinely is one flat colour, the roles are already
    // pre-composited opaque by `withSolidGlassSurfaces`, and putting alpha back on them would
    // composite the field into itself a second time.
    val windowScheme = if (isSolidField) {
        MaterialTheme.colorScheme
    } else {
        MaterialTheme.colorScheme.withTranslucentSurfaces(
            crystalSurfaceAlpha(
                LocalCrystalStyle.current,
                isDark,
                LocalCrystalIntensity.current
            )
        )
    }
    // Where this window sits on screen, so the field below can draw the patch it covers rather than a
    // miniature of the whole thing. Unspecified until the window has been laid out — see the guard in
    // the draw, which is `crystalMenuField`'s and is there so the field cannot visibly jump as the
    // sheet opens.
    val screenSize = with(LocalContext.current.resources.displayMetrics) {
        Size(widthPixels.toFloat(), heightPixels.toFloat())
    }
    var windowOrigin by remember { mutableStateOf(Offset.Unspecified) }
    val meshDark = isDark
    val meshBase = crystalFieldBase(meshDark)
    val meshBlobs = crystalFieldBlobs(LocalCrystalMesh.current, meshDark)
    val meshDilution = crystalFieldDilution(meshDark)
    if (field != null) BlurredScreenMesh(field, blurRadius, meshBase, meshBlobs, meshDark, meshDilution, screenSize)
    MaterialTheme(colorScheme = windowScheme) {
        Box {
            if (paintField) {
                // **The screen's mesh, offset by this window's position — not a gradient of its
                // own.** `CrystalFieldStyle.Panel` painted a linear gradient from this box's corner
                // to its corner, so a sheet showed the entire five-hue palette compressed into its
                // own bounds instead of the piece of field behind it. Glass shows what is behind it;
                // that showed a miniature of everything. `drawCrystalMeshField` has taken
                // `fieldSize`/`origin` since the dropdown menus were fixed this way on 2026-08-22 —
                // this is that same proven mechanism, finally applied to sheets and dialogs.
                //
                // The tint on top is not optional: with the field alone the window becomes
                // pixel-identical to what is behind it and reads as a hole rather than a surface.
                // A sheet is a panel like any card, so it takes the tint any card takes.
                // Panels in here don't blur this box: [BlurredScreenMesh] blurs the whole screen's
                // mesh once, and a panel draws the patch at its own screen position — the same
                // pixels this box shows behind it, without re-blurring as the sheet slides in.
                Box(
                    modifier = Modifier.matchParentSize()
                        .onGloballyPositioned { windowOrigin = it.positionOnScreen() }
                        .drawBehind {
                            if (windowOrigin.isUnspecified) return@drawBehind
                            drawCrystalMeshField(
                                meshBase, meshBlobs, meshDark,
                                fieldSize = screenSize, origin = windowOrigin, dilution = meshDilution
                            )
                        }
                )
            }
            CompositionLocalProvider(
                LocalBlurredField provides field,
                LocalCrystalInWindow provides true,
                // MaterialTheme does not set a content colour, and the sheet's container is a raw
                // gradient stop rather than a Material role — so whatever the caller resolved
                // `contentColorFor` to is not to be trusted here. Anchor it, same rule as
                // crystalScaffoldContentColor.
                LocalContentColor provides windowScheme.onSurface,
                content = content
            )
        }
    }
}
