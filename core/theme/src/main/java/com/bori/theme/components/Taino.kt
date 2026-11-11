package com.bori.theme.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bori.theme.LocalBoriken
import com.bori.theme.BorikenRed
import com.bori.theme.BorikenCeleste
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.height
import com.bori.theme.util.BoriAlertDialog
import kotlin.math.cos
import kotlin.math.sin

/**
 * Taíno petroglyphs for the hidden **Borikén** edition — the coquí, the Sol de Jayuya, the spiral
 * and a cemí face, the marks carved on the ceremonial stones of Caguana and Jayuya. All four are
 * single-colour line art on a 24-unit grid, drawn here rather than shipped as resources so the
 * edition adds no assets to the APK and every mark follows the theme's colours.
 */
object TainoGlyphs {
    private const val STROKE = 1.5f
    /** The sun is the busiest mark — eyes, nose, mouth, eight lobes with dots — so it draws finer,
     *  or at header size its rings fill in and the lobes run together. */
    private const val SUN_STROKE = 0.8f

    private class GlyphScope(
        private val builder: ImageVector.Builder,
        /** Line weight; the cemí draws finer than the rest, like the incised outline zemí. */
        private val lineWidth: Float = STROKE
    ) {
        fun stroke(block: PathBuilder.() -> Unit) {
            builder.path(
                stroke = SolidColor(Color.Black), strokeLineWidth = lineWidth,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block
            )
        }
        fun dot(cx: Float, cy: Float, r: Float) {
            builder.path(fill = SolidColor(Color.Black)) {
                moveTo(cx - r, cy)
                arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
                arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
                close()
            }
        }
        /** An ellipse turned [rotationDeg] clockwise (y points down), as SVG's `rotate()` does. */
        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rotationDeg: Float = 0f) = stroke {
            val t = Math.toRadians(rotationDeg.toDouble())
            val dx = (rx * kotlin.math.cos(t)).toFloat()
            val dy = (rx * kotlin.math.sin(t)).toFloat()
            moveTo(cx - dx, cy - dy)
            arcTo(rx, ry, rotationDeg, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + dx, y1 = cy + dy)
            arcTo(rx, ry, rotationDeg, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - dx, y1 = cy - dy)
        }
        /**
         * A smooth stroke **through** the points given as x, y pairs: a centripetal Catmull-Rom
         * curve, as cubic Béziers. The trace stays exactly on the points, but the corners between
         * them round off — a carved line has no sharp elbows. Centripetal rather than uniform so
         * unevenly spaced points can't loop or overshoot. The ends get mirrored phantom points, so
         * each end leaves along its own last segment.
         */
        fun smooth(vararg xy: Float) = stroke {
            val pts = (xy.indices step 2).map { Offset(xy[it], xy[it + 1]) }
            val p = listOf(pts[0] * 2f - pts[1]) + pts + listOf(pts.last() * 2f - pts[pts.size - 2])
            fun d(a: Offset, b: Offset) = maxOf(kotlin.math.sqrt((a - b).getDistance()), 1e-4f)
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until p.size - 2) {
                val (p0, p1, p2, p3) = listOf(p[i - 1], p[i], p[i + 1], p[i + 2])
                val d1 = d(p0, p1); val d2 = d(p1, p2); val d3 = d(p2, p3)
                val c1 = (p2 * (d1 * d1) - p0 * (d2 * d2) + p1 * (2 * d1 * d1 + 3 * d1 * d2 + d2 * d2)) /
                    (3 * d1 * (d1 + d2))
                val c2 = (p1 * (d3 * d3) - p3 * (d2 * d2) + p2 * (2 * d3 * d3 + 3 * d3 * d2 + d2 * d2)) /
                    (3 * d3 * (d3 + d2))
                curveTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
            }
        }
        fun circle(cx: Float, cy: Float, r: Float) = stroke {
            moveTo(cx - r, cy)
            arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
            arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        }
    }

    private fun glyph(name: String, lineWidth: Float = STROKE, block: GlyphScope.() -> Unit): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f)
        GlyphScope(b, lineWidth).block()
        return b.build()
    }

    /**
     * The Sol Taíno, as carved and as worn: a round face ringed by eight rounded petal-lobes, ringed
     * eyes, a nose ending in two nostril curls, and an **open mouth** — a petroglyph, not a smiley.
     * An earlier version drew a smiling cartoon sun, which the user rightly called out.
     */
    val Sun: ImageVector by lazy {
        glyph("Sun", lineWidth = SUN_STROKE) {
            // Eight lobes: each a rounded bump between two points on a circle just outside the face.
            val inner = 6.6f
            val outer = 10.6f
            for (i in 0 until 8) {
                val a0 = Math.toRadians(i * 45.0 - 90.0 - 17.0)
                val a1 = Math.toRadians(i * 45.0 - 90.0 + 17.0)
                val am = Math.toRadians(i * 45.0 - 90.0)
                stroke {
                    moveTo(12f + cos(a0).toFloat() * inner, 12f + sin(a0).toFloat() * inner)
                    curveTo(
                        12f + cos(a0).toFloat() * outer, 12f + sin(a0).toFloat() * outer,
                        12f + cos(a1).toFloat() * outer, 12f + sin(a1).toFloat() * outer,
                        12f + cos(a1).toFloat() * inner, 12f + sin(a1).toFloat() * inner
                    )
                }
                // A dot in each lobe, as on the carved stones.
                dot(12f + cos(am).toFloat() * 8.5f, 12f + sin(am).toFloat() * 8.5f, 0.55f)
            }
            circle(12f, 12f, 5.6f)
            // Ringed eyes.
            circle(9.9f, 10.6f, 1.15f)
            circle(14.1f, 10.6f, 1.15f)
            dot(9.9f, 10.6f, 0.45f)
            dot(14.1f, 10.6f, 0.45f)
            // Nose with nostril curls.
            stroke {
                moveTo(12f, 10.4f); lineTo(12f, 12.6f)
                moveTo(12f, 12.6f); quadTo(11.1f, 12.9f, 11.3f, 12.2f)
                moveTo(12f, 12.6f); quadTo(12.9f, 12.9f, 12.7f, 12.2f)
            }
            // Open mouth: a horizontal oval.
            stroke {
                moveTo(10.4f, 14.6f)
                arcTo(1.6f, 0.75f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 13.6f, y1 = 14.6f)
                arcTo(1.6f, 0.75f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 10.4f, y1 = 14.6f)
            }
        }
    }

    /** The sea turtle (jicotea) of the petroglyph sets: a round shell with a cross, head and flippers. */
    val Turtle: ImageVector by lazy {
        glyph("Turtle") {
            stroke {
                moveTo(12f, 6.2f)
                curveTo(16.6f, 6.2f, 17.6f, 10.5f, 17.6f, 12.6f)
                curveTo(17.6f, 16.6f, 14.8f, 18.4f, 12f, 18.4f)
                curveTo(9.2f, 18.4f, 6.4f, 16.6f, 6.4f, 12.6f)
                curveTo(6.4f, 10.5f, 7.4f, 6.2f, 12f, 6.2f)
            }
            // The cross on the shell.
            stroke { moveTo(9.4f, 9.6f); lineTo(14.6f, 15.6f); moveTo(14.6f, 9.6f); lineTo(9.4f, 15.6f) }
            circle(12f, 4.2f, 1.6f)                      // head
            stroke { moveTo(7.2f, 9f); quadTo(4f, 7.4f, 3.2f, 9.8f) }     // front flippers
            stroke { moveTo(16.8f, 9f); quadTo(20f, 7.4f, 20.8f, 9.8f) }
            stroke { moveTo(7.4f, 16.4f); quadTo(5f, 18.6f, 6.2f, 20f) }  // back flippers
            stroke { moveTo(16.6f, 16.4f); quadTo(19f, 18.6f, 17.8f, 20f) }
            stroke { moveTo(12f, 18.4f); lineTo(12f, 20.4f) }             // tail
        }
    }

    /** The three-pointed zemí stone (trigonolito) in outline, face on the front slope. */
    val Cemi: ImageVector by lazy {
        // A three-pointer in profile, facing left, after the outline zemí the user chose
        // (Bori/Taino_symbols/s-l300.jpg) — drawn fresh, simplified for small sizes. Domed cone and
        // band; on the front edge a tall tilted eye socket with the eye inside; a small nose; one
        // mouth loop sloping back with an inner curl; an open ear with its curl; two marks by the
        // back point; long points flaring off an arched base. Thin, even line, as on the decal.
        glyph("Cemi", lineWidth = 0.75f) {
            stroke {
                moveTo(11.2f, 7.4f)
                lineTo(11.4f, 6.5f)
                lineTo(11.8f, 3.0f)
                quadTo(12.8f, 1.0f, 13.8f, 3.0f)      // the domed cone
                lineTo(14.8f, 6.8f)
                quadTo(17.0f, 15.4f, 22.8f, 18.6f)    // concave back slope out to the back point
                quadTo(23.9f, 19.6f, 22.2f, 19.9f)
                quadTo(12.0f, 17.6f, 1.2f, 19.1f)     // arched base
                quadTo(0.1f, 18.8f, 1.0f, 17.9f)      // front point
                quadTo(4.4f, 16.6f, 6.6f, 15.8f)      // up to the jaw
            }
            stroke { moveTo(11.2f, 6.4f); quadTo(13.0f, 6.2f, 14.9f, 6.7f) }    // band
            ellipse(10.2f, 9.7f, 1.45f, 2.35f, rotationDeg = 35f)                // eye socket
            ellipse(9.8f, 10.1f, 0.75f, 1.3f, rotationDeg = 35f)                 // eye
            stroke {                                                              // nose
                moveTo(8.8f, 11.5f)
                quadTo(7.3f, 11.0f, 7.1f, 11.9f)
                quadTo(7.0f, 12.6f, 8.1f, 12.4f)
            }
            stroke {                                                              // mouth
                moveTo(8.1f, 12.4f)
                quadTo(10.8f, 12.6f, 11.9f, 14.3f)
                quadTo(12.5f, 15.7f, 10.6f, 15.9f)
                quadTo(8.4f, 16.0f, 6.6f, 15.8f)
            }
            stroke {
                moveTo(8.6f, 13.5f)
                quadTo(10.2f, 13.7f, 10.6f, 14.7f)
                quadTo(9.7f, 15.1f, 8.8f, 14.7f)
            }
            stroke {                                                              // ear
                moveTo(13.4f, 11.4f)
                quadTo(15.2f, 10.8f, 15.1f, 13.1f)
                quadTo(15.0f, 15.2f, 13.4f, 14.8f)
            }
            stroke {
                moveTo(14.1f, 12.2f)
                quadTo(13.2f, 12.8f, 14.0f, 13.4f)
                quadTo(14.4f, 13.8f, 13.8f, 14.1f)
            }
            stroke { moveTo(15.0f, 18.0f); lineTo(16.8f, 14.6f) }                // marks
            stroke { moveTo(16.0f, 18.4f); lineTo(17.7f, 15.1f) }
        }
    }

    /**
     * The coquí petroglyph from the app icon, traced stroke for stroke (see scripts/gen_bori_icon.py).
     * The limbs go through [GlyphScope.smooth], not straight segments: drawn as a polyline its elbows
     * were sharp corners, which the user called jagged, and the carving's limbs are rounded.
     */
    val Coqui: ImageVector by lazy {
        glyph("Coqui") {
            smooth(
                6.75f, 3.25f, 4.25f, 6.00f, 5.00f, 8.50f, 12.25f, 9.00f
            )
            smooth(
                12.25f, 9.00f, 19.00f, 8.50f, 21.00f, 7.25f
            )
            stroke {
                moveTo(12.25f, 9.00f)
                lineTo(12.25f, 6.50f)
            }
            smooth(
                12.25f, 9.00f, 11.75f, 13.00f, 11.75f, 16.25f
            )
            smooth(
                11.75f, 16.25f, 8.25f, 13.00f, 4.00f, 13.75f, 2.00f, 16.50f,
                4.50f, 19.25f, 7.75f, 20.50f, 7.50f, 22.50f
            )
            smooth(
                11.75f, 16.25f, 15.75f, 13.00f, 20.25f, 14.00f, 22.00f, 17.00f,
                20.50f, 19.50f, 18.25f, 21.00f, 18.50f, 22.50f
            )
            dot(6.75f, 3.00f, 1.98f)
            dot(21.00f, 7.25f, 1.85f)
            dot(10.50f, 4.25f, 1.35f)
            dot(15.50f, 5.25f, 1.35f)
        }
    }

    /** A Taíno spiral, the commonest mark on the island's petroglyph stones. Smoothed through its
     *  points, so the outer turns don't show facets at background-mark size. */
    val Spiral: ImageVector by lazy {
        glyph("Spiral") {
            smooth(
                12.60f, 12.00f, 12.64f, 12.05f, 12.75f, 12.23f, 12.80f, 12.45f,
                12.78f, 12.71f, 12.69f, 12.97f, 12.51f, 13.22f, 12.26f, 13.44f,
                11.93f, 13.60f, 11.55f, 13.68f, 11.13f, 13.66f, 10.70f, 13.53f,
                10.29f, 13.30f, 9.93f, 12.95f, 9.63f, 12.50f, 9.45f, 11.97f,
                9.38f, 11.39f, 9.45f, 10.77f, 9.68f, 10.16f, 10.05f, 9.59f,
                10.56f, 9.10f, 11.20f, 8.72f, 11.93f, 8.49f, 12.72f, 8.43f,
                13.54f, 8.55f, 14.34f, 8.85f, 15.07f, 9.35f, 15.69f, 10.02f,
                16.17f, 10.84f, 16.46f, 11.77f, 16.54f, 12.77f, 16.38f, 13.79f,
                16.00f, 14.78f, 15.39f, 15.69f, 14.57f, 16.46f, 13.58f, 17.04f,
                12.45f, 17.40f, 11.25f, 17.50f, 10.02f, 17.33f, 8.83f, 16.89f,
                7.74f, 16.17f, 6.82f, 15.21f, 6.11f, 14.05f, 5.67f, 12.73f,
                5.53f, 11.32f, 5.70f, 9.88f, 6.20f, 8.49f, 7.01f, 7.21f,
                8.10f, 6.12f, 9.42f, 5.29f, 10.93f, 4.75f, 12.55f, 4.56f,
                14.20f, 4.73f, 15.80f, 5.26f, 17.27f, 6.15f, 18.53f, 7.36f,
                19.51f, 8.84f, 20.15f, 10.53f, 20.41f, 12.35f, 20.26f, 14.21f,
                19.70f, 16.03f
            )
        }
    }

    val all: List<ImageVector> get() = listOf(Sun, Coqui, Spiral, Cemi, Turtle)

    /**
     * Each planner section has its own mark. Habits get the coquí — it sings to its love every night,
     * which is about as faithful a habit as there is.
     */
    fun forSection(title: String): ImageVector? = when (title) {
        "Habits" -> Coqui
        "Reminders" -> Sun
        "Events" -> Turtle
        "To-do List" -> Spiral
        else -> null
    }

    /** Each page has its own mark, so moving around the app turns up different carvings. */
    fun forPage(route: String?): ImageVector = when {
        route == null -> Sun
        route == "list" || route.startsWith("routine") -> Sun
        route == "stats" -> Coqui  // the Journal tab's route is "stats" — the coquí sings each night
        route == "clock" -> Spiral
        route == "notes" || route.startsWith("note") -> Cemi
        else -> Turtle
    }
}

