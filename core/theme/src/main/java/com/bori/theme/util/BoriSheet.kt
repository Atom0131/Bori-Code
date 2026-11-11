package com.bori.theme.util

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.bori.theme.components.CrystalWindowContent
import com.bori.theme.components.crystalMenuContainerColor
import com.bori.theme.components.crystalMenuField
import com.bori.theme.components.isCrystal
import com.bori.theme.components.crystalControlElevation
import com.bori.theme.components.crystalScaffoldColor
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.window.DialogProperties

/**
 * The one and only bottom sheet for this app — every sheet must go through this wrapper so
 * dismissal behavior can never drift per-screen again:
 *
 * - Content gestures belong to the content. Material3 1.3.x has no `sheetGesturesEnabled`
 *   switch, so the content is wrapped with (a) a nested-scroll blocker that swallows the
 *   leftover scroll/fling once an inner LazyColumn/verticalScroll hits its boundary, and (b) a
 *   dead-zone drag consumer that eats vertical drags on non-scrollable areas — so neither can
 *   ever reach the sheet's own swipe-to-dismiss and wipe in-progress work.
 * - The ONLY swipe-to-dismiss zone is the drag-handle strip at the top; scrim tap and system
 *   back remain as deliberate exits. Note how that is achieved: M3 puts its `draggable` on the
 *   whole sheet `Surface`, so the strip is not opted *in* — everything else is opted *out*, by
 *   consuming. [SheetDismissHandle] therefore installs no gesture detector of its own, and its
 *   KDoc explains why adding one breaks the drag without looking like it.
 * - Top insets (`contentWindowInsets`) reserve the status bar + display cutout, so the handle
 *   is never hidden under a punch-hole camera (Fold 6 outer display) on any screen size.
 *   Bottom/side insets are padded INSIDE the blocker column instead — leaving them on the
 *   sheet would expose a bare strip of surface at the very bottom where M3's built-in
 *   swipe-to-dismiss still works (the v1.79 leak).
 *
 * Sheets that want full height must pass [fillHeight] = true (NOT `Modifier.fillMaxHeight()`):
 * the blocker column then fills the whole surface, so empty space below short content is
 * shielded too — the other half of the v1.79 leak.
 *
 * **Content must own a scrollable** — a `verticalScroll` or a `LazyColumn`. This wrapper does not
 * provide one, and cannot: sheets that host a lazy list would nest badly. A sheet whose content
 * outgrows the viewport with no scrollable is not merely clipped, it is **inert**: with nothing to
 * consume the gesture in the Main pass, every vertical drag falls through to the dead-zone consumer
 * above and is eaten, so the sheet neither scrolls nor dismisses. Two editors (`AddTodoSheet`,
 * `AddReminderSheet`) shipped that way, because the guarantees listed above read as though
 * scrolling were handled for you. It isn't. Where a save button must stay reachable, give the
 * scroll region `Modifier.weight(1f)` and pin the button below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoriSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    // Transparent under Crystal, because the field drawn inside now covers the whole sheet including
    // the handle strip — see the dragHandle = null below. It used to be crystalSheetColor(...), one
    // flat colour standing in for a diagonal gradient, which is what put a mint band across the top
    // of every sheet. Under the flat themes CrystalWindowContent draws nothing, so the Material
    // default still has to paint the sheet.
    containerColor: Color =
        if (isCrystal()) Color.Transparent else BottomSheetDefaults.ContainerColor,
    // `contentColorFor` returns Color.Unspecified for anything that isn't a Material role, and under
    // Crystal the container above is a raw gradient stop — so the default would publish Unspecified
    // as LocalContentColor for the whole sheet and every uncoloured Text would render black. Same
    // trap as the transparent Scaffolds; see crystalScaffoldContentColor.
    contentColor: Color = contentColorFor(containerColor).takeOrElse {
        MaterialTheme.colorScheme.onSurface
    },
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = if (fillHeight) modifier.fillMaxHeight() else modifier,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = {
            WindowInsets.statusBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Top)
        },
        // No handle slot. M3 lays that slot out ABOVE the content, outside anything the content can
        // draw into, so a field installed below it can never reach the strip — the whole reason the
        // container was being painted a flat colour in the first place. The handle is drawn inside
        // the field below instead, where it sits on the gradient like every other element.
        dragHandle = null
    ) {
        // A ModalBottomSheet renders in its own window, so the app's ambient field is not reachable
        // from in here — a panel inside would inherit a HazeState pointing at a source in another
        // window. CrystalWindowContent gives this window its own field and its own blur source
        // instead, so the controls in every sheet are real glass. Severing the source without
        // replacing it was the first attempt, and it made every sheet a flat near-black slab.
        // One edit, every sheet in the app.
        CrystalWindowContent {
          // Unlike BoriAlertDialog's BasicAlertDialog, ModalBottomSheet does bound its own
          // window (confirmed against material3 1.4.0 with javap: no sheetMaxWidth parameter
          // exists in this version either, but the sheet Surface itself is sized by the modal's
          // own layout to the screen width) - so on a wide unfolded screen the content column
          // below still stretches edge-to-edge, spreading button rows (fillMaxWidth+SpaceBetween
          // rows land their children at opposite far edges; fixed-dp clusters end up centered
          // with huge dead margins). Same cap as BoriAlertDialog, measured off the screen
          // rather than BoxWithConstraints for the same reason documented there: inside this
          // window BoxWithConstraints reports the already-content-sized constraint, not the
          // screen, and would double-count the margin.
          val screenWidth = with(LocalDensity.current) {
              LocalContext.current.resources.displayMetrics.widthPixels.toDp()
          }
          val sheetWidthCap = (screenWidth - DialogSideMargin * 2).coerceAtMost(DialogMaxWidth)
          Column(
              // align(CenterHorizontally) resolves against the ModalBottomSheet's own ColumnScope,
              // still in lexical scope through this nested CrystalWindowContent lambda - without it
              // a narrower, capped column would stick to the sheet's left edge instead of centering.
              modifier = (if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                  .widthIn(max = sheetWidthCap)
                  .align(Alignment.CenterHorizontally)
          ) {
            // The handle lives HERE, not in the sheet's slot, and it must stay above the Column
            // below rather than inside it. That Column installs a dead-zone
            // `detectVerticalDragGestures` which *consumes*; a handle inside it would swallow the
            // drag before ModalBottomSheet's own `anchoredDraggable` on the Surface ever saw it, and
            // the sheet would sit frozen under the thumb. That is the same failure this file's KDoc
            // records from the other direction, when SheetDismissHandle hand-rolled its own
            // detector. Out here it consumes nothing, so the sheet drags exactly as before.
            SheetDismissHandle()
            Column(
                modifier = (if (fillHeight) Modifier.weight(1f) else Modifier.fillMaxWidth())
                    .nestedScroll(BoundaryOverscrollBlocker)
                    // Children (scrollables, text fields, pickers) consume their gestures first in
                    // the Main pass; this only eats what's left, i.e. drags on dead zones.
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, _ -> change.consume() }
                    }
                    // Bottom/side insets padded here, AFTER the blockers, so the inset strip is
                    // part of the shielded area rather than bare sheet surface.
                    .windowInsetsPadding(
                        WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    ),
                content = content
            )
          }
        }
    }
}

/**
 * A dropdown menu wearing Crystal — the three-dot menus, the block menus, the habit picker.
 *
 * **A menu is a popup, and a popup is a separate window, so it has exactly the problem sheets had.**
 * `BoriSheet` and `RhythmDialPickerDialog` were given `CrystalWindowContent` early because they
 * were obviously windows; a `DropdownMenu` is one too and was never treated as such, so all seven in
 * the app rendered as flat Material slabs on top of a Crystal app — the Edit/lock/delete menu on a
 * notebook and the Pin/Move/Share/Delete menu on a note, both reported as unconverted.
 *
 * The **content** is wrapped in [CrystalWindowContent] so the window gets the denser in-window
 * surface roles and an anchored content colour; without it the items would sample a `HazeState`
 * pointing at a surface in another window and fall back to flat fills.
 *
 * **The field is supplied through the `modifier`, not the content slot, and that is the fix for the
 * band.** `DropdownMenu` wraps its items in `DropdownMenuVerticalPadding`, a private 8dp it exposes
 * no parameter for. Painting that strip with a flat container colour cannot work — it was
 * `crystalSheetColor`, the panel gradient's first stop, which is the field's colour at its top-left
 * corner *only*, so a diagonal field diverged from it across the width and the menu showed a teal
 * band at **both** ends (measured `rgb(34,76,88)` against the computed stop's `rgb(29,78,90)`).
 *
 * The previous attempt bled the field 8dp past the content, and **that could never have worked**:
 * M3 builds the content as `modifier.padding(...).width(...).verticalScroll(...)`, so a *clipping*
 * scroll container sits between the padding and us and discards the bleed. The modifier passed to
 * `DropdownMenu` is applied before both, which is why [crystalMenuField] belongs there and why the
 * container colour is now transparent under a mesh. Both are no-ops off Crystal and under Solid,
 * where the flat container already is the field.
 *
 * `shape`/`containerColor`/`tonalElevation`/`shadowElevation` are real parameters of M3's
 * `DropdownMenu` here — confirmed against material3 1.5.0-alpha17 by disassembly rather than assumed,
 * since this project resolves a different version from the one the docs describe.
 */
