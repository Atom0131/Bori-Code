package com.bori.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.bori.theme.settings.CrystalBackground
import com.bori.theme.settings.CrystalMesh
import com.bori.theme.settings.CrystalStyle
import com.bori.theme.settings.DEFAULT_CRYSTAL_INTENSITY
import com.bori.theme.settings.DEFAULT_CRYSTAL_MESH_CUSTOM_CHROMA
import com.bori.theme.settings.CRYSTAL_BG_NEUTRAL
import com.bori.theme.settings.ThemeStyle
import com.bori.theme.util.resolveDisplayColor
import com.materialkolor.rememberDynamicMaterialThemeState
import com.materialkolor.dynamicColorScheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.math.sign

/** The active theme style, so any composable can branch without it being threaded through every
 * call. Defaults to [ThemeStyle.MATERIAL3] — the same default the preference has, so a composable
 * previewed outside [BoriTheme] renders as today's app rather than as glass. */
val LocalThemeStyle = staticCompositionLocalOf { ThemeStyle.MATERIAL3 }

/** The active Crystal panel intensity. Only read when [LocalThemeStyle] is [ThemeStyle.CRYSTAL]. */
val LocalCrystalStyle = staticCompositionLocalOf { CrystalStyle.TINTED }

/**
 * Where the Glass Intensity slider sits within the active preset's band, `0f..1f`.
 *
 * **`0.5f` is the midpoint of every band and is exactly the look that shipped before the slider
 * existed**, which is what lets the default be a no-op for anyone updating. Sheer and Tinted keep
 * their own characters and each gets a range around its own numbers, so the two stay distinguishable
 * at any slider position rather than collapsing into one scale.
 */
val LocalCrystalIntensity = staticCompositionLocalOf { DEFAULT_CRYSTAL_INTENSITY }

/**
 * Whether Crystal draws its five-colour mesh or one flat cool neutral behind the panels.
 *
 * Independent of [LocalCrystalStyle] and of the intensity, so all four combinations exist. Note the
 * kit skips Haze entirely under [CrystalBackground.SOLID]: blurring a flat colour returns that flat
 * colour, so the blur is pure cost — which also takes it out of the scroll and slider paths.
 */
val LocalCrystalBackground = staticCompositionLocalOf { CrystalBackground.MESH }

/**
 * Which of the four fields the mesh draws. Read only by the field itself — the four share their
 * tones, so no surface role depends on the choice and the scheme never has to be rebuilt for it.
 */
val LocalCrystalMesh = staticCompositionLocalOf { CrystalMesh.AURORA }

/**
 * The seed a [CrystalMesh.CUSTOM] field is solved from, and how much of Aurora's chroma it takes.
 *
 * Only these two are needed because **only the hue is the user's** — see `CrystalCustomMesh.kt` for
 * why the tones are not. `null` means the field has never been configured, and the solver returns
 * Aurora's own list in that case. Read exactly where `LocalCrystalMesh` is read.
 */
val LocalCrystalCustomSeed = staticCompositionLocalOf<Color?> { null }

/** A multi-colour custom field (2–4 colours); empty means the single-colour [LocalCrystalCustomSeed]. */
val LocalCrystalCustomColours = staticCompositionLocalOf<List<Color>> { emptyList() }
val LocalCrystalCustomChroma = staticCompositionLocalOf { DEFAULT_CRYSTAL_MESH_CUSTOM_CHROMA }

/**
 * The seed colour the whole app's palette is built from.
 *
 * Normally the user's chosen accent. **Under Crystal with a colour field, and only while the user
 * has never chosen an accent at all, the field supplies it instead** — so a fresh install looks
 * deliberate the moment a field is picked rather than putting a violet accent over a green room.
 *
 * The important part is that this is a *read*, not a write. An earlier version had the field picker
 * call `setAccentColor`, which worked but changed the single global accent preference — so choosing
 * a Crystal field also repainted Material 3 and Expressive, which is not what anyone wants from a
 * Crystal setting. Deriving it here means nothing is ever overwritten: the moment the user picks any
 * accent, `accentIsDefault` goes false and the field stops having an opinion, permanently and
 * without needing to be undone.
 *
 * Both windows must call this rather than resolving the accent themselves — `MainActivity` and
 * `ThemedRoot` computing a seed two different ways is exactly the drift this exists to prevent.
 */
fun accentSeed(
    themeStyle: ThemeStyle,
    crystalBackground: CrystalBackground,
    crystalMesh: CrystalMesh,
    accentColorIndex: Int,
    accentColorArgb: Int?,
    accentIsDefault: Boolean
): Color = if (
    themeStyle == ThemeStyle.CRYSTAL &&
    crystalBackground == CrystalBackground.MESH &&
    accentIsDefault
) {
    // Solid is excluded on purpose: there its room colour picker is the colour decision, and a
    // mesh field's accent has nothing to do with the room the user chose.
    resolveDisplayColor(crystalMeshAccent(crystalMesh), null)
} else {
    resolveDisplayColor(accentColorIndex.coerceAtLeast(0), accentColorArgb)
}

/** The chosen Crystal room colour, or `null` for the cool neutral. See [crystalRoomColor]. */
val LocalCrystalRoom = staticCompositionLocalOf<Color?> { null }

// ── Crystal palette assembly ─────────────────────────────────────────────────────────────────────
//
// Crystal's *neutrals* are fixed (see CrystalColor.kt) and its *accent* comes from the user's chosen
// colour, derived here at Material 3's tonal positions via HCT. Doing the tone maths directly rather
// than asking materialkolor for a scheme is deliberate, for the same reason BoriTheme pins
// ColorSpec.SPEC_2021 elsewhere in this file: a library default flipping must never silently
// restyle the app. Crystal's palette is now stated outright, in one place, in tone numbers.

/** A floor on chroma so a near-grey accent still produces a visible accent rather than another grey
 * — the surfaces around it are already neutral, so there is nothing left to distinguish it. */
private const val MIN_ACCENT_CHROMA = 32.0

