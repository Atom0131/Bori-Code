package com.bori.theme

import androidx.compose.ui.graphics.Color
import com.bori.theme.settings.CrystalMesh

/**
 * The fixed palette behind [com.bori.theme.settings.ThemeStyle.CRYSTAL] — cool, neutral
 * iOS-26 surfaces, ported from the Recipe App's Liquid Glass system.
 *
 * **These are the neutrals only.** Crystal's accent roles (primary/secondary/tertiary and their
 * containers) are *not* here: they are derived from the user's chosen accent colour through
 * materialkolor, exactly as the other two theme styles do, so the App Accent Color picker keeps
 * working under Crystal. The source system hardcoded iOS system blue instead; Rhythm deliberately
 * does not, because the accent picker predates this theme and is a headline feature of the app.
 *
 * The ambient blob colours are fixed, however. They are what makes Crystal recognisable as Crystal,
 * and rotating them around an arbitrary accent hue turns a red accent into a warm mesh that no
 * longer reads as glass at all.
 */

// ---- Light neutrals ----------------------------------------------------------------------------
val CoolWhite = Color(0xFFF6F7F9)              // app background base
val CoolSurfaceLight = Color(0xFFFFFFFF)
val CoolSurfaceVariantLight = Color(0xFFE9EBEF)
val InkLight = Color(0xFF1C1C1E)               // primary text on light

/**
 * Secondary text on light — `onSurfaceVariant`.
 *
 * **It was `#6B6F76` and failed AA on the ambient field, which is where a lot of it actually sits.**
 * Measured off the Fold against a live Verdant field: `"Good afternoon"` came out at **2.81:1** and
 * the unselected nav labels at **3.21:1**, both well under the 4.5:1 floor — reported as the grey
 * being hard to read, worst on Verdant. The dark counterpart [InkMutedDark] was raised for exactly
 * this reason when the dark palettes were lifted; **the light side was never re-checked**, and a
 * muted grey chosen against a white background is simply a different problem from one sitting on a
 * coloured field.
 *
 * `#45484F` measures **5.10:1** on that same Verdant field and 6.0–6.8:1 on the brighter meshes, so
 * it clears the floor across the whole set rather than only where it was sampled. It stays clearly
 * *secondary* — [InkLight] is 9.47:1 on the same background — so the onSurface → onSurfaceVariant
 * hierarchy survives; it is the gap to the **field** that changed, not the gap to the primary ink.
 */
val InkMutedLight = Color(0xFF45484F)
val OutlineLight = Color(0xFFC8CCD2)
val OutlineVariantLight = Color(0xFFDDE0E5)
val SlateSecondaryLight = Color(0xFF5A6472)

// ---- Dark neutrals (iOS dark) ------------------------------------------------------------------
val InkBackgroundDark = Color(0xFF0B0B0D)
val CoolSurfaceDark = Color(0xFF1C1C1E)
val CoolSurfaceVariantDark = Color(0xFF2C2C2E)
val InkDark = Color(0xFFF2F3F7)                // primary text on dark
// Brightened from a plainer grey for AA over the glass mesh — a translucent panel sitting on a lit
// blob is a much brighter backdrop than a flat dark surface, so the usual muted grey fails there.
//
// **Brightened a second time (L\* 75.3 -> 85.3) on 2026-08-23, and it is not optional.** Lifting the
// dark field 40% lifts every card with it, and this is `onSurfaceVariant` — all the secondary text
// in the app. At the old value the worst case (**Sheer at intensity 0**, the thinnest card the app
// can produce) fell to **3.44:1**, under the 4.5:1 AA body-text floor, from 5.06:1 before. It is now
// 4.63:1. Measured on the composited card, not on the field: text sits on the panel.
//
// **So the field's tone and this constant are coupled.** With the old grey the field could only be
// lifted 1.10x before AA broke; the 1.40x the design now uses is only reachable because this moved
// too. Re-darkening this without re-darkening the field silently reintroduces the failure — and it
// fails on the *thinnest* Crystal style, which is not the one anyone checks first.
val InkMutedDark = Color(0xFFD0D6E0)
val OutlineDark = Color(0xFF56575A)
val OutlineVariantDark = Color(0xFF48494C)
val SlateSecondaryDark = Color(0xFFAAB2C0)