@Composable
fun BoriDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // **Not composed at all until it has been opened once.** Every notebook and note card carries
    // one of these, and a closed `DropdownMenu` still builds its transition state, and
    // `crystalMenuField` still does its own work, on every card as it scrolls into view — work for a
    // menu that is opened on one card at most. Once opened it stays composed, so closing still plays
    // M3's exit animation.
    var opened by remember { mutableStateOf(false) }
    if (expanded) opened = true
    if (!opened) return
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        // The field goes HERE, not in the content slot. M3 applies this modifier first and only then
        // its own padding and its clipping scroll container, so this is the one place a background
        // can reach the menu's 8dp vertical padding. See [CrystalWindowContent].
        modifier = modifier.crystalMenuField(),
        shape = MaterialTheme.shapes.medium,
        // Transparent under a mesh so the field above shows through the padding strips instead of a
        // flat first-stop teal. Unchanged off Crystal and under Solid.
        containerColor = crystalMenuContainerColor(MaterialTheme.colorScheme.surfaceContainer),
        // Same reason the icon chips lost theirs: a shadow's tessellated outline shows through a
        // translucent fill as a polygon. Kept under the flat themes and under Solid.
        shadowElevation = crystalControlElevation(3.dp),
        tonalElevation = crystalControlElevation(3.dp)
    ) {
        // `paintField = false`: the field is already painted, one box larger, by the modifier above.
        CrystalWindowContent(paintField = false) {
            Column(content = content)
        }
    }
}