/**
 * The faint petroglyphs behind every screen while Borikén is on: seven marks at fixed spots, so they
 * never reshuffle, at a few percent alpha so text is never fighting them. One is in the flag's red.
 * It is a single `Canvas` with no state, so it draws once and only redraws on a theme change.
 */
/**
 * The current page's mark, for the field's watermark. The field is drawn by `CrystalRoot`, above the
 * navigation host that knows the route, so the route's glyph is published here (by
 * `StandardNavigationContent`) and read **only while drawing** — a tab change repaints the marks
 * without recomposing anything.
 */
object BorikenPage {
    var glyph by mutableStateOf(TainoGlyphs.Sun)
}

/**
 * The faint petroglyphs behind every screen while Borikén is on. Under Crystal they are `CrystalRoot`
 * draws this inside the same blur source as the colour field, so glass panels blur the marks along
 * with the colours. They used to be a layer over the field, where every card hid most of a mark and
 * left a sharp fragment poking out between cards.
 *
 * Five marks, kept to the screen's edges and corners so they frame the content rather than sit under
 * it. Every other one is the current page's own symbol.
 */
@Composable
fun BorikenPetroglyphs(modifier: Modifier = Modifier) {
    if (!LocalBoriken.current) return
    val painters = TainoGlyphs.all.associateWith { rememberVectorPainter(it) }
    // Crystal: see-through, so the glass blurs them with the field. Material 3 / Expressive: the
    // same marks drawn solid, a shade off the page background — tone-on-tone, nothing see-through.
    val crystal = isCrystal()
    val bg = MaterialTheme.colorScheme.background
    val ink = if (crystal) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f)
              else androidx.compose.ui.graphics.lerp(bg, MaterialTheme.colorScheme.onBackground, 0.14f)
    val red = if (crystal) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
              else androidx.compose.ui.graphics.lerp(bg, MaterialTheme.colorScheme.tertiary, 0.22f)
    val spots = remember {
        listOf(
            // Kept in the outer few percent of the width, where no row puts text, and out of the top
            // ~16% — under the status bar and the glass top bar a mark showed only in pieces.
            // (A top-right spot was dropped: every page's header and first cards fill the top, so
            // any mark there sat half under a card and read as a glitch.)
            Spot(0.03f, 0.46f, 1, 48f, -14f, true),
            Spot(0.97f, 0.60f, 2, 48f, 24f, false),
            Spot(0.04f, 0.79f, 3, 52f, 8f, false),
            Spot(0.94f, 0.97f, 4, 56f, -10f, true)
        )
    }
    Canvas(modifier.fillMaxSize()) {
        val page = BorikenPage.glyph
        spots.forEachIndexed { i, spot ->
            val px = spot.sizeDp.dp.toPx()
            val glyph = if (i % 2 == 1) page else TainoGlyphs.all[spot.glyph % TainoGlyphs.all.size]
            val painter = painters.getValue(glyph)
            translate(size.width * spot.x - px / 2, size.height * spot.y - px / 2) {
                rotate(spot.rotation, pivot = Offset(px / 2, px / 2)) {
                    with(painter) {
                        draw(Size(px, px), colorFilter = ColorFilter.tint(if (spot.red) red else ink))
                    }
                }
            }
        }
    }
}