// ---- Ambient mesh ------------------------------------------------------------------------------
// The base wash the five blobs are drawn over.
//
// **The dark set was lifted 40% in L\* on 2026-08-23** — the user reported dark mode as too dark
// across every mesh, not just one. Every dark blob and this base were raised by the same factor in
// **LCh, holding hue and chroma**, so the hue relationships and the per-slot chroma caps the mesh
// gate enforces are untouched; only tone moved. Regenerate at a different factor by editing `K` in
// `scripts/lighten_dark_mesh.py` rather than hand-editing 25 hex literals, or the five palettes
// drift out of the shared-tone invariant that makes card separation correct by construction.
//
// Two consequences, both measured rather than assumed:
//   * separation *widens* (a dark card is darker than the field, so a lighter field pushes them
//     apart): Tinted@0% went from 11.2 to 17.6 L\*, Sheer@0% from 6.5 to 10.2.
//   * `InkMutedDark` had to be brightened with it — see the note on that constant.
val AmbientBaseLight = Color(0xFFEAF0F6)
val AmbientBaseDark = Color(0xFF242732)

/**
 * The flat field, for anyone who would rather not have the five-colour mesh.
 *
 * **Pitched a clear step below the card colour on purpose, and that is the whole difficulty of a
 * solid Crystal.** A panel is its backdrop tinted with `surfaceContainer`, so on a flat field the
 * only thing separating a card from the room is the distance between those two colours. At the
 * mesh's own base (`#EAF0F6` against a `#F2F4F7` card) a panel resolves to within one level of its
 * background and the layout is left resting on the rim alone.
 *
 * The value is set by the **weakest** point of the range, not the typical one — light mode, Sheer,
 * Glass Intensity at 0%, where the card fill is only 0.28 opaque. At `#E4EAF2` that corner separated
 * by under 4 levels; `#DFE6F0` brings it past 5 and every other combination lands between 8 and 16,
 * against Material's own background→surfaceContainer step of roughly 6–10 for scale.
 *
 * Dark needs no constant: `colorScheme.background` is already `#0B0B0D` against a `#1C1C1E` card —
 * a real step at every intensity — and it is the value that AMOLED swaps for true black, which a
 * fixed colour here would silently override.
 */
val CrystalSolidLight = Color(0xFFDFE6F0)

/** teal, sky, indigo, violet, coral — deliberately vivid (medium saturation, not neon), because
 * clear glass shows and tints this rather than hiding it. */
val AmbientBlobsLight = listOf(
    Color(0xFF74D0C4), Color(0xFF7FB4F5), Color(0xFF8E9BF0), Color(0xFFC49BEA), Color(0xFFF6B79E)
)

/** The same five hues lifted into a rich twilight rather than near-black — a straight darkening of
 * the light set reads as a muddy grey field with no colour left in it. */
val AmbientBlobsDark = listOf(
    Color(0xFF4F96A3), Color(0xFF5477B5), Color(0xFF5A51A3), Color(0xFF785195), Color(0xFF9F5F64)
)

