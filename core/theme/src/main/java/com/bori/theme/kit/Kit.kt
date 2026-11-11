package com.bori.theme.kit

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bori.theme.components.crystalButtonColors
import com.bori.theme.components.crystalCardSurface
import com.bori.theme.components.crystalChipSurface
import com.bori.theme.components.crystalControlColor
import com.bori.theme.components.crystalControlSurface
import com.bori.theme.components.isCrystal
import com.bori.theme.util.BORI_COLOR_PRESET_COUNT
import com.bori.theme.util.CustomColorPickerDialog
import com.bori.theme.util.boriColorPalette

// Hand-written for the v2 export: the habit tracker's shared pieces that every screen is built from,
// lifted out of `ui/util/SharedComposables.kt` and `ui/settings/SettingsScreen.kt` with their bodies
// unchanged. Only what tied them to that app was cut (Hilt view models, the Pro gate).

/** A bold editorial heading (the habit tracker's screen and empty-state titles). */
@Composable
fun EditorialTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.headlineLarge,
    textAlign: TextAlign? = null,
    padding: Dp = 0.dp,
) {
    Text(
        text = text,
        style = style,
        fontWeight = FontWeight.Bold,
        color = color,
        textAlign = textAlign,
        modifier = modifier.padding(horizontal = padding),
    )
}

/**
 * The app's standard card (the habit tracker's `HabitCard`): a flat tonal card under Material 3 and
 * Expressive, a frosted floating panel under Crystal. [tint] blends a colour in at 12%, as a habit's
 * colour does there; [isDone] uses the completed wash.
 *
 * `content` renders inside a bare `Box`: wrap more than one child in a `Column`.
 */
@Composable
fun BoriCard(
    modifier: Modifier = Modifier,
    isDone: Boolean = false,
    tint: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val cardColor = when {
        isDone -> MaterialTheme.colorScheme.surfaceVariant
        tint != Color.Unspecified -> lerp(MaterialTheme.colorScheme.surfaceContainer, tint, 0.12f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Box(modifier.crystalCardSurface(fill = cardColor)) { content() }
}

/** Label above, a transparent field inside a Control-tier glass box. A blank label draws nothing. */
@Composable
fun FluidTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().crystalControlSurface()) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = singleLine,
                maxLines = maxLines,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                textStyle = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * The primary action ("Save", "Add", "Got it"): a 56dp accent pill under the Material styles, glass
 * with an accent label under Crystal (lit, not the last opaque slab on a screen of glass).
 */
@Composable
fun MomentumButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val crystal = isCrystal()
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = crystalButtonColors(),
        modifier = modifier
            .height(56.dp)
            .then(if (crystal) Modifier.crystalChipSurface(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/**
 * One collapsible block in a settings group: a card with a title row, the current value on the right
 * while collapsed, and a chevron. Collapsed by default, so a group opens to a list of names.
 */
@Composable
fun SettingsExpandableCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoriCard(modifier = modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.animateContentSize(tween(250, easing = FastOutSlowInEasing))) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (!expanded && summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                content()
            }
        }
    }
}

private val SwatchSize = 36.dp
private val SwatchGap = 12.dp

/**
 * The habit tracker's colour row: the palette button (opens the colour picker), the five vivid
 * presets, optionally the five pastels under them, then the user's saved colours once there are any.
 * Six columns, so the rows line up; circles shrink only when the widest row would not fit.
 *
 * Saved colours are the caller's to keep: [onRememberCustomColor] when one is picked in the dialog,
 * [onRemoveCustomColor] on long-press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoriColorPickerRow(
    colorIndex: Int,
    colorArgb: Int?,
    onColorSelected: (index: Int, argb: Int?) -> Unit,
    modifier: Modifier = Modifier,
    includePastels: Boolean = false,
    customColors: List<Int?> = emptyList(),
    onRememberCustomColor: (Int) -> Unit = {},
    onRemoveCustomColor: (Int) -> Unit = {},
) {
    var showCustomPicker by remember { mutableStateOf(false) }
    val isCustomSelected = colorArgb != null && customColors.none { it == colorArgb }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = maxOf(6, 1 + customColors.count { it != null })
        val swatch = minOf(SwatchSize, (maxWidth - SwatchGap * (columns - 1)) / columns)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SwatchGap)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SwatchGap)) {
                Box(
                    modifier = Modifier
                        .size(swatch)
                        .clip(CircleShape)
                        .background(
                            if (isCustomSelected) Color(colorArgb!!)
                            else crystalControlColor(MaterialTheme.colorScheme.surfaceContainerHighest),
                        )
                        .border(
                            2.dp,
                            if (isCustomSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            CircleShape,
                        )
                        .clickable { showCustomPicker = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = "Custom colour",
                        tint = if (isCustomSelected) {
                            if (Color(colorArgb!!).luminance() > 0.5f) Color.Black else Color.White
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
                boriColorPalette.take(BORI_COLOR_PRESET_COUNT).forEachIndexed { index, color ->
                    Swatch(color, swatch, selected = colorIndex == index && colorArgb == null) {
                        onColorSelected(index, null)
                    }
                }
            }
            if (includePastels) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SwatchGap)) {
                    Spacer(Modifier.size(swatch))
                    boriColorPalette.drop(BORI_COLOR_PRESET_COUNT).forEachIndexed { offset, color ->
                        val index = BORI_COLOR_PRESET_COUNT + offset
                        Swatch(color, swatch, selected = colorIndex == index && colorArgb == null) {
                            onColorSelected(index, null)
                        }
                    }
                }
            }
            val saved = customColors.withIndex().filter { it.value != null }
            if (saved.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SwatchGap)) {
                    Spacer(Modifier.size(swatch))
                    saved.forEach { (slot, argb) ->
                        val colour = argb!!
                        Box(
                            modifier = Modifier
                                .size(swatch)
                                .clip(CircleShape)
                                .background(Color(colour))
                                .combinedClickable(
                                    onClick = { onColorSelected(-1, colour) },
                                    onLongClick = { onRemoveCustomColor(slot) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (colorArgb == colour) CheckMark(Color(colour))
                        }
                    }
                }
            }
        }
    }

    if (showCustomPicker) {
        CustomColorPickerDialog(
            initialColor = colorArgb?.let { Color(it) }
                ?: boriColorPalette[colorIndex.coerceIn(0, boriColorPalette.lastIndex)],
            onColorSelected = { newColor ->
                val argb = newColor.toArgb()
                onRememberCustomColor(argb)
                onColorSelected(-1, argb)
            },
            onDismiss = { showCustomPicker = false },
        )
    }
}

@Composable
private fun Swatch(color: Color, size: Dp, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) CheckMark(color)
    }
}

@Composable
private fun CheckMark(on: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Check,
            contentDescription = "Selected",
            tint = if (on.luminance() > 0.5f) Color.Black else Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}
