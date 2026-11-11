package com.bori.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One shape scale per theme style. These feed `MaterialTheme.shapes`, so stock Material components
 * (Cards, Buttons, TextFields, Menus, Chips) pick up the right radii without any call site knowing
 * which theme is active.
 */

/** Rhythm's own scale, unchanged — lifted out of `Theme.kt` so all three live side by side. The
 * jump from 16dp to 32dp at `large` is deliberate: `large` is what the app's cards use. */
val Material3Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(48.dp)
)

/**
 * Material 3 Expressive runs fuller and rounder than baseline Material across the whole scale.
 *
 * **It did not, and that is half of why this theme read as "about the same as last time".** `large`
 * was **32dp — identical to [Material3Shapes]** — and `large` is the value the app's cards use, per
 * the note above. So the surface the user actually looks at most rendered with the exact same corner
 * radius in both styles, and `extraLarge` was *smaller* under Expressive (44 against 48), making the
 * scale rounder in the middle and tighter at the top. The comment claimed a change the numbers never
 * made.
 *
 * `large` now clears Material 3's by 8dp and `extraLarge` goes above it rather than below, so the
 * scale is monotonically fuller — which is what "fuller and rounder across the whole scale" means and
 * what the Settings picker's description promises. [Material3Shapes] is untouched: it is the default
 * style and an update must not restyle anyone who never chose Expressive.
 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small      = RoundedCornerShape(18.dp),
    medium     = RoundedCornerShape(26.dp),
    large      = RoundedCornerShape(40.dp),
    extraLarge = RoundedCornerShape(56.dp)
)

/**
 * Crystal leans on generous but *continuous* corners — noticeably softer than Rhythm's own scale at
 * the small end and noticeably tighter at the large end. Glass panels need a radius that reads as a
 * bevelled edge catching light; a 32dp card corner turns the specular rim into a racetrack.
 */
val CrystalShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small      = RoundedCornerShape(14.dp),
    medium     = RoundedCornerShape(20.dp),
    large      = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)