/*
 * ---- The other nine fields ---------------------------------------------------------------------
 *
 * **Every variant reuses Aurora's five tones exactly, slot for slot, in both modes.** Light is
 * L* 77.8 / 72.1 / 66.1 / 70.3 / 79.6 and dark is 58.0 / 49.9 / 38.9 / 41.3 / 47.6; the base wash,
 * the blob positions, radii and alphas are shared. That is not a stylistic preference, it is what
 * makes the whole set safe:
 *
 * A Crystal panel *is* its backdrop tinted with `surfaceContainer`, so the separation between a card
 * and the field is the tone distance between the two. Under `CrystalBackground.MESH` the surface
 * roles are the neutral ones with alpha (`withTranslucentSurfaces`) — nothing lifts them to follow
 * the field the way `withSolidGlassSurfaces` does for Solid. So a field that moved the tone would
 * change card separation on every screen and force the whole surface family to be re-derived, which
 * is the round-four finding and the reason the room colour picker was never let near the mesh.
 * Holding the tones fixed means the composited field tone is identical across every variant, and
 * separation, text contrast and the surface roles are preserved **by construction** rather than by
 * re-measuring each time — the same argument as `crystalRoomColor` returning null for Neutral.
 *
 * Chroma may sit at or below Aurora's per-slot value, never above: chroma only ever *adds* to the
 * perceived gap between two colours of the same tone, so staying under the cap cannot erode
 * anything. Hue is the only free choice. `scripts/verify_mesh_palettes.py` enforces all three rules
 * against this file.
 *
 * Where a hue cannot hold Aurora's chroma at Aurora's tone in sRGB, the chroma is what gives — the
 * greens and the deep warms both hit the gamut wall well before the indigos do.
 */

/** Ember — amber, coral, rose, plum, gold. The warm field. */
val AmbientBlobsEmberLight = listOf(
    Color(0xFFE7B78D), Color(0xFFF29A8C), Color(0xFFE58498), Color(0xFFE094CA), Color(0xFFE3C090)
)
val AmbientBlobsEmberDark = listOf(
    Color(0xFFA58569), Color(0xFFAB635C), Color(0xFF8F4451), Color(0xFF82517D), Color(0xFF886D46)
)

/**
 * Verdant — one green band: emerald, mint, moss, olive and pale lime.
 *
 * **A field's punch is set by its hue *span*, not by its per-slot chroma, and that is the correction
 * this palette records.** Verdant was reported dim twice, and both times the response was to raise
 * chroma — first in round eleven, then again when it was pushed to Aurora's per-slot caps. It stayed
 * dim, and the reason is that the blobs overlap: greens at hue ~135–165 against purples at ~300–320
 * are very nearly complementary, so wherever two blobs met they cancelled toward grey. The field was
 * carrying Aurora's chroma slot for slot (mean C\* 37 against Aurora's 38) and still **rendered** at
 * C\* 11 where Rose rendered at 23.
 *
 * Simulating `drawCrystalMeshField`'s own blend over all five fields makes it exact, and monotonic:
 *
 * ```
 *   Rose     span  46°  ->  rendered C* 22.8      Mist     span 158°  ->  12.4
 *   Aurora   span 138°  ->             19.9       Verdant  span 175°  ->  11.4
 *   Ember    span 102°  ->             17.6
 * ```
 *
 * So the fix is to narrow the span, not to add chroma there is no room for: the hues span **27°**,
 * against Rose's 46°, and the field renders well clear of where it started.
 * Per-slot chroma is at or below Aurora's cap and the tones are Aurora's exactly, so
 * `verify_mesh_palettes.py` is untouched by this — the gate was never what held Verdant back.
 *
 * The green-and-purple mixture is therefore gone deliberately, not lost. It cannot be bright: any
 * two-family field wide enough to read as a mixture is wide enough to cancel.
 *
 * **The dark slots are chroma-capped at C\* 40 and every channel is floored at ~0.075**, which is
 * the same guard the old moss centre was written to: at its reachable C\* 48 it rendered `#004D07`
 * with the red channel at zero and read as neon rather than as moss. A pure sRGB gamut check does
 * not catch that, because zero-channel colours are in gamut. Two earlier failures this palette
 * already survived are still worth not repeating: a yellow-green at hue ~100 goes khaki at the dark
 * bottom-right's L\* 34.0, and a green at hue 150 collapsed into the teal that used to sit at hue
 * 191 next to it. Both were invisible in the numbers and obvious in a render.
 *
 * **The khaki warning above was written and then not applied to this palette's own band, which is
 * what the 2026-08-27 correction fixes.** The band shipped as `149 128 140 165 115`, so the top-right
 * and bottom-right slots sat at hue 128° and **114.8°** — yellow-greens — and at L\* 49.5 and 47.4
 * they rendered `#5F7F43` and `#6C7545`: olive and khaki, exactly the failure the paragraph above
 * names. Reported as Verdant looking "like garbage green" at night. **There is no dark scrim and no
 * runtime transform** — `ambientBlobs` just returns the other hardcoded list — so the dark look is
 * these five literals and nothing else. The band is now `149 142 140 165 138`: only the two offending
 * slots moved, only in hue, at the same tone and the same chroma, so the shared-tone invariant and
 * the chroma caps hold by construction and the gate could not have caught the old values. Note this
 * also *narrows* the span from 50° to 27°, which by the argument above makes the field read as more
 * saturated rather than less. `scripts/gen_mesh_palette.py` now carries Verdant's band, so this
 * palette regenerates with the other five rather than being hand-edited.
 */