private fun Color.atTone(tone: Double, minChroma: Double = MIN_ACCENT_CHROMA): Color {
    val hct = Hct.fromInt(toArgb())
    return Color(Hct.from(hct.hue, hct.chroma.coerceAtLeast(minChroma), tone).toInt())
}

// **There is deliberately no seed chroma floor here, and one was written and then removed.**
// `atLeastChroma` lived at this spot for a day, on the theory that Material 3 and Expressive read the
// seed's own chroma and so a pastel preset would produce a pale palette. Measured on the JVM, that is
// false for the styles this app actually uses: `SchemeVibrant`/`SchemeExpressive` build their primary
// palette as `TonalPalette.fromHueAndChroma(seed.hue, <hardcoded>)` and never look at the seed's
// chroma at all — seeds of C* 5, 13, 30, 48 and 80 at one hue all resolved to the same `primary`.
// Only Crystal needs a floor, and it has one, applied per accent role in `atTone`.

/**
 * The sibling of [atTone] for **rooms and the surfaces in them**: it *caps* chroma where [atTone]
 * floors it.
 *
 * The two want opposite things and that is the whole reason this exists. An accent has to stay
 * saturated enough to read as a colour against neutral glass, so [atTone] pulls a washed-out seed
 * up. A background has to stay quiet enough to sit behind everything for hours, so a saturated seed
 * gets pulled down. Handing the palette's accent colours to [atTone] would produce a room nobody
 * could work in.
 */
private fun Color.asRoomTone(tone: Double, maxChroma: Double): Color {
    val hct = Hct.fromInt(toArgb())
    return Color(Hct.from(hct.hue, hct.chroma.coerceAtMost(maxChroma), tone).toInt())
}

// ── The Crystal room ─────────────────────────────────────────────────────────────────────────────
//
// Under Material 3 and Expressive the accent colours the app's *elements*. Under Crystal the elements
// are glass, so a colour has nowhere to land — which is why a solid Crystal in dark mode came out as
// simply a black app with nothing for the glass to reveal. The room is what should be coloured,
// because the room is what the glass is revealing.
//
// Tones below are not taste, they are a budget. A Crystal panel *is* its backdrop tinted with a
// surface colour, so on a flat field the only separation between a card and the room is the distance
// between those two tones — and the worst case is Sheer at 0% intensity, where the fill is barely a
// quarter opaque. The neutral room ships a 5.1-tone gap in light and 7.3 in dark; these reproduce it.

/** Light rooms sit at 88 rather than the neutral's 91: deep enough to hold a visible hue, and it
 * widens the worst-case card separation from 1.5 tones to 2.3 while it is there. */
private const val ROOM_TONE_LIGHT = 88.0
private const val ROOM_CHROMA_LIGHT = 16.0

/**
 * Dark rooms sit at **11**, not at the neutral's 3.
 *
 * This is the crux of the whole feature. A room dark enough to match the neutral shows no colour at
 * all — which was the original complaint — but a room at tone 11 is the *card's* own tone, so the
 * cards would vanish into it. Neither number can move alone, so the dark surfaces move with the
 * room: see [crystalDark]'s tinted branch, which re-derives the whole family about eight tones up
 * and restores the 7-tone gap. Text stays at 12.6:1 on the lifted card, comfortably AAA.
 */
private const val ROOM_TONE_DARK = 11.0
private const val ROOM_CHROMA_DARK = 20.0

/**
 * The room colour, or `null` for the cool neutral — so every caller has one obvious
 * "nothing changes" branch, and the default path cannot drift from what is already on the device.
 *
 * **AMOLED wins.** The toggle promises true black for OLED battery and has to mean it; the chosen
 * colour comes back the moment it is switched off.
 */
fun crystalRoomColor(
    index: Int,
    argb: Int?,
    dark: Boolean,
    amoled: Boolean
): Color? {
    if (index == CRYSTAL_BG_NEUTRAL && argb == null) return null
    if (dark && amoled) return Color.Black
    val picked = resolveDisplayColor(index.coerceAtLeast(0), argb)
    return if (dark) picked.asRoomTone(ROOM_TONE_DARK, ROOM_CHROMA_DARK)
           else picked.asRoomTone(ROOM_TONE_LIGHT, ROOM_CHROMA_LIGHT)
}

/**
 * Crystal, light. Cool near-white surfaces stepping up in tight increments — the steps are small on
 * purpose, because a translucent panel already separates itself from the mesh by its rim and tint,
 * and a strong tonal jump underneath it fights that.
 *
 * **Every `surfaceContainer*` role is set explicitly.** `lightColorScheme()` fills anything omitted
 * from Material's *baseline purple* palette, and this app reads `surfaceContainer`,
 * `surfaceContainerLow/High/Highest` in 80-odd places — leaving them defaulted puts lilac-grey cards
 * on a cool blue mesh, which reads as a bug rather than as a theme.
 */
/**
 * The surface family for a **tinted** room: the same hue at low chroma, at the tones listed.
 *
 * A neutral grey card in a deep teal room reads as a mistake — Material's own "neutral" palette is
 * seeded for exactly this reason. Tinting the surfaces is therefore not decoration; it is what makes
 * the room look lit rather than filtered.
 */
private data class RoomSurfaces(
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceBright: Color,
    val surfaceDim: Color,
    val containerLowest: Color,
    val containerLow: Color,
    val container: Color,
    val containerHigh: Color,
    val containerHighest: Color
)

private const val SURFACE_CHROMA = 6.0

