package com.bori.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Builds a [CrystalMesh.CUSTOM] field's five blob colours from one seed colour.
 *
 * **This is `scripts/gen_mesh_palette.py`'s `build()`, ported.** That script is the definition of the
 * five generated fields (Tide, Indigo, Orchid, Dune, Graphite) and now of Verdant too; this is the
 * same algorithm run at runtime so the user can supply the hue. Keep the two in step — if the
 * constants here and there ever disagree, the shipped fields and a custom one stop being the same
 * kind of object, and the safety argument below stops covering the custom one.
 *
 * ## Why a user cannot simply pick five colours
 *
 * Under `CrystalBackground.MESH` the surface roles are the neutral ones with alpha
 * (`withTranslucentSurfaces`), and **nothing lifts them to follow the field**. So a field whose tones
 * differ from Aurora's would change the separation between every card and its backdrop, on every
 * screen in the app, and would need the whole surface family re-derived. Every shipped field
 * therefore reuses **Aurora's per-slot tones exactly** and may only vary hue, plus chroma at or below
 * Aurora's per-slot cap. That is what makes separation, text contrast and the roles correct *by
 * construction* rather than by measurement — see `CrystalColor.kt` and
 * `scripts/verify_mesh_palettes.py`.
 *
 * A custom field is held to the same three rules, which is why the picker takes a colour and uses
 * only its **hue**: the tones are not the user's to choose.
 *
 * ## The third constraint is the one that bites
 *
 * Tone-matches-and-chroma-under-the-cap is necessary and **not sufficient**. Blobs composite in
 * *gamma-encoded* sRGB, so a colour at the right L\* with a channel jammed against the gamut wall
 * darkens the field more than a balanced colour of the same L\* does. Tide was built to the first two
 * rules exactly and still missed the composited tone by 1.3 L\*, twice the gate's tolerance. Chroma
 * yields until all three hold — that is what the bisection below is doing.
 */

// ── the definition, mirrored from gen_mesh_palette.py ────────────────────────────────────────────

/** ~0.075 × 255. A near-zero channel reads neon rather than as the colour asked for. */
private const val CHANNEL_FLOOR = 19.0

/** L\* units. The shipping fields run to 0.54 against the gate's 0.6 tolerance. */
private const val COMPOSITE_MARGIN = 0.50

/**
 * The band's shape, as offsets from the seed hue — the same sweep the generated fields use
 * (Tide `186 200 218 234 192` is `h, h+14, h+32, h+48, h+6`; Indigo and Orchid are `h, +12, +26,
 * +40, +6`). Averaged to one shape here so every custom field has the same character and only its
 * starting hue changes.
 */
private val HUE_OFFSETS = floatArrayOf(0f, 13f, 29f, 44f, 6f)

/** Blob alphas and centres, mirrored from `drawCrystalMeshField`. Order is the slot order. */
private val BLOB_ALPHA = floatArrayOf(0.85f, 0.85f, 0.64f, 0.85f, 0.85f)
private const val DILUTION_LIGHT = 0.68
private const val DILUTION_DARK = 1.0

// ── colour maths (CIELAB / LCh), same as verify_mesh_palettes.py ─────────────────────────────────

private fun s2l(c: Double): Double {
    val v = c / 255.0
    return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
}

private fun l2s(y: Double): Double {
    val s = if (y <= 0.0031308) 12.92 * y else 1.055 * Math.pow(y, 1 / 2.4) - 0.055
    return s * 255.0
}

/** Returns L\*, C\*, h° for an sRGB triple. */
private fun lab(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
    val rl = s2l(r); val gl = s2l(g); val bl = s2l(b)
    val x = 0.4124 * rl + 0.3576 * gl + 0.1805 * bl
    val y = 0.2126 * rl + 0.7152 * gl + 0.0722 * bl
    val z = 0.0193 * rl + 0.1192 * gl + 0.9505 * bl
    fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
    val fx = f(x / 0.95047); val fy = f(y); val fz = f(z / 1.08883)
    val a = 500 * (fx - fy)
    val bb = 200 * (fy - fz)
    return Triple(
        116 * fy - 16,
        hypot(a, bb),
        (Math.toDegrees(atan2(bb, a)) + 360.0) % 360.0
    )
}