/**
 * The app's alert dialog. Same slots as M3's `AlertDialog`, so converting a call site is a rename.
 *
 * **Built on `BasicAlertDialog` rather than `AlertDialog` because the field has to go *inside* the
 * window.** M3's `AlertDialog` owns its own `Surface` and exposes only a `containerColor`, so the
 * best it can do is a flat tint — the same dead end the dial picker was in. `BasicAlertDialog` hands
 * over the window and nothing else, which is exactly what is needed: this can then paint a real
 * panel field behind the content, the way every sheet does.
 *
 * **The clip is on the outside, and that is load-bearing** — the same lesson as
 * `RhythmDialPickerDialog`. `CrystalWindowContent` draws its field with `matchParentSize` and no
 * shape of its own, so a transparent Surface inside a rounded dialog squares off the corners. The
 * `Box` bounds the field to the shape first, which frees the Surface to be transparent and lets the
 * gradient through.
 *
 * Layout follows the Material spec the call sites were written against — 24dp padding, centred icon,
 * `titleLarge` title, `bodyMedium` body, actions end-aligned with dismiss before confirm — so
 * swapping the name does not move anything on screen.
 *
 * [containerColor] and [shape] are accepted so existing call sites that set them keep compiling;
 * under Crystal the container is deliberately transparent and the field shows instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoriAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    containerColor: Color = Color.Unspecified,
    properties: DialogProperties = DialogProperties()
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties
    ) {
        // The cap is measured off the SCREEN, not off the incoming constraint. `BoxWithConstraints`
        // was tried first and is wrong here: inside a dialog its `maxWidth` is the *window's*
        // constraint, which the platform has already sized towards the content — so subtracting the
        // margin from it double-counts and pulls every dialog in, including the ones that were
        // already correct (`Discard changes?` measured 840px -> 736px on a Fold 6 cover screen).
        val screenWidth = with(LocalDensity.current) {
            LocalContext.current.resources.displayMetrics.widthPixels.toDp()
        }
        val widthCap = (screenWidth - DialogSideMargin * 2).coerceAtMost(DialogMaxWidth)
        Box(modifier = Modifier.widthIn(max = widthCap).clip(shape)) {
                CrystalWindowContent {
                    Surface(
                        shape = shape,
                        color = crystalScaffoldColor(
                            containerColor.takeOrElse { MaterialTheme.colorScheme.surfaceContainerHigh }
                        ),
                        // Transparent is not a Material role, so contentColorFor returns Unspecified
                        // and every uncoloured Text in the dialog would render black. Same trap as
                        // the transparent Scaffolds and the dial picker.
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 6.dp
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            if (icon != null) {
                                Box(Modifier.align(Alignment.CenterHorizontally)) { icon() }
                                Spacer(Modifier.height(16.dp))
                            }
                            if (title != null) {
                                ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                                Spacer(Modifier.height(16.dp))
                            }
                            if (text != null) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                                Spacer(Modifier.height(24.dp))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (dismissButton != null) {
                                    dismissButton()
                                    Spacer(Modifier.size(8.dp))
                                }
                                confirmButton()
                            }
                        }
                    }
                }
            }
        }
    }

/**
 * The margin an alert dialog keeps from each screen edge, and the width it will not exceed.
 *
 * **These exist because `BasicAlertDialog` constrains nothing.** M3's `AlertDialog` bounds its own
 * content (`DialogMinWidth` 280dp / `DialogMaxWidth` 560dp, read out of the material3 1.5.0-alpha17
 * bytecode); `BasicAlertDialog` hands over the window and leaves sizing entirely to the caller, which
 * is the price of being able to paint a field inside it. The values are mirrored rather than imported
 * because material3 declares them `internal`.
 *
 * **The failure this prevents**, measured on a Fold 6 cover screen (968px, 420dpi) on 2026-08-20: the
 * dialog window is `WRAP_CONTENT` between a platform *minimum* (~86.8% of the screen there) and a
 * *maximum* of the screen width, and [BoriAlertDialog]'s own action `Row` fills the width — so the
 * painted panel is always exactly the window. A dialog whose content is plain text stays under the
 * minimum and sits at the floor, looking correct; a dialog containing **any `fillMaxWidth()` child**
 * asks for the whole screen, hits the ceiling, and renders edge-to-edge with no margin at all,
 * reading as a bottom sheet rather than a dialog. `Wake-up Check` measured 0–2dp of margin against
 * `Discard changes?`'s 24.4dp, and 8 of the 21 call sites have fill-width content.
 *
 * Capping the *content* rather than adding padding is deliberate: padding would also pull the 13
 * well-behaved dialogs in off the platform floor, changing screens that are already correct. A cap
 * only binds on the ones that would otherwise overflow.
 */