private fun roomSurfaces(room: Color, dark: Boolean): RoomSurfaces = if (dark) {
    // Lifted about eight tones above the neutral dark family, to keep the 7-tone room→card gap once
    // the room itself has risen to 11. See ROOM_TONE_DARK.
    RoomSurfaces(
        surface = room.asRoomTone(18.0, SURFACE_CHROMA),
        surfaceVariant = room.asRoomTone(26.0, SURFACE_CHROMA),
        surfaceBright = room.asRoomTone(31.0, SURFACE_CHROMA),
        surfaceDim = room.asRoomTone(11.0, SURFACE_CHROMA),
        containerLowest = room.asRoomTone(10.0, SURFACE_CHROMA),
        containerLow = room.asRoomTone(15.0, SURFACE_CHROMA),
        container = room.asRoomTone(18.0, SURFACE_CHROMA),
        containerHigh = room.asRoomTone(23.0, SURFACE_CHROMA),
        containerHighest = room.asRoomTone(28.0, SURFACE_CHROMA)
    )
} else {
    // Light keeps the neutral family's tones exactly and only gains hue — the gap there already
    // works, so there is nothing to buy by moving them.
    RoomSurfaces(
        surface = room.asRoomTone(100.0, SURFACE_CHROMA),
        surfaceVariant = room.asRoomTone(93.0, SURFACE_CHROMA),
        surfaceBright = room.asRoomTone(100.0, SURFACE_CHROMA),
        surfaceDim = room.asRoomTone(86.0, SURFACE_CHROMA),
        containerLowest = room.asRoomTone(100.0, SURFACE_CHROMA),
        containerLow = room.asRoomTone(98.5, SURFACE_CHROMA),
        container = room.asRoomTone(96.0, SURFACE_CHROMA),
        containerHigh = room.asRoomTone(94.0, SURFACE_CHROMA),
        containerHighest = room.asRoomTone(92.0, SURFACE_CHROMA)
    )
}

/**
 * Repaints a Crystal scheme's room and surfaces in the chosen colour.
 *
 * Deliberately a `copy()` on top of the finished neutral scheme rather than a parameter threaded
 * through [crystalLight] / [crystalDark]. **When no colour is chosen this function is never called**,
 * so the default look cannot drift from what is already on the device — that is a guarantee by
 * construction, not something to re-check after every edit. Accent roles are untouched: the accent
 * picker still owns the elements.
 */
private fun ColorScheme.withRoomSurfaces(room: Color, dark: Boolean): ColorScheme {
    val s = roomSurfaces(room, dark)
    return copy(
        background = room,
        surface = s.surface,
        surfaceVariant = s.surfaceVariant,
        surfaceBright = s.surfaceBright,
        surfaceDim = s.surfaceDim,
        surfaceContainerLowest = s.containerLowest,
        surfaceContainerLow = s.containerLow,
        surfaceContainer = s.container,
        surfaceContainerHigh = s.containerHigh,
        surfaceContainerHighest = s.containerHighest
    )
}

private fun crystalLight(seed: Color): ColorScheme = lightColorScheme(
    primary = seed.atTone(48.0),
    onPrimary = Color.White,
    primaryContainer = seed.atTone(90.0),
    onPrimaryContainer = seed.atTone(20.0),
    inversePrimary = seed.atTone(80.0),
    secondary = SlateSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = seed.atTone(93.0, minChroma = 8.0),
    onSecondaryContainer = seed.atTone(24.0, minChroma = 8.0),
    // Tertiary is the accent again rather than a third hue: Crystal's whole point is one accent
    // against neutral glass, and a third colour turns it into an ordinary Material palette.
    tertiary = seed.atTone(48.0),
    onTertiary = Color.White,
    tertiaryContainer = seed.atTone(90.0),
    onTertiaryContainer = seed.atTone(20.0),
    background = CoolWhite,
    onBackground = InkLight,
    surface = CoolSurfaceLight,
    onSurface = InkLight,
    surfaceVariant = CoolSurfaceVariantLight,
    onSurfaceVariant = InkMutedLight,
    surfaceTint = seed.atTone(48.0),
    surfaceDim = Color(0xFFDDDFE4),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFECEEF2),
    surfaceContainerHighest = Color(0xFFE5E8ED),
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F2F6)
)

/**
 * Crystal, dark. Note `tertiary` sits two tonal steps *above* `primary` and that is load-bearing:
 * the fill blue that looks right on a solid button is too dark to read as text or an icon on a
 * translucent panel lit by the mesh behind it. Solid fills use `primary`; accent text and icons use
 * `tertiary`. Losing that split is how dark Crystal ends up with unreadable links.
 */
private fun crystalDark(seed: Color, amoled: Boolean): ColorScheme = darkColorScheme(
    primary = seed.atTone(58.0),
    onPrimary = Color.White,
    primaryContainer = seed.atTone(32.0),
    onPrimaryContainer = seed.atTone(92.0),
    inversePrimary = seed.atTone(45.0),
    secondary = SlateSecondaryDark,
    onSecondary = InkBackgroundDark,
    secondaryContainer = seed.atTone(28.0, minChroma = 8.0),
    onSecondaryContainer = seed.atTone(90.0, minChroma = 8.0),
    tertiary = seed.atTone(74.0),
    onTertiary = seed.atTone(18.0),
    tertiaryContainer = seed.atTone(32.0),
    onTertiaryContainer = seed.atTone(92.0),
    background = if (amoled) Color.Black else InkBackgroundDark,
    onBackground = InkDark,
    surface = CoolSurfaceDark,
    onSurface = InkDark,
    surfaceVariant = CoolSurfaceVariantDark,
    onSurfaceVariant = InkMutedDark,
    surfaceTint = seed.atTone(58.0),
    surfaceDim = if (amoled) Color.Black else InkBackgroundDark,
    surfaceBright = Color(0xFF38393C),
    surfaceContainerLowest = if (amoled) Color.Black else Color(0xFF060607),
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF26262A),
    surfaceContainerHighest = Color(0xFF313135),
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = Color(0xFFE4E5EA),
    inverseOnSurface = Color(0xFF2F3033)
)

// ── Expressive hue containment ───────────────────────────────────────────────────────────────────
//
// `PaletteStyle.Expressive` is multi-hue by design: it derives secondary and tertiary by rotating a
// long way around the hue wheel from the seed. That is the point of it, and on Google's own default
// purple it looks considered. On a *chosen* accent it does not — a green accent put an off-orange
// pill under the selected navigation tab, which reads as a bug rather than as a palette, and the
// same role turns up in eleven other places (habit-detail chips, calendar rows, widget config
// selections).
//
// So the drift is bounded rather than removed: secondary and tertiary still move, enough to be
// distinguishable and to give Expressive its livelier feel, but never far enough to leave the
// accent's family. **Hue only — tone and chroma are preserved**, which is what keeps every
// `on*`/container contrast pairing exactly as Material computed it.