val AmbientBlobsVerdantLight = listOf(
    Color(0xFF97CDA2), Color(0xFF86BE85), Color(0xFF6DB068), Color(0xFF47C095), Color(0xFFA9D0A0)
)
val AmbientBlobsVerdantDark = listOf(
    Color(0xFF6E9476), Color(0xFF4D824E), Color(0xFF316730), Color(0xFF336C56), Color(0xFF577950)
)

/**
 * Rose — blush, pink, orchid, coral-red, petal.
 *
 * The fifth field. Distinct from [AmbientBlobsEmberLight] despite both being warm: Ember is amber
 * and gold and reads rust in the dark, where Rose stays in the pinks and magentas.
 *
 * **A cyan/ocean field was tried for this slot first and abandoned** — hues 186–235 cannot hold
 * these chromas at these tones in sRGB, and four of the five dark slots clipped, which lands a
 * colour at the wrong tone *and* the wrong hue at once. A blue-indigo field clipped three. Rose
 * clips nothing in either mode.
 */
val AmbientBlobsRoseLight = listOf(
    Color(0xFFF2ADCB), Color(0xFFE09BCE), Color(0xFFC38CD8), Color(0xFFF18FA8), Color(0xFFF1B3D7)
)
val AmbientBlobsRoseDark = listOf(
    Color(0xFFAE7D91), Color(0xFFA26190), Color(0xFF7F4491), Color(0xFFA0415B), Color(0xFF96617E)
)

/**
 * Mist — Aurora's own hues at **85%** of its chroma.
 *
 * The quietest **coloured** field — Dune and Graphite went quieter still in 2026-08-23's round, and
 * Graphite is quieter by an order of magnitude, but both of those give up hue to do it and this does
 * not. Still the gap between the full mesh and Solid: same hues as
 * Aurora, same tones, only the volume down. It began at C\* 7–12, went to 60%, and was *still*
 * reported washed out alongside Verdant. It is now 85%, which renders at C\* 17.1 light / 19.3 dark
 * against Aurora's 20.0 / 24.7 — soft rather than absent, and still unmistakably the quiet one.
 *
 * **Unlike Verdant, the hue span is deliberately left alone at Aurora's 158°, and that is the
 * interesting part.** Verdant's dimness was cancellation between near-complementary greens and
 * purples, fixed by narrowing its span to 50°. The same trick was tried here — a narrow cool band
 * around teal/blue — and it *did* raise the number, but rendering it showed why the number was the
 * wrong target: it stopped being Aurora-with-the-volume-down and became a blue field that competed
 * with Aurora instead of sitting quietly beside it. It also bought far less in dark (15.5 against
 * the 19.3 simply raising the scale gives here), because at the dark tones those cool hues hit the
 * channel floor before any cap binds.
 *
 * So Mist keeps its identity and pays for its lift with chroma, where Verdant could not — Verdant's
 * slots were already at Aurora's caps with nothing left to raise. Two fields, two different causes,
 * two different fixes; only a render distinguishes them.
 */
val AmbientBlobsMistLight = listOf(
    Color(0xFF82CEC4), Color(0xFF8AB3EB), Color(0xFF929CE4), Color(0xFFC09EE1), Color(0xFFF0B9A4)
)
val AmbientBlobsMistDark = listOf(
    Color(0xFF5B949F), Color(0xFF5B77AC), Color(0xFF5D558C), Color(0xFF75558A), Color(0xFF986266)
)