private val DialogSideMargin = 24.dp
private val DialogMaxWidth = 560.dp

/** Swallows scroll/fling left over after inner scrollables hit their boundary, so top-edge
 * overscroll never bubbles into the sheet's nested-scroll-driven swipe-to-dismiss. */
private val BoundaryOverscrollBlocker = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/**
 * Full-width grab strip with the standard pill. Dragging it moves the sheet with the thumb and,
 * on release, settles back open or carries on out at the speed it was flicked; anywhere else on
 * the sheet, vertical drags stay with the content.
 *
 * **This composable must not install a gesture detector, and that is the whole point of it.** None
 * of the drag behaviour above is ours — `ModalBottomSheet` builds one modifier chain
 * (`nestedScroll` → `draggableAnchors` → `draggable(onDragStopped = settleToDismiss)`) and hands it
 * to the sheet `Surface`, so the drag already lives on the whole sheet with this strip inside it.
 * The strip works by *not consuming*, letting that parent modifier run, exactly as the content
 * column works by consuming so it doesn't.
 *
 * It used to hand-roll `detectVerticalDragGestures`, accumulate the drag and call `hide()` past a
 * 56dp threshold — so the sheet stayed frozen under the thumb and then played the flat hide spec
 * from fully open, and no flick could dismiss it short of that distance. The reason it blocked
 * rather than merely duplicated: `detectVerticalDragGestures` **consumes the pointer once touch
 * slop is crossed**, and a child consuming in the Main pass means the parent's `draggable` never
 * sees the gesture. Attaching any detector here silently disables thumb tracking again, and it
 * looks like a missing animation rather than a swallowed gesture.
 */
@Composable
private fun SheetDismissHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}