/** Maximum hue distance, in degrees, any accent role may sit from the seed. */
private const val EXPRESSIVE_MAX_HUE_DRIFT = 30.0

/** Below this chroma a colour is effectively grey, and its hue is meaningless to rotate. */
private const val HUE_MEANINGFUL_CHROMA = 5.0

/** Shortest signed distance from [from] to [to] around the wheel, in (-180, 180]. */
private fun hueDelta(from: Double, to: Double): Double {
    var d = (to - from) % 360.0
    if (d > 180.0) d -= 360.0
    if (d <= -180.0) d += 360.0
    return d
}

private fun Color.clampHueTo(seedHue: Double): Color {
    val hct = Hct.fromInt(toArgb())
    if (hct.chroma < HUE_MEANINGFUL_CHROMA) return this
    val delta = hueDelta(seedHue, hct.hue)
    if (abs(delta) <= EXPRESSIVE_MAX_HUE_DRIFT) return this
    val pulled = seedHue + EXPRESSIVE_MAX_HUE_DRIFT * sign(delta)
    return Color(Hct.from((pulled % 360.0 + 360.0) % 360.0, hct.chroma, hct.tone).toInt())
}

/**
 * Pulls the **secondary** roles back toward the seed's hue. Surfaces, outlines and the error family
 * are left alone: they are near-neutral (so the clamp would be a no-op) or carry meaning of their own
 * that has nothing to do with the accent.
 *
 * **Tertiary is deliberately no longer clamped, and the reason is measured.** Read out of
 * `ColorSpec2025.getSecondaryPalette` / `getTertiaryPalette` (material-color-utilities 5.0.0,
 * `javap`), the two styles derive these by rotating the seed hue through a breakpoint table, and the
 * tables are nothing like each other:
 *
 * | | secondary rotations | tertiary rotations |
 * |---|---|---|
 * | `Expressive` | −160, +155, −100, +96, −96, −156, −165, −160 | −165, +160, −105, +101, −101, −160, −170, −165 |
 * | `Vibrant` | **−14, +10, −14, +10, −14** | −72, +35, +24, −24, +62, +50, +62, −72 |
 *
 * That table is the whole story of this function. Under `Expressive` the clamp was doing enormous
 * work — squashing ±160° down to 30° — which is why the palette read as monochromatic *and* why it
 * was needed at all: the fault it was written for was **a green accent putting an off-orange pill
 * under the selected nav tab**, and that pill is `secondaryContainer`.
 *
 * Under `Vibrant`, secondary rotates by **at most 14°, already inside the 30° limit**, so the
 * secondary clamp is a **no-op by construction** — the reported fault cannot recur no matter what
 * this function does. It is kept anyway, as free insurance if the style is ever changed back.
 *
 * Tertiary is the opposite case: it rotates up to 72°, so the clamp *was* biting, and it was
 * discarding exactly the complementary-hue variety that makes Vibrant vibrant. It reaches only
 * `tertiaryContainer`, at two decorative call sites (the swipe-to-dismiss background and the note
 * editor's highlight), where a contrasting hue is the point and no selection state is being named.
 * Released 2026-08-25.
 *
 * `inversePrimary` comes from the primary palette, so its hue is the seed's and the clamp is a no-op
 * there too; it stays for symmetry.
 */
private fun ColorScheme.withContainedHues(seed: Color): ColorScheme {
    val seedHue = Hct.fromInt(seed.toArgb()).hue
    return copy(
        secondary = secondary.clampHueTo(seedHue),
        onSecondary = onSecondary.clampHueTo(seedHue),
        secondaryContainer = secondaryContainer.clampHueTo(seedHue),
        onSecondaryContainer = onSecondaryContainer.clampHueTo(seedHue),
        inversePrimary = inversePrimary.clampHueTo(seedHue)
    )
}

/**
 * How opaque a Crystal surface role is, at position [intensity] in the active preset's band.
 *
 * These are the same numbers the panel kit uses for its no-blur fill, so a translucent role and a
 * blurred panel sit at the same weight on screen — change one band here and the matching one in
 * `Crystal.kt` moves with it, or cards and untouched `Surface()` calls start to disagree.
 *
 * Each band's midpoint is the value that shipped before the slider: 0.42 / 0.50 / 0.68 / 0.74.
 */
internal fun crystalSurfaceAlpha(style: CrystalStyle, dark: Boolean, intensity: Float): Float {
    val sheer = style == CrystalStyle.SHEER
    val low = when {
        sheer && dark -> 0.34f
        sheer         -> 0.28f
        dark          -> 0.58f
        else          -> 0.52f
    }
    val high = when {
        sheer && dark -> 0.66f
        sheer         -> 0.56f
        dark          -> 0.90f
        else          -> 0.84f
    }
    return low + (high - low) * intensity.coerceIn(0f, 1f)
}

/**
 * The same thing, for surfaces **inside a window that paints its own field** — a bottom sheet or a
 * modal dialog. Denser than the screen band at every slider position.
 *
 * On a screen, a control is translucent *over the ambient field*, and the field is a mesh with
 * enough variation that a lightly-tinted panel still separates from it. In a sheet the backdrop is
 * that sheet's own smooth gradient, and a control tinted for the screen simply dissolves into it —
 * which is what made the timer and journal pickers read as ghosts. Same slider, denser band.
 */
internal fun crystalWindowSurfaceAlpha(style: CrystalStyle, dark: Boolean, intensity: Float): Float {
    val sheer = style == CrystalStyle.SHEER
    val low = when {
        sheer && dark -> 0.56f
        sheer         -> 0.52f
        dark          -> 0.70f
        else          -> 0.66f
    }
    val high = when {
        sheer && dark -> 0.92f
        sheer         -> 0.88f
        dark          -> 1.00f
        else          -> 0.98f
    }
    return low + (high - low) * intensity.coerceIn(0f, 1f)
}