/*
 * ---- Five more fields, added 2026-08-23 --------------------------------------------------------
 *
 * Same three rules, no exemptions: Aurora's tone slot for slot, chroma at or below Aurora's per-slot
 * cap, hue the only free choice. Every one holds tone to within **0.2 L\***, so the separation
 * argument above extends to them by construction rather than by re-measurement.
 *
 * They were chosen by rendering `drawCrystalMeshField` itself over candidate hue bands rather than
 * by reading the numbers, which is the lesson Verdant cost twice: its per-slot chroma matched
 * Aurora's exactly while it *rendered* at half Aurora's, and no per-slot figure showed that. The
 * bands are narrow (~50°) for the same reason — a wide span cancels toward grey where blobs overlap.
 *
 * **Three candidates were built, rendered and rejected, and two of the reasons are structural.**
 *
 * - **A yellow field is not reachable, and nudging its hue will not fix it.** Chroma peaks for
 *   yellow well above L\* 85, and the centre slot is pinned at L\* 66.1 light / 39.1 dark — so a gold
 *   or chartreuse band resolves to **olive**, and rendered as khaki mud in both modes. This is the
 *   same failure the Verdant note records for hue ~100, but it is not a hue that can be moved off:
 *   the tone is fixed by the shared-tone invariant, so the set is permanently short of yellows. That
 *   is a cost of the invariant, not an oversight, and it is the thing to say when it is proposed.
 * - **A hot red-orange band ("Sunset") renders as Ember's rust in dark.** Rejected as a duplicate
 *   rather than as a failure — it was a perfectly good field, just one the app already has.
 * - **A teal-green band ("Jade") sits between Verdant and Tide** and stopped being distinct from
 *   either once Tide was in. Order matters here: Jade was fine on its own.
 *
 * **Warm fields are structurally limited and the new set is cool-heavy because of it.** In light the
 * tones are pastel-bright, so *every* warm hue lands somewhere in peach/salmon/tan — which Ember,
 * Rose and now Dune already occupy. The unclaimed part of the wheel was all cool, which is why three
 * of the five are.
 */

/**
 * Tide — aqua, cyan, azure, cerulean, sea-glass. The ocean field, and the biggest gap in the set:
 * hues 186–236 were unrepresented apart from Aurora's single teal corner.
 *
 * **This is the field the Rose note records as tried and abandoned.** That attempt clipped four of
 * five dark slots, which lands a colour at the wrong tone *and* the wrong hue at once. It is
 * reachable now because the chroma **yields** instead — the rule this file already states for the
 * greens and deep warms — and because the 2026-08-23 dark lift gave cyan more room at L\* 39 than it
 * had at the old 34.
 *
 * **It is also the field that showed the per-slot rules are necessary and not sufficient, which is
 * the part worth carrying forward.** Built to the stated rules — Aurora's tone, chroma under
 * Aurora's cap, in gamut — this palette still failed `verify_mesh_palettes.py`, on the *composited*
 * field tone, by 1.3 L\* at the centre. The blobs are composited in **gamma-encoded** sRGB, so a
 * colour at the right L\* with one channel jammed against the gamut wall (cyan at C\* 47 puts red at
 * **21**) darkens the field more than a balanced colour of the same L\* does. Every other field
 * satisfies the composited check for free; only a hue running along the gamut boundary does not.
 * Chroma therefore gives a second time, to C\* 29–34 light, and the whole set now lands within
 * **0.49 L\*** where the shipping five run to 0.54.
 *
 * So: **do not read "tone matches and chroma is under the cap" as proof a palette is safe.** The
 * composited tone is the number the separation argument rests on, and it is the one the gate fails
 * on. Renders at C\* 25 light / 24 dark.
 */