/** LCh → sRGB. `second` is false when the result fell outside the gamut. */
private fun lchToRgb(l: Double, c: Double, h: Double): Pair<DoubleArray, Boolean> {
    val hr = Math.toRadians(h)
    val a = c * cos(hr)
    val b = c * sin(hr)
    val fy = (l + 16) / 116
    val fx = fy + a / 500
    val fz = fy - b / 200
    fun fi(t: Double) = if (t * t * t > 0.008856) t * t * t else (t - 16.0 / 116.0) / 7.787
    val x = fi(fx) * 0.95047; val y = fi(fy); val z = fi(fz) * 1.08883
    val vals = doubleArrayOf(
        l2s(3.2406 * x - 1.5372 * y - 0.4986 * z),
        l2s(-0.9689 * x + 1.8758 * y + 0.0415 * z),
        l2s(0.0557 * x - 0.2040 * y + 1.0570 * z)
    )
    return vals to vals.all { it >= -0.6 && it <= 255.6 }
}

private fun Color.rgbTriple(): DoubleArray {
    val argb = toArgb()
    return doubleArrayOf(
        ((argb shr 16) and 0xFF).toDouble(),
        ((argb shr 8) and 0xFF).toDouble(),
        (argb and 0xFF).toDouble()
    )
}

/** The field's tone where blob [i] is strongest — the number the separation argument rests on. */
private fun compositedTone(rgb: DoubleArray, i: Int, base: DoubleArray, dark: Boolean): Double {
    val a = BLOB_ALPHA[i] * (if (dark) DILUTION_DARK else DILUTION_LIGHT)
    return lab(
        base[0] * (1 - a) + rgb[0] * a,
        base[1] * (1 - a) + rgb[1] * a,
        base[2] * (1 - a) + rgb[2] * a
    ).first
}

/**
 * Solve the five stops for [seed]'s hue at [chromaScale] of Aurora's caps.
 *
 * Per slot: bisect chroma down from Aurora's cap until the colour is in gamut, clears the channel
 * floor, **and** composites to within [COMPOSITE_MARGIN] of Aurora's tone at that blob. The Python
 * original follows the bisection with a ±2 RGB lattice refinement to recover a little hue accuracy
 * lost to rounding; that is skipped here — it is 125 candidates × 5 slots × 2 modes for a fraction
 * of a degree, and this runs on the main thread when a theme resolves.
 */
private fun solve(seedHue: Double, chromaScale: Float, dark: Boolean): List<Color> {
    val aurora = if (dark) AmbientBlobsDark else AmbientBlobsLight
    val base = (if (dark) AmbientBaseDark else AmbientBaseLight).rgbTriple()
    return (0 until 5).map { i ->
        val ref = aurora[i].rgbTriple()
        val (targetL, capC, _) = lab(ref[0], ref[1], ref[2])
        val refComposite = compositedTone(ref, i, base, dark)
        val hue = (seedHue + HUE_OFFSETS[i]) % 360.0
        var lo = 0.0
        var hi = capC * chromaScale
        repeat(40) {
            val mid = (lo + hi) / 2
            val (vals, inGamut) = lchToRgb(targetL, mid, hue)
            val ok = inGamut &&
                vals.min() >= CHANNEL_FLOOR - 0.5 &&
                abs(compositedTone(vals, i, base, dark) - refComposite) <= COMPOSITE_MARGIN
            if (ok) lo = mid else hi = mid
        }
        val (vals, _) = lchToRgb(targetL, lo, hue)
        Color(
            red = (vals[0].roundToInt().coerceIn(0, 255)) / 255f,
            green = (vals[1].roundToInt().coerceIn(0, 255)) / 255f,
            blue = (vals[2].roundToInt().coerceIn(0, 255)) / 255f
        )
    }
}

/**
 * Memoised per `(hue, chromaScale, dark)`. The solve is ~400 iterations of cheap arithmetic and runs
 * once when a theme resolves rather than per frame, but `BoriTheme` re-reads it on every intensity
 * drag frame, so caching it keeps that path free.
 */
private val cache = HashMap<Triple<Int, Int, Boolean>, List<Color>>()

/**
 * The five blob colours for a custom field.
 *
 * [seed] is `null` until the user has picked one, in which case this returns **Aurora's own list** —
 * a custom field that has never been configured looks exactly like the default field rather than
 * black, and nothing downstream has to special-case an empty palette.
 */