/**
 * How much surface colour is laid over the room on a **[CrystalBackground.SOLID]** field.
 *
 * Solid is not a variant of the two bands above; it is a different problem, and reusing
 * [crystalSurfaceAlpha] for it was a real bug — panels came out as near-black slabs on a coloured
 * room, reported as *"the boxes look black instead of looking through the back of it"*.
 *
 * **Why the other band is wrong here.** With a blur, a panel is `crystalHazeStyle`'s *tint* (0.26 at
 * dark/Tinted/50%) over an opaque blurred snapshot of the field. Without one it falls to
 * [crystalSurfaceAlpha] (0.74 at the same setting) — a band tuned to *compensate* for missing
 * frosted glass on API < 32. Solid skips Haze deliberately, on the sound argument that blurring a
 * flat colour returns that flat colour; but that identity holds for the **blur**, not for the
 * **opacity**, and the fallback band came along with it. The proportions ended up inverted: 74%
 * surface over 26% room, where the whole point of a room colour is that the room reads through.
 *
 * **Why it cannot simply be lowered.** On a flat field a panel's only separation from the room is
 * its own opacity — there is no blurred texture doing the work — and a blend can never exceed the
 * tone of what it blends toward. With the dark room at tone 11 and `surfaceContainer` at 18, the
 * largest reachable gap is 7 and it needs alpha 1.0. Lowering alpha alone would have dissolved the
 * cards into the room, which is round four's finding restated. So the tint drops **and** the roles
 * lift together, exactly as [ROOM_TONE_DARK] and [roomSurfaces] had to move together — see
 * [SOLID_SURFACE_LIFT_DARK].
 *
 * **Dark and light are not symmetric, and that is not an oversight.** Dark has headroom: a card can
 * rise from tone 11 to 28 without approaching white. Light has almost none — the room is already at
 * 88–91 and a card at 96 has nowhere to go. That asymmetry is also why the bug was invisible in
 * light: when the room and the card are within five tones, which of them dominates the blend barely
 * shows, so the wrong alpha still produced a nearly right answer. Light therefore gets a small
 * correction and dark a large one.
 *
 * Verified by `scripts/verify_crystal_solid_gate.py`, which computes the composited tone gap and the
 * room's share for every mode × preset × intensity. **Re-run it after touching these numbers.**
 */
internal fun crystalSolidTint(style: CrystalStyle, dark: Boolean, intensity: Float): Float {
    val sheer = style == CrystalStyle.SHEER
    val low = when {
        sheer && dark -> 0.30f
        sheer         -> 0.26f
        dark          -> 0.42f
        else          -> 0.46f
    }
    val high = when {
        sheer && dark -> 0.52f
        sheer         -> 0.52f
        dark          -> 0.68f
        else          -> 0.78f
    }
    return low + (high - low) * intensity.coerceIn(0f, 1f)
}

/**
 * How far the surface roles lift on a Solid field, in HCT tones.
 *
 * This is the other half of [crystalSolidTint] and cannot be read without it: the tint drops so the
 * room shows through, and the roles rise so the card still separates from it. Applied uniformly
 * across all nine roles, so their order relative to one another is exactly preserved — only their
 * distance from the room changes.
 *
 * Dark lifts ten tones. Light lifts 2.4, which is all the headroom there is before a card is simply
 * white.
 *
 * A welcome side effect on **AMOLED**: the room there is true black, and the neutral dark roles
 * include two that are also true black, so a Solid AMOLED screen used to be black cards on a black
 * room. The lift gives them somewhere to be seen, while `background` — the thing AMOLED actually
 * promises — is never touched.
 */
private const val SOLID_SURFACE_LIFT_DARK = 10.0
private const val SOLID_SURFACE_LIFT_LIGHT = 2.4

/** Raises a colour's tone, keeping its hue and chroma. The sibling of [atTone] and [asRoomTone],
 * which both *set* a tone; this one moves relative to whatever the role already is, which is what
 * lets one constant lift a whole family without restating any of its nine tones. */
private fun Color.liftedBy(tones: Double): Color {
    val hct = Hct.fromInt(toArgb())
    return Color(Hct.from(hct.hue, hct.chroma, (hct.tone + tones).coerceIn(0.0, 100.0)).toInt())
}

/**
 * Puts alpha on every surface role, so the ambient field shows through the app's existing surfaces
 * without a single call site being edited.
 *
 * This is what carries the long tail of Rhythm into Crystal. Around seventy `Surface(color =
 * MaterialTheme.colorScheme.surfaceContainerHigh)` calls are spread across thirty files — text
 * fields, picker tiles, sheet rows, chips — and rewriting each into a glass panel by hand would mean
 * seventy chances to drop a ripple, a click role, or a border. Instead the *colour they already ask
 * for* becomes translucent, and they all reveal the field correctly with no edit at all. They get no
 * backdrop blur, which is exactly what every device below API 32 gets everywhere; the surfaces that
 * carry the look — cards, bars, the panels you actually notice — go through the kit and do blur.
 *
 * **`background` is deliberately left opaque.** It is the bottom of the stack, and a translucent one
 * has nothing behind it to reveal but black.
 */
internal fun ColorScheme.withTranslucentSurfaces(alpha: Float): ColorScheme = copy(
    surface = surface.copy(alpha = alpha),
    surfaceVariant = surfaceVariant.copy(alpha = alpha),
    surfaceBright = surfaceBright.copy(alpha = alpha),
    surfaceDim = surfaceDim.copy(alpha = alpha),
    surfaceContainerLowest = surfaceContainerLowest.copy(alpha = alpha),
    surfaceContainerLow = surfaceContainerLow.copy(alpha = alpha),
    surfaceContainer = surfaceContainer.copy(alpha = alpha),
    surfaceContainerHigh = surfaceContainerHigh.copy(alpha = alpha),
    surfaceContainerHighest = surfaceContainerHighest.copy(alpha = alpha)
)