val AmbientBlobsTideLight = listOf(
    Color(0xFF73D0C5), Color(0xFF4BC1C6), Color(0xFF45AEC2), Color(0xFF4CB8DE), Color(0xFF7BD4CF)
)
val AmbientBlobsTideDark = listOf(
    Color(0xFF56978F), Color(0xFF158487), Color(0xFF156572), Color(0xFF136A83), Color(0xFF1C7E7A)
)

/**
 * Indigo — sky, cornflower, blue, periwinkle, powder. Hues 252–293, a 41° band.
 *
 * Aurora passes through this territory (its 270/290 slots) but arrives from teal and leaves through
 * coral, so it reads as a sweep; this reads as one blue. It clips nothing in either mode — the
 * indigos are the part of the wheel that holds Aurora's chroma most comfortably, which is the same
 * fact the Rose note cites from the other direction.
 */
val AmbientBlobsIndigoLight = listOf(
    Color(0xFF84C8F6), Color(0xFF74B6F5), Color(0xFF70A1F4), Color(0xFFA0A5F7), Color(0xFF94CBFA)
)
val AmbientBlobsIndigoDark = listOf(
    Color(0xFF6491B0), Color(0xFF317CB5), Color(0xFF155DA5), Color(0xFF515CA3), Color(0xFF3F769E)
)

/**
 * Orchid — periwinkle, lilac, violet, magenta-violet, wisteria. Hues 285–327.
 *
 * Sits between Aurora and Rose on the wheel and is distinct from both for the reason Verdant's note
 * gives: it is a *band* where Aurora is a sweep, so it reads as one colour rather than as a mesh of
 * several. Rose starts where this ends (318–4) and is pink rather than violet.
 */
val AmbientBlobsOrchidLight = listOf(
    Color(0xFFB1BEF6), Color(0xFFB4A8EC), Color(0xFFBA8FE0), Color(0xFFDA94DA), Color(0xFFC2C0F7)
)
val AmbientBlobsOrchidDark = listOf(
    Color(0xFF828AB2), Color(0xFF796FAC), Color(0xFF734999), Color(0xFF884B88), Color(0xFF6C6D9B)
)

/**
 * Dune — sand, clay, tan, ochre, oat. Hues 40–81 at **50%** of Aurora's chroma caps.
 *
 * The warm counterpart to Mist, and the only quiet warm field: it renders at C\* 13 light / 17 dark
 * where Ember is 20/21. Mist is the quiet *cool* one and the set had no equivalent on the warm side,
 * so a user who wants a calm room had to accept a cool one.
 *
 * **Its chroma is a choice, not a limit** — unlike Tide, these hues could hold the full caps, and at
 * the caps this is simply Ember. 50% is what makes it read as paper and stone rather than as amber.
 * If it is ever reported washed out, the fix is the scale, and Mist's history is the warning: it was
 * raised twice (60% then 85%) before it stopped reading as absent.
 */
val AmbientBlobsDuneLight = listOf(
    Color(0xFFDEB8AE), Color(0xFFCFA995), Color(0xFFBE9A78), Color(0xFFC2A881), Color(0xFFE1BEB0)
)
val AmbientBlobsDuneDark = listOf(
    Color(0xFFA1867F), Color(0xFF91705E), Color(0xFF755636), Color(0xFF745E3C), Color(0xFF896A5E)
)

/**
 * Graphite — a near-neutral field at **18%** of the chroma caps, rendering at C\* 4 light / 5 dark.
 *
 * **Deliberately not a colour, and it is not Solid either.** Solid is one flat tone; this keeps the
 * mesh's depth — the corners still fall away from the centre — with the hue taken out of it. It is
 * the option for wanting glass without wanting a colour behind it, which the set otherwise could not
 * express: Mist is the quietest coloured field and is still unmistakably blue.
 *
 * Its hues are spread wide on purpose rather than banded. At this chroma the span cannot cancel
 * anything visible, and the small residual keeps it from reading as a dead grey wash.
 */
val AmbientBlobsGraphiteLight = listOf(
    Color(0xFFB5C3C3), Color(0xFFA3B3BC), Color(0xFF9BA0B2), Color(0xFFADAAB8), Color(0xFFD1C2C0)
)
val AmbientBlobsGraphiteDark = listOf(
    Color(0xFF838E8E), Color(0xFF6C7980), Color(0xFF575C6B), Color(0xFF63606D), Color(0xFF7C6E6C)
)