fun crystalCustomBlobs(seed: Color?, chromaScale: Float, dark: Boolean): List<Color> {
    if (seed == null) return if (dark) AmbientBlobsDark else AmbientBlobsLight
    val rgb = seed.rgbTriple()
    val (_, seedChroma, seedHue) = lab(rgb[0], rgb[1], rgb[2])
    // A grey seed has no meaningful hue — `atan2` on two near-zero terms is noise. Fall back to
    // Aurora's own starting hue so the result is a quiet neutral field rather than a random one.
    val hue = if (seedChroma < 1.0) {
        val a = AmbientBlobsLight[0].rgbTriple()
        lab(a[0], a[1], a[2]).third
    } else seedHue
    val key = Triple(hue.roundToInt(), (chromaScale * 100).roundToInt(), dark)
    return cache.getOrPut(key) { solve(hue, chromaScale, dark) }
}

// ── multi-colour combos ───────────────────────────────────────────────────────────────────────────

/**
 * Which picked colour each of the five slots takes, for 2–4 colours. Slots are top-left, top-right,
 * centre, bottom-left, bottom-right, so these read top-to-bottom like a wallpaper gradient — with
 * three colours, the first runs across the top, the second through the middle, the third along the
 * bottom (which is how the Borikén combo lays the flag's celeste, white and red).
 */
private val COMBO_SLOTS = mapOf(
    2 to intArrayOf(0, 0, 1, 1, 1),
    3 to intArrayOf(0, 0, 1, 2, 2),
    4 to intArrayOf(0, 1, 2, 2, 3)
)

/**
 * One slot solved for one colour: the slot keeps Aurora's tone and chroma cap exactly as the
 * single-colour solve does — that is what keeps card separation and text contrast safe — and takes
 * the colour's **hue**, with chroma also capped at the colour's own chroma, so a white or grey pick
 * becomes a quiet near-neutral band rather than a hue forced onto it.
 */
private fun solveSlot(i: Int, colour: Color, chromaScale: Float, dark: Boolean, toneShift: Float = 0f): Color {
    val aurora = if (dark) AmbientBlobsDark else AmbientBlobsLight
    val base = (if (dark) AmbientBaseDark else AmbientBaseLight).rgbTriple()
    val auroraRef = aurora[i].rgbTriple()
    val (auroraL, capC, _) = lab(auroraRef[0], auroraRef[1], auroraRef[2])
    // A tone shift (daytime Borikén) pins the slot to a deeper tone itself, so the composite-tone
    // guard below, which holds a slot to Aurora's tone, is skipped for it; only gamut and the
    // channel floor still apply. Without a shift this is exactly the Aurora solve, as before.
    val targetL = auroraL + toneShift
    val refComposite = compositedTone(auroraRef, i, base, dark)
    val c = colour.rgbTriple()
    val (_, ownChroma, ownHue) = lab(c[0], c[1], c[2])
    var lo = 0.0
    var hi = minOf(capC * chromaScale, ownChroma)
    repeat(40) {
        val mid = (lo + hi) / 2
        val (vals, inGamut) = lchToRgb(targetL, mid, ownHue)
        val ok = inGamut &&
            vals.min() >= CHANNEL_FLOOR - 0.5 &&
            (toneShift != 0f || abs(compositedTone(vals, i, base, dark) - refComposite) <= COMPOSITE_MARGIN)
        if (ok) lo = mid else hi = mid
    }
    val (vals, _) = lchToRgb(targetL, lo, ownHue)
    return Color(
        red = (vals[0].roundToInt().coerceIn(0, 255)) / 255f,
        green = (vals[1].roundToInt().coerceIn(0, 255)) / 255f,
        blue = (vals[2].roundToInt().coerceIn(0, 255)) / 255f
    )
}

private val comboCache = HashMap<Triple<List<Int>, Int, Boolean>, List<Color>>()

/**
 * The five blob colours for a **combo** of 2–4 picked colours — the wallpaper-app style gradient.
 * One colour (or none) is exactly [crystalCustomBlobs], so existing custom fields are unchanged.
 */