/**
 * The **Solid** counterpart of [withTranslucentSurfaces]: every surface role becomes the *opaque*
 * colour a glass panel over the flat field actually resolves to, rather than a translucent colour
 * left to be composited at draw time.
 *
 * This is possible only under Solid, and it is possible *because* the field is one flat known
 * colour — so the answer can simply be computed. Under the mesh there is nothing to pre-composite
 * against, which is why that path keeps its alpha.
 *
 * Three things fall out of doing it this way, and the third is the one worth keeping in mind:
 *
 * 1. The room reads through the glass, because the room really is most of the resulting colour.
 * 2. Separation survives the lower tint, via [SOLID_SURFACE_LIFT_DARK].
 * 3. **Every panel is opaque, so nothing can ghost through anything.** That is what retires
 *    `stickyFallbackBoost` on this path — a sticky header no longer needs to be a special, denser
 *    colour to stop rows showing through it, so it stops being visibly greyer than the cards
 *    around it. That mismatch was real and measurable on a device: 17.7 against 16.3 tones.
 *
 * The blend is a plain component mix in sRGB space, which is exactly what the canvas does when it
 * draws a translucent fill with SrcOver — deliberately *not* `lerp(Color, Color, Float)`, which
 * interpolates in a perceptual space and would land somewhere else.
 */
internal fun ColorScheme.withSolidGlassSurfaces(
    field: Color,
    lift: Double,
    tint: Float
): ColorScheme {
    fun glass(role: Color): Color {
        val over = role.liftedBy(lift)
        return Color(
            red = field.red * (1f - tint) + over.red * tint,
            green = field.green * (1f - tint) + over.green * tint,
            blue = field.blue * (1f - tint) + over.blue * tint,
            alpha = 1f
        )
    }
    return copy(
        surface = glass(surface),
        surfaceVariant = glass(surfaceVariant),
        surfaceBright = glass(surfaceBright),
        surfaceDim = glass(surfaceDim),
        surfaceContainerLowest = glass(surfaceContainerLowest),
        surfaceContainerLow = glass(surfaceContainerLow),
        surfaceContainer = glass(surfaceContainer),
        surfaceContainerHigh = glass(surfaceContainerHigh),
        surfaceContainerHighest = glass(surfaceContainerHighest)
    )
}

/**
 * The app's theme, in three styles.
 *
 * [ThemeStyle.MATERIAL3] is Rhythm's original look and its branch is unchanged — same palette
 * engine, same spec version, same type and shapes. It stays the default so an update changes
 * nothing for anyone who hasn't gone looking.
 *
 * All three styles read the user's accent colour from [seedColor], including Crystal. [isAmoled] and
 * [darkContrastLevel] are materialkolor parameters and so apply only to the two Material styles —
 * except that Crystal honours AMOLED by blacking out its darkest surfaces by hand, because "true
 * black on OLED" is a battery preference, not a palette one, and users will try it.
 */
@Composable
fun BoriTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isAmoled: Boolean = false,
    seedColor: Color = Color(0xFF6750A4),
    themeStyle: ThemeStyle = ThemeStyle.MATERIAL3,
    crystalStyle: CrystalStyle = CrystalStyle.TINTED,
    crystalIntensity: Float = DEFAULT_CRYSTAL_INTENSITY,
    crystalBackground: CrystalBackground = CrystalBackground.MESH,
    crystalMesh: CrystalMesh = CrystalMesh.AURORA,
    /** Seed and chroma for [CrystalMesh.CUSTOM]; ignored by the other ten. */
    crystalMeshCustomSeed: Color? = null,
    crystalMeshCustomChroma: Float = DEFAULT_CRYSTAL_MESH_CUSTOM_CHROMA,
    /** A 2–4 colour custom combo; empty uses [crystalMeshCustomSeed] alone. */
    crystalMeshCustomColours: List<Color> = emptyList(),
    crystalRoom: Color? = null,
    // Only applied in dark mode — lifts surface container tones so boxes and text
    // are more visible without changing the light-mode feel.
    darkContrastLevel: Double = 0.0,
    /** The hidden Borikén edition (unlocked from the version number); see [BorikenCeleste]. */
    boriken: Boolean = false,
    /** Borikén is still supplying the background (the user has not picked their own since). */
    borikenField: Boolean = true,
    /** Borikén is still supplying the accent (the user has not picked their own since). */
    borikenAccent: Boolean = true,
    content: @Composable () -> Unit
) {
    // **Borikén is values, not a fourth style.** It replaces the seed, the Crystal field and the red
    // roles, and leaves every one of the user's own settings in place underneath — turning it off
    // returns to exactly the look they had. The Crystal field is the existing Custom solver fed the
    // flag's celeste, so it sits at Aurora's tones and under its chroma caps like every shipped field.
    // The colours are Borikén's only until the user picks their own: each default has its own flag,
    // cleared by the matching picker, so the symbols and the flag-red accents stay either way.
    val flagField = boriken && borikenField
    val seed = if (boriken && borikenAccent) BorikenCeleste else seedColor
    val background = if (flagField) CrystalBackground.MESH else crystalBackground
    val mesh = crystalMesh
    val meshSeed = crystalMeshCustomSeed
    val meshChroma = crystalMeshCustomChroma
    val room = if (flagField) null else crystalRoom
    val base = resolveTheme(
        darkTheme, isAmoled, seed, themeStyle, crystalStyle,
        crystalIntensity, background, room, darkContrastLevel
    )
    val resolved = if (boriken) base.copy(colorScheme = base.colorScheme.withBorikenRed(darkTheme)) else base
    val crystal = themeStyle == ThemeStyle.CRYSTAL
    // Read before `MaterialTheme` so the non-Crystal branches re-provide the value they would have
    // had anyway. See the note on `LocalContentColor` below.
    val inheritedContentColor = LocalContentColor.current
    MaterialTheme(
        colorScheme = resolved.colorScheme,
        typography = resolved.typography,
        shapes = resolved.shapes
    ) {
        CompositionLocalProvider(
            LocalThemeStyle provides themeStyle,
            LocalCrystalStyle provides crystalStyle,
            // **The Crystal locals are provided in every style, but only *valued* under Crystal.**
            // Arity has to be constant — that is the whole point of this function's shape — while the
            // values must not change what the flat themes see. Several helpers branch on
            // `isSolidField` alone, on the reasonable assumption that it can only be reached under
            // Crystal; handing them a real `SOLID` while Material 3 is active would quietly change
            // those. `.current` here is each local's default, which is exactly what the two Material
            // branches published before.
            LocalCrystalIntensity provides
                if (crystal) crystalIntensity else LocalCrystalIntensity.current,
            LocalCrystalBackground provides
                if (crystal) background else LocalCrystalBackground.current,
            LocalCrystalMesh provides if (crystal) mesh else LocalCrystalMesh.current,
            LocalCrystalCustomSeed provides
                if (crystal) meshSeed else LocalCrystalCustomSeed.current,
            LocalCrystalCustomChroma provides
                if (crystal) meshChroma else LocalCrystalCustomChroma.current,
            LocalCrystalRoom provides if (crystal) room else LocalCrystalRoom.current,
            LocalCrystalCustomColours provides
                if (crystal) crystalMeshCustomColours else LocalCrystalCustomColours.current,
            LocalBoriken provides boriken,
            LocalBorikenField provides flagField,
            // Crystal's panels are a `Box` plus a modifier rather than a Material `Surface`, so
            // nothing inside one provides `LocalContentColor` and Compose's fallback is
            // `Color.Black` — legible in light Crystal, invisible in dark. Anchoring it here covers
            // every one of them. Outside Crystal this re-provides the inherited value unchanged,
            // because Material's own `Surface`es are what set it there and overriding the root would
            // reach every `Text` that sits outside one.
            LocalContentColor provides
                if (crystal) resolved.colorScheme.onBackground else inheritedContentColor,
            content = content
        )
    }
}