/** The five blob colours for a field, in slot order (top-left, top-right, centre, bottom-left,
 * bottom-right). Aurora's two lists keep their original names so the default cannot drift. */
/**
 * The accent that ships with each field, as an index into `habitColorPalette`.
 *
 * Picking a field sets this too, so a field looks deliberate the moment it is chosen rather than
 * leaving a violet accent over a green room. **It is a starting point, not a lock** — the accent
 * picker sits directly below and overrides it, and nothing re-applies this afterwards.
 *
 * Chosen by nearest hue to each field's chroma-weighted mean, against the five vivid presets rather
 * than the pastels: under Crystal `atTone` *floors* accent chroma at `MIN_ACCENT_CHROMA`, so a
 * pastel accent is pulled back up to full saturation anyway and the two rows would pair identically.
 * Ember is the one deliberate departure from the mean — its mean sits at hue 28 because the rose
 * centre drags it, but the two corner blobs that actually dominate the field are amber at hue 67 and
 * 79, so it takes Amber rather than Ruby Red.
 *
 * **Aurora maps to Deep Purple, which is already the app's default accent**, so an install that has
 * never touched either setting is unchanged by this.
 *
 * Note the accent is a single global preference shared with Material 3 and Expressive, so choosing a
 * Crystal field changes the accent in those styles too. That is the intended trade: one accent, set
 * from wherever the user last expressed a preference.
 */
fun crystalMeshAccent(mesh: CrystalMesh): Int = when (mesh) {
    CrystalMesh.AURORA -> 0   // Deep Purple   h305 vs field h290
    CrystalMesh.EMBER -> 4    // Amber/Orange  h61  vs its dominant corners h67/79
    CrystalMesh.VERDANT -> 1  // Emerald Green h163 vs field h193
    CrystalMesh.MIST -> 2     // Azure Blue    h273 vs field h283
    CrystalMesh.ROSE -> 0     // Deep Purple   h305 vs field h341
    CrystalMesh.TIDE -> 1     // Emerald Green h163 vs field h208 — 45° away against Azure's 65°.
                              // A cyan field genuinely sits between the two presets; the rule above
                              // picks the nearer one rather than the more obvious-sounding one.
    CrystalMesh.INDIGO -> 2   // Azure Blue    h273 vs field h272
    CrystalMesh.ORCHID -> 0   // Deep Purple   h305 vs field h306
    CrystalMesh.DUNE -> 4     // Amber/Orange  h61  vs field h61
    // Graphite renders at C* 4-5, so its chroma-weighted mean hue is arithmetic noise — nearest-hue
    // would report a confident answer computed from nothing. It takes the app's **default** accent
    // instead, which is also what makes choosing the neutral field leave a never-touched install
    // exactly as it was. Do not "fix" this to match its mean.
    CrystalMesh.GRAPHITE -> 0 // Deep Purple, the app default
    // A custom field's hue is whatever the user picked, so there is no constant to record here.
    // It takes the app default for the same reason Graphite does — and there is a second reason:
    // this feeds `accentSeed` only while the accent has never been touched, so deriving an accent
    // from a field the user is actively dragging a colour wheel for would make the whole app's
    // palette chase the wheel. The field is the choice; the accent stays theirs.
    CrystalMesh.CUSTOM -> 0
}

/**
 * The blob list for [mesh], **reading the custom field's seed from composition** so no call site has
 * to know that one of the eleven is solved rather than declared.
 *
 * Every `drawCrystalMeshField` caller goes through this. `ambientBlobs` below stays as the plain
 * lookup for the ten literal fields — `verify_mesh_palettes.py` parses that function, and a
 * composable it cannot call would blind the gate.
 */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun crystalFieldBlobs(mesh: CrystalMesh, dark: Boolean): List<Color> =
    if (LocalBorikenField.current) {
        // The light-blue flag as a gradient: celeste across the top, white through the middle, red
        // along the bottom — built by the same combo solver a user's custom field uses.
        borikenFieldBlobs(dark)
    } else {
        crystalOwnFieldBlobs(mesh, dark)
    }