private class Spot(
    val x: Float, val y: Float, val glyph: Int,
    val sizeDp: Float, val rotation: Float, val red: Boolean
)

/** A small petroglyph above an empty-state message, shown only while Borikén is on. */
@Composable
fun TainoEmptyGlyph(modifier: Modifier = Modifier, glyph: ImageVector? = null) {
    if (!LocalBoriken.current) return
    // Solid, in the theme's blue: part of the empty state, not a watermark over it.
    Icon(
        imageVector = glyph ?: LocalBorikenPage.current,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(40.dp)
    )
}

/**
 * The unlock moment: the Sol de Jayuya turns and grows in, and the edition can be tried at once.
 * Shown again (harmlessly) if the version is tapped ten more times after unlocking.
 */
@Composable
fun BorikenUnlockDialog(onDismiss: () -> Unit, onTryIt: () -> Unit) {
    val scale = remember { Animatable(0.2f) }
    val turn = remember { Animatable(-120f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) { turn.animateTo(0f, tween(900, easing = FastOutSlowInEasing)) }
    BoriAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                TainoGlyphs.Sun,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .size(72.dp)
                    .graphicsLayer { scaleX = scale.value; scaleY = scale.value; rotationZ = turn.value }
            )
        },
        title = { Text("¡Wepa! Borikén unlocked", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "A celeste, red and white edition of Bori, with Taíno marks hidden throughout. " +
                        "Find it any time under Settings → Appearance → Theme Style.",
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = { Button(onClick = onTryIt) { Text("Try it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}

/** The current page's Taíno mark, provided by `MainActivity` from the route. */
val LocalBorikenPage = staticCompositionLocalOf { TainoGlyphs.Sun }

/** A small solid mark beside a page title, in the flag's red. Borikén only. */
@Composable
fun BorikenHeaderSymbol(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 22.dp) {
    if (!LocalBoriken.current) return
    Icon(
        LocalBorikenPage.current,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.size(size)
    )
}

/** A smaller solid mark for section headings, in the theme's blue. Borikén only. */
@Composable
fun BorikenSectionSymbol(modifier: Modifier = Modifier, glyph: ImageVector? = null) {
    if (!LocalBoriken.current) return
    Icon(
        glyph ?: LocalBorikenPage.current,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(18.dp)
    )
}

/**
 * A flat flag ribbon under a page's top bar in Material 3 and Expressive: red, white, red stripes
 * with the celeste triangle at the hoist. Not drawn under Crystal, whose whole field is the flag.
 */
@Composable
fun BorikenFlagRibbon(modifier: Modifier = Modifier) {
    if (!LocalBoriken.current || isCrystal()) return
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val red = if (dark) Color(0xFFD9404F) else BorikenRed
    val white = if (dark) Color(0xFFE8EEF4) else Color.White
    val celeste = BorikenCeleste
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val stripe = size.height / 3f
        drawRect(red, size = Size(size.width, stripe))
        drawRect(white, topLeft = Offset(0f, stripe), size = Size(size.width, stripe))
        drawRect(red, topLeft = Offset(0f, stripe * 2), size = Size(size.width, size.height - stripe * 2))
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(size.height * 2.2f, size.height / 2f); lineTo(0f, size.height); close()
        }
        drawPath(path, celeste)
    }
}

/**
 * Borikén's mark **built into a surface** in Material 3 and Expressive: a large carving at the right
 * end of a card or heading, in a tone just off that surface, cropped by the surface's own edge — part
 * of the card, the way a pressed pattern is part of paper. (A page-wide wallpaper pattern was tried
 * and dropped: on a flat page every mark landed on some row of text, or was cut off by a heading.)
 * Crystal keeps its marks in the field instead, so this draws nothing there.
 */
@Composable
fun Modifier.borikenSurfaceMark(
    glyph: ImageVector,
    /** Where the mark's centre sits across the surface, 0..1. Headings use the empty middle so the
     *  mark stays clear of the chevron; cards use their right end. */
    centreX: Float = 0.86f
): Modifier {
    if (!LocalBoriken.current || isCrystal()) return this
    val painter = rememberVectorPainter(glyph)
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    return this
        .clipToBounds()
        .drawBehind {
            val px = size.height * 1.5f
            translate(size.width * centreX - px / 2f, (size.height - px) / 2f) {
                rotate(-12f, pivot = Offset(px / 2, px / 2)) {
                    with(painter) { draw(Size(px, px), colorFilter = ColorFilter.tint(ink)) }
                }
            }
        }
}