/** The three styles' answers to "which scheme, type and shapes", with no content involved. */
private data class ResolvedTheme(
    val colorScheme: ColorScheme,
    val typography: Typography,
    val shapes: Shapes
)

/**
 * Picks the scheme/type/shapes triple for [themeStyle].
 *
 * **This function exists so that [BoriTheme] can compose `content` from exactly one call site, and
 * that is not a tidiness argument — it is a bug fix.** Compose identifies a subtree by *where* it is
 * called, so when this logic was three `if (…) { MaterialTheme { content() }; return }` blocks, each
 * style invoked `content` from a different position. Changing style therefore **disposed the entire
 * app below the theme and recomposed it from scratch**: the `NavHost` lost its back stack and
 * restarted at `startDestination`, which is why choosing a theme in Settings dumped the user back on
 * Today, and why every expanded Settings card collapsed on the way. Nothing navigated — the screen
 * simply ceased to exist. `CrystalRoot` had the identical defect and was fixed the same way.
 *
 * **So do not reintroduce a branch that wraps `content`.** Branch on values here; compose once there.
 */
@Composable
private fun resolveTheme(
    darkTheme: Boolean,
    isAmoled: Boolean,
    seedColor: Color,
    themeStyle: ThemeStyle,
    crystalStyle: CrystalStyle,
    crystalIntensity: Float,
    crystalBackground: CrystalBackground,
    crystalRoom: Color?,
    darkContrastLevel: Double
): ResolvedTheme {
    // Crystal is not built on materialkolor, so it cannot go through DynamicMaterialTheme at all.
    // Its whole scheme is computed here and handed to a plain MaterialTheme.
    if (themeStyle == ThemeStyle.CRYSTAL) {
        // Two remembers, not one, because `crystalIntensity` changes on every frame of a slider
        // drag. The opaque scheme is ~20 HCT conversions and depends on none of that, so it is keyed
        // only on what can actually change it; the alpha pass is a nine-colour `copy()` and is the
        // only thing that reruns while dragging.
        // The room belongs to the *opaque* key, not the per-frame alpha key below — it changes on a
        // tap, not on every frame of a slider drag.
        val opaque = remember(darkTheme, isAmoled, seedColor, crystalRoom) {
            val base = if (darkTheme) crystalDark(seedColor, isAmoled) else crystalLight(seedColor)
            if (crystalRoom != null) base.withRoomSurfaces(crystalRoom, darkTheme) else base
        }
        // Solid and mesh take different routes here. On the mesh the roles stay translucent and the
        // field is composited underneath them at draw time. On a flat field there is nothing to
        // discover at draw time — the answer is computable — so the roles are pre-composited opaque
        // instead. See `withSolidGlassSurfaces` for why that is the fix rather than a shortcut.
        val colorScheme = remember(opaque, crystalStyle, crystalIntensity, crystalBackground) {
            if (crystalBackground == CrystalBackground.SOLID) {
                opaque.withSolidGlassSurfaces(
                    field = crystalRoom
                        ?: if (darkTheme) opaque.background else CrystalSolidLight,
                    lift = if (darkTheme) SOLID_SURFACE_LIFT_DARK else SOLID_SURFACE_LIFT_LIGHT,
                    tint = crystalSolidTint(crystalStyle, darkTheme, crystalIntensity)
                )
            } else {
                opaque.withTranslucentSurfaces(
                    crystalSurfaceAlpha(crystalStyle, darkTheme, crystalIntensity)
                )
            }
        }
        return ResolvedTheme(colorScheme, Typography, CrystalShapes)
    }

    // Expressive builds its scheme explicitly, because `DynamicMaterialTheme` exposed no hook to
    // post-process one and the hue containment below has to run before Material sees it. Material 3
    // now also builds its scheme here rather than calling that composable — see the note on its
    // branch for why that is the same palette engine and not a substitution.
    if (themeStyle == ThemeStyle.EXPRESSIVE) {
        val colorScheme = remember(darkTheme, isAmoled, seedColor, darkContrastLevel) {
            // **`PaletteStyle.Expressive` is the least saturated of the two candidates, which is why
            // this theme read as "about the same" as Material 3 for two months.** Under `SPEC_2025`,
            // `ColorSpec2025.getPrimaryPalette` switches on the variant and hardcodes the derived
            // primary palette's chroma per style — **Expressive is 36 in dark and 48 in light, where
            // Vibrant is 74 in both.** Roughly double the saturation in dark, from an identical seed
            // and the identical engine. It is one enum value, and it is the whole lever.
            //
            // Settings has always described this style as having "a punchier palette"
            // (`SettingsScreen`, the Theme Style picker); until now that copy was a promise the
            // branch did not keep.
            //
            // **The seed's chroma is ignored here, and a floor on it was measured to do nothing.**
            // This branch briefly pre-boosted the seed with `atLeastChroma`, on the reasonable-sounding
            // theory that a palette style only decides how far to walk *from* the seed's saturation.
            // That is true of `TonalSpot`; it is **false** of `Vibrant` and `Expressive`, whose
            // `getPrimaryPalette` is `TonalPalette.fromHueAndChroma(sourceColorHct.hue, 74.0)` — a
            // hardcoded chroma, with only the **hue** read off the seed. Proved on the JVM: seeds of
            // C* 5, 13, 30, 48 and 80 at one hue all resolve to the same `primary` (C* 47.4–49.7).
            //
            // Two consequences worth stating, because the first was written down backwards. A pastel
            // accent is **not** pale under Expressive and never was — it produces the same palette as
            // the vivid preset at its hue, since the pastels sit within a few degrees of the vivids by
            // design. And there is no seed-side lever here at all; the only knobs are the style and
            // post-processing.
            dynamicColorScheme(
                seedColor = seedColor,
                isDark = darkTheme,
                isAmoled = isAmoled,
                style = PaletteStyle.Vibrant,
                contrastLevel = if (darkTheme) darkContrastLevel else 0.0,
                specVersion = ColorSpec.SpecVersion.SPEC_2025
            )
                // **Kept, and it is not what was making this pale.** `withContainedHues` rotates hue
                // only — chroma and tone pass through `Hct.from(pulled, hct.chroma, hct.tone)`
                // untouched — so it cannot desaturate anything, and it never touches `primary` at
                // all.
                //
                // Under Vibrant it is now a **no-op on the role that mattered**: Vibrant rotates
                // secondary by at most 14°, well inside the 30° limit, where Expressive rotated it up
                // to 160°. So the off-orange nav pill this was written for cannot recur here, and the
                // clamp survives only as insurance if the style is ever changed back. It no longer
                // touches tertiary — see its KDoc for the measured rotation tables.
                //
                // The hue is identical either side of the chroma floor, so passing the punched seed
                // rather than the original is a statement of intent, not a behaviour change.
                .withContainedHues(seedColor)
        }
        return ResolvedTheme(colorScheme, ExpressiveTypography, ExpressiveShapes)
    }

    // Material 3 — the app's original look, on the same palette engine it has always used.
    //
    // **This is `DynamicMaterialTheme`'s own scheme, not a lookalike.** That composable was read out
    // of materialkolor 5.0.0 with `javap` before this changed: the seed overload delegates to
    // `rememberDynamicMaterialThemeState(...)` and then, with `animate = false`, calls
    // `MaterialTheme(state.colorScheme, shapes, typography, content)` — the animation branch is
    // skipped entirely. So taking the state's `colorScheme` here and handing it to the single
    // `MaterialTheme` in [BoriTheme] is the identical code path, and it is a stronger equivalence
    // than reaching for `rememberDynamicColorScheme`, which merely computes the same kind of thing.
    // The one thing dropped is `LocalDynamicMaterialThemeSeed`, which `DynamicMaterialTheme` also
    // provides and which nothing in this app reads (grepped).
    //
    // **`animate = false` is load-bearing for that argument** — with it true the scheme would go
    // through `animateColorScheme` and this substitution would not hold.
    val material3State = rememberDynamicMaterialThemeState(
        seedColor = seedColor,
        // materialkolor 3 renamed these from useDarkTheme/withAmoled.
        isDark = darkTheme,
        isAmoled = isAmoled,
        contrastLevel = if (darkTheme) darkContrastLevel else 0.0,
        style = PaletteStyle.TonalSpot,
        // Pinned rather than left to default. materialkolor 5 added SPEC_2025 (Material 3
        // Expressive), which regenerates every colour in the app from the same seed — a design
        // decision, not a version bump. The library's default is still SPEC_2021, so this changes
        // nothing today; it exists so that when the default does flip, the app's whole palette
        // doesn't change without a diff saying so. The Expressive branch above opts into SPEC_2025
        // deliberately, which is the difference the user is choosing between.
        specVersion = ColorSpec.SpecVersion.SPEC_2021
    )
    return ResolvedTheme(material3State.colorScheme, Typography, Material3Shapes)
}