/**
 * Borikén in daytime: blobs at 0.74 strength over a lightly celeste base. 2026-10-14 had a deeper
 * base (#D8E8F5) at 0.82 to stop "the white washes it out", but that left the screen ~14 L* darker
 * than plain Crystal by day. 2026-10-22, tuned on the Fold: plain Crystal's strength was "too light
 * now", so this sits in the middle (~71 L* vs Crystal's ~76 and the old ~62), with the white band
 * lifted in [BorikenDayTone] so the centre reads white, not grey. Night is unchanged. Same values as
 * the desktop.
 */
val BorikenDayBase = Color(0xFFE0ECF6)
const val BorikenDayDilution = 0.74f

/** The field's base colour: Borikén's tinted daytime base, else the plain light/dark base. */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun crystalFieldBase(dark: Boolean): Color = when {
    dark -> AmbientBaseDark
    LocalBorikenField.current -> BorikenDayBase
    else -> AmbientBaseLight
}

/** How strongly the field's colour blobs are painted: Borikén's daytime strength, else the usual. */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun crystalFieldDilution(dark: Boolean): Float = when {
    dark -> com.bori.theme.components.MESH_BLOB_DILUTION_DARK
    LocalBorikenField.current -> BorikenDayDilution
    else -> com.bori.theme.components.MESH_BLOB_DILUTION_LIGHT
}

/**
 * The field [mesh] itself draws, ignoring Borikén — for the picker's swatches, which must show each
 * field as it is rather than the edition currently overriding the screen.
 */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun crystalOwnFieldBlobs(mesh: CrystalMesh, dark: Boolean): List<Color> =
    if (mesh == CrystalMesh.CUSTOM) {
        val combo = LocalCrystalCustomColours.current
        if (combo.size >= 2) {
            crystalComboBlobs(combo, LocalCrystalCustomChroma.current, dark)
        } else {
            crystalCustomBlobs(
                LocalCrystalCustomSeed.current,
                LocalCrystalCustomChroma.current,
                dark
            )
        }
    } else {
        ambientBlobs(mesh, dark)
    }

fun ambientBlobs(mesh: CrystalMesh, dark: Boolean): List<Color> = when (mesh) {
    CrystalMesh.AURORA -> if (dark) AmbientBlobsDark else AmbientBlobsLight
    CrystalMesh.EMBER -> if (dark) AmbientBlobsEmberDark else AmbientBlobsEmberLight
    CrystalMesh.VERDANT -> if (dark) AmbientBlobsVerdantDark else AmbientBlobsVerdantLight
    CrystalMesh.MIST -> if (dark) AmbientBlobsMistDark else AmbientBlobsMistLight
    CrystalMesh.ROSE -> if (dark) AmbientBlobsRoseDark else AmbientBlobsRoseLight
    CrystalMesh.TIDE -> if (dark) AmbientBlobsTideDark else AmbientBlobsTideLight
    CrystalMesh.INDIGO -> if (dark) AmbientBlobsIndigoDark else AmbientBlobsIndigoLight
    CrystalMesh.ORCHID -> if (dark) AmbientBlobsOrchidDark else AmbientBlobsOrchidLight
    CrystalMesh.DUNE -> if (dark) AmbientBlobsDuneDark else AmbientBlobsDuneLight
    CrystalMesh.GRAPHITE -> if (dark) AmbientBlobsGraphiteDark else AmbientBlobsGraphiteLight
    // Solved at runtime, not declared — see `crystalCustomBlobs`. Callers should use
    // `crystalFieldBlobs`, which reads the seed from composition; this branch keeps the `when`
    // exhaustive and returns the unconfigured fallback (Aurora) for anything that cannot.
    CrystalMesh.CUSTOM -> if (dark) AmbientBlobsDark else AmbientBlobsLight
}