fun crystalComboBlobs(
    colours: List<Color>,
    chromaScale: Float,
    dark: Boolean,
    /** Which colour each slot takes; null = the top-to-bottom layout in [COMBO_SLOTS]. */
    slots: IntArray? = null,
    /** Extra chroma per picked colour (same tone, so contrast is unchanged); null = 1 for all. */
    colourChroma: FloatArray? = null,
    /** Tone shift per picked colour, in L* (negative = deeper); null = none. */
    colourTone: FloatArray? = null
): List<Color> {
    if (colours.size <= 1) return crystalCustomBlobs(colours.firstOrNull(), chromaScale, dark)
    val picked = colours.take(4)
    val slotMap = slots ?: COMBO_SLOTS.getValue(picked.size)
    val key = Triple(picked.map { it.toArgb() } + slotMap.toList().map { -it - 1 } + (colourChroma?.map { (it * 100).roundToInt() } ?: emptyList()) + (colourTone?.map { (it * 100).roundToInt() + 100_000 } ?: emptyList()), (chromaScale * 100).roundToInt(), dark)
    return comboCache.getOrPut(key) {
        (0 until 5).map { i ->
            val boost = colourChroma?.getOrNull(slotMap[i]) ?: 1f
            val tone = colourTone?.getOrNull(slotMap[i]) ?: 0f
            solveSlot(i, picked[slotMap[i]], chromaScale * boost, dark, tone)
        }
    }
}

/** The Borikén combo: the light-blue flag's celeste, white and red. */
val BorikenCombo = listOf(Color(0xFF3EA6F2), Color(0xFFFFFFFF), Color(0xFFE4002B))

/**
 * Where the flag's colours go: celeste down the left and through the centre (the triangle, and the
 * centre slot is the strongest one, so the field reads celeste first), white at the top right and
 * red at the bottom right (the stripes). Slots: top-left, top-right, centre, bottom-left, bottom-right.
 */
val BorikenSlots = intArrayOf(0, 1, 0, 0, 2)

/**
 * How much more colour each Borikén colour gets than the mesh's usual cap: the red is pushed
 * (2026-10-13, "just a little bolder"), celeste and white stay as they were. Only chroma moves —
 * each slot keeps its tone, so cards and text read exactly as before.
 */
val BorikenChroma = floatArrayOf(1f, 1f, 1.6f)

/**
 * Daytime only: how much deeper each Borikén colour sits. At the light field's usual tone the
 * screen's most saturated red is a pale pink, so no chroma boost can make the corner read red in
 * daylight (2026-10-14: "a little more red hue during the day"). Night is untouched.
 */
/**
 * Daytime only: the soft white also takes the centre slot, so the middle of the screen stays light
 * (2026-10-14: full-strength celeste in the centre was "too dark in the middle"); celeste keeps the
 * left edge and red the bottom-right corner, as at night.
 */
val BorikenDaySlots = intArrayOf(0, 1, 1, 0, 2)

/**
 * Daytime only: tone shift per Borikén colour (celeste, white, red), in L*. Was -14/0/-22 (2026-10-14),
 * which made day far darker than plain Crystal. 2026-10-22, tuned on the Fold: celeste a little deeper,
 * the white lifted well above Aurora's tone so the middle is white rather than grey ("the middle is
 * still grayish"), and the red deep enough to read red rather than pink.
 */
val BorikenDayTone = floatArrayOf(-7f, 14f, -21f)

/**
 * Daytime only: the flag's colours with the white softened to a white with a hint of celeste, so the
 * top-right band lightens the field without bleaching it.
 */
val BorikenDayCombo = listOf(
    BorikenCombo[0],
    Color(0xFFE3F0FA),
    BorikenCombo[2]
)

/**
 * Daytime only: colour strength per Borikén colour (celeste, white, red), in place of [BorikenChroma].
 * Red raised 2.25 → 3.2 on 2026-10-22 ("can we up the red"); at this tone it is near the gamut edge,
 * so more red now means a larger red area, not a higher number here.
 */
val BorikenDayChroma = floatArrayOf(1.55f, 1f, 3.2f)

/** The Borikén field's five colours — the one call the field and its swatch share. */
fun borikenFieldBlobs(dark: Boolean): List<Color> =
    if (dark) crystalComboBlobs(BorikenCombo, 1f, true, BorikenSlots, BorikenChroma)
    else crystalComboBlobs(BorikenDayCombo, 1f, false, BorikenDaySlots, BorikenDayChroma, BorikenDayTone)