// `CrystalContentColor` used to live here — it was the Crystal branch's private wrapper around
// `content`, and being a *third* place `content` could be composed from is precisely what made
// switching themes dispose the whole app. Its provisions, including the `LocalContentColor` anchor
// Crystal needs (its panels are a `Box` plus a modifier, not a Material `Surface`, so nothing inside
// one provides a content colour and Compose's fallback is `Color.Black` — invisible in dark
// Crystal), now happen in `BoriTheme`'s one `CompositionLocalProvider`. Don't reintroduce a
// per-style wrapper.

/**
 * **Borikén** — the hidden Puerto Rico edition, unlocked by tapping the version number ten times.
 * Its blue is the *celeste* of the light-blue flag, deliberately brighter than any accent preset,
 * and its red is the flag's.
 */
val BorikenCeleste = Color(0xFF3EA6F2)
val BorikenRed = Color(0xFFE4002B)

/** True while the Borikén edition is on, for the Taíno touches (background glyphs, empty states). */
val LocalBoriken = staticCompositionLocalOf { false }

/** True while the field is Borikén's flag gradient rather than the user's own choice. */
val LocalBorikenField = staticCompositionLocalOf { false }

/**
 * Puts the flag's red into the tertiary roles, the slot Material reserves for a contrasting accent.
 * A post-process on the finished scheme, the same shape as the room and hue-containment passes, so
 * the primary/surface derivation is untouched. Dark mode takes a lighter red so it keeps contrast on
 * dark surfaces, the way every Material role pair does.
 */
private fun ColorScheme.withBorikenRed(dark: Boolean): ColorScheme = if (dark) copy(
    tertiary = Color(0xFFFFB4AB),
    onTertiary = Color(0xFF690005),
    tertiaryContainer = Color(0xFF93000A),
    onTertiaryContainer = Color(0xFFFFDAD6)
) else copy(
    tertiary = BorikenRed,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD6),
    onTertiaryContainer = Color(0xFF410002)
)
