package com.bori.theme.settings.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bori.theme.AmbientBaseDark
import com.bori.theme.AmbientBaseLight
import com.bori.theme.BorikenDayBase
import com.bori.theme.BorikenDayDilution
import com.bori.theme.borikenFieldBlobs
import com.bori.theme.components.BorikenUnlockDialog
import com.bori.theme.components.MESH_BLOB_DILUTION_DARK
import com.bori.theme.components.MESH_BLOB_DILUTION_LIGHT
import com.bori.theme.components.TainoGlyphs
import com.bori.theme.components.crystalRadioButtonColors
import com.bori.theme.components.crystalSegmentedButtonColors
import com.bori.theme.components.crystalSliderColors
import com.bori.theme.components.crystalSwitchColors
import com.bori.theme.components.drawCrystalMeshField
import com.bori.theme.crystalOwnFieldBlobs
import com.bori.theme.kit.BoriColorPickerRow
import com.bori.theme.kit.SettingsExpandableCard
import com.bori.theme.settings.BoriThemeSettings
import com.bori.theme.settings.CRYSTAL_BG_NEUTRAL
import com.bori.theme.settings.CrystalBackground
import com.bori.theme.settings.CrystalMesh
import com.bori.theme.settings.CrystalStyle
import com.bori.theme.settings.DarkReadability
import com.bori.theme.settings.ThemeMode
import com.bori.theme.settings.ThemeStyle
import com.bori.theme.util.CrystalCustomFieldDialog
import kotlin.math.roundToInt

// Hand-written for the v2 export: the habit tracker's Settings → Appearance cards (Theme Style,
// Crystal Background, Glass, Accent Colour, Theme), ported from `ui/settings/SettingsScreen.kt` with
// the same labels, order and rules. The view model calls became one `onChange(settings)`.

/** "Material 3", "Expressive" or "Crystal". */
fun ThemeStyle.label(): String = when (this) {
    ThemeStyle.MATERIAL3 -> "Material 3"
    ThemeStyle.EXPRESSIVE -> "Expressive"
    ThemeStyle.CRYSTAL -> "Crystal"
}

/** The display name of a Crystal field. */
fun CrystalMesh.label(): String = when (this) {
    CrystalMesh.AURORA -> "Aurora"
    CrystalMesh.EMBER -> "Ember"
    CrystalMesh.VERDANT -> "Verdant"
    CrystalMesh.MIST -> "Mist"
    CrystalMesh.ROSE -> "Rose"
    CrystalMesh.TIDE -> "Tide"
    CrystalMesh.INDIGO -> "Indigo"
    CrystalMesh.ORCHID -> "Orchid"
    CrystalMesh.DUNE -> "Dune"
    CrystalMesh.GRAPHITE -> "Graphite"
    CrystalMesh.CUSTOM -> "Custom"
}

/**
 * The whole theme picker, as the habit tracker's Appearance page shows it: one card per decision,
 * collapsed by default, one open at a time. Place it in a scrolling column; each card pads itself
 * 16dp at the sides.
 *
 * @param onChange gets the new settings on every choice; persist them.
 * @param onIntensityPreview the Glass Intensity slider while it is held (null on release), so the
 *   app can repaint live without writing to disk per frame. The final value comes through [onChange].
 * @param customColors / [onRememberCustomColor] / [onRemoveCustomColor] the user's saved colours,
 *   shared by the accent and room pickers.
 */
@Composable
fun ColumnScope.ThemeSettingsSection(
    settings: BoriThemeSettings,
    onChange: (BoriThemeSettings) -> Unit,
    onIntensityPreview: (Float?) -> Unit = {},
    customColors: List<Int?> = emptyList(),
    onRememberCustomColor: (Int) -> Unit = {},
    onRemoveCustomColor: (Int) -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    fun toggle(card: String) { open = if (open == card) null else card }
    var showCustomField by remember { mutableStateOf(false) }
    val s = settings

    SettingsExpandableCard(
        title = "Theme Style",
        expanded = open == "style",
        onToggle = { toggle("style") },
        summary = (if (s.borikenMode) "Borikén · " else "") + s.themeStyle.label(),
    ) {
        Column {
            ThemeStyle.entries.forEachIndexed { i, style ->
                val pick = { onChange(s.withBorikenMode(false).withThemeStyle(style)) }
                RadioRow(
                    selected = !s.borikenMode && s.themeStyle == style,
                    onClick = pick,
                    title = if (style == ThemeStyle.EXPRESSIVE) "Material 3 Expressive" else style.label(),
                    subtitle = when (style) {
                        ThemeStyle.MATERIAL3 -> "Google's design language, the classic Bori look"
                        ThemeStyle.EXPRESSIVE -> "Flat and bold, with rounder shapes and a punchier palette"
                        ThemeStyle.CRYSTAL -> "Frosted glass panels floating over a soft colour field"
                    },
                )
                if (i < ThemeStyle.entries.size - 1 || s.borikenUnlocked) Divider()
            }
            // Borikén appears only once it has been found. It is one option with the base style
            // chosen underneath, because it is a palette over whichever of the three the user prefers.
            if (s.borikenUnlocked) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChange(s.withBorikenMode(true)) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    RadioButton(
                        colors = crystalRadioButtonColors(),
                        selected = s.borikenMode,
                        onClick = { onChange(s.withBorikenMode(true)) },
                    )
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text("Borikén", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Celeste, red and white, with Taíno touches",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        TainoGlyphs.Sun,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                if (s.borikenMode) {
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    ) {
                        ThemeStyle.entries.forEachIndexed { idx, style ->
                            SegmentedButton(
                                colors = crystalSegmentedButtonColors(),
                                selected = s.themeStyle == style,
                                onClick = { onChange(s.withThemeStyle(style)) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = ThemeStyle.entries.size),
                                label = { Text(style.label(), maxLines = 1) },
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    if (s.themeStyle == ThemeStyle.CRYSTAL) {
        SettingsExpandableCard(
            title = "Crystal Background",
            expanded = open == "background",
            onToggle = { toggle("background") },
            summary = when {
                s.borikenMode && s.borikenField -> "Borikén"
                s.crystalBackground == CrystalBackground.MESH -> s.crystalMesh.label()
                else -> "Solid"
            },
        ) {
            Column {
                RadioRow(
                    selected = s.crystalBackground == CrystalBackground.MESH,
                    onClick = { onChange(s.withCrystalBackground(CrystalBackground.MESH)) },
                    title = "Colour Field",
                    subtitle = "A soft field of colour for the glass to reveal",
                )
                Box(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .alpha(if (s.crystalBackground == CrystalBackground.MESH) 1f else 0.45f),
                ) {
                    CrystalMeshPickerRow(
                        selected = s.crystalMesh,
                        onMeshSelected = {
                            onChange(s.withCrystalBackground(CrystalBackground.MESH).withCrystalMesh(it))
                        },
                        onCustomRequested = { showCustomField = true },
                        onBorikenSelected = if (s.borikenMode) {
                            { onChange(s.withBorikenFieldSelected()) }
                        } else null,
                        borikenSelected = s.borikenMode && s.borikenField,
                    )
                }
                Divider()
                val solid = s.crystalBackground == CrystalBackground.SOLID
                RadioRow(
                    selected = solid,
                    onClick = { onChange(s.withCrystalBackground(CrystalBackground.SOLID)) },
                    title = "Solid",
                    subtitle = "One flat colour, no gradient at all",
                )
                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .alpha(if (solid) 1f else 0.45f),
                ) {
                    val neutral = { onChange(s.withCrystalBackground(CrystalBackground.SOLID).withCrystalRoom(CRYSTAL_BG_NEUTRAL)) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = neutral).padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            colors = crystalRadioButtonColors(),
                            selected = solid && s.crystalRoomIndex == CRYSTAL_BG_NEUTRAL && s.crystalRoomArgb == null,
                            onClick = neutral,
                        )
                        Text("Neutral", style = MaterialTheme.typography.bodyMedium)
                    }
                    BoriColorPickerRow(
                        colorIndex = s.crystalRoomIndex.coerceAtLeast(0),
                        colorArgb = s.crystalRoomArgb,
                        includePastels = true,
                        onColorSelected = { idx, argb ->
                            onChange(s.withCrystalBackground(CrystalBackground.SOLID).withCrystalRoom(idx, argb))
                        },
                        customColors = customColors,
                        onRememberCustomColor = onRememberCustomColor,
                        onRemoveCustomColor = onRemoveCustomColor,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsExpandableCard(
            title = "Glass",
            expanded = open == "glass",
            onToggle = { toggle("glass") },
            summary = if (s.crystalStyle == CrystalStyle.SHEER) "Sheer" else "Tinted",
        ) {
            Column {
                CrystalStyle.entries.forEach { style ->
                    RadioRow(
                        selected = s.crystalStyle == style,
                        onClick = { onChange(s.withCrystalStyle(style)) },
                        title = if (style == CrystalStyle.SHEER) "Sheer" else "Tinted",
                        subtitle = if (style == CrystalStyle.SHEER) "More transparent, more of the colour field shows through"
                        else "More opaque, easier to read, with richer depth",
                    )
                    Divider()
                }
                // Where the glass sits inside the band the preset defines. 50% is the look before the
                // control existed. Live while held (onIntensityPreview), saved once on release.
                var dragging by remember { mutableStateOf(false) }
                var held by remember { mutableFloatStateOf(s.crystalIntensity) }
                val shown = if (dragging) held else s.crystalIntensity
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Glass Intensity",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(shown * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        colors = crystalSliderColors(),
                        value = shown,
                        onValueChange = { dragging = true; held = it; onIntensityPreview(it) },
                        onValueChangeFinished = {
                            dragging = false
                            onChange(s.withCrystalIntensity(held))
                            onIntensityPreview(null)
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "Clearer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Frostier",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    SettingsExpandableCard(
        title = "Accent Colour",
        expanded = open == "accent",
        onToggle = { toggle("accent") },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Choose a primary colour for the whole app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            BoriColorPickerRow(
                colorIndex = s.accentColorIndex,
                colorArgb = s.accentColorArgb,
                includePastels = true,
                onColorSelected = { idx, argb -> onChange(s.withAccent(idx, argb)) },
                customColors = customColors,
                onRememberCustomColor = onRememberCustomColor,
                onRemoveCustomColor = onRemoveCustomColor,
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    SettingsExpandableCard(
        title = "Theme",
        expanded = open == "mode",
        onToggle = { toggle("mode") },
        summary = when (s.themeMode) {
            ThemeMode.SYSTEM -> "System default"
            ThemeMode.LIGHT -> "Light"
            ThemeMode.DARK -> "Dark"
        },
    ) {
        Column {
            ThemeMode.entries.forEachIndexed { i, mode ->
                RadioRow(
                    selected = s.themeMode == mode,
                    onClick = { onChange(s.withThemeMode(mode)) },
                    title = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                )
                if (i < ThemeMode.entries.size - 1) Divider()
            }
            // Drives materialkolor's contrastLevel, which Crystal's fixed palette never goes near, so
            // it is hidden there rather than left to be tried and disbelieved.
            if (s.themeStyle != ThemeStyle.CRYSTAL) {
                Divider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Night Readability", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Controls how much surface contrast is added in dark mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        DarkReadability.entries.forEachIndexed { idx, option ->
                            SegmentedButton(
                                colors = crystalSegmentedButtonColors(),
                                selected = s.darkReadability == option,
                                onClick = { onChange(s.withDarkReadability(option)) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = DarkReadability.entries.size),
                                label = {
                                    Text(
                                        when (option) {
                                            DarkReadability.STANDARD -> "Standard"
                                            DarkReadability.COMFORTABLE -> "Comfortable"
                                            DarkReadability.HIGH -> "High"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            Divider()
            val darkActive = s.themeMode == ThemeMode.DARK || (s.themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme())
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "AMOLED Black",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (darkActive) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (darkActive) "True black backgrounds, saves battery on OLED screens"
                        else "Available when the dark theme is active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    colors = crystalSwitchColors(),
                    checked = s.amoled,
                    enabled = darkActive,
                    onCheckedChange = { onChange(s.withAmoled(it)) },
                )
            }
        }
    }

    if (showCustomField) {
        CrystalCustomFieldDialog(
            initialColors = s.crystalMeshCustomColors.map { Color(it) }
                .ifEmpty { listOfNotNull(s.crystalMeshCustomArgb?.let { Color(it) }) },
            initialChroma = s.crystalMeshCustomChroma,
            onSave = { colors, chroma ->
                onChange(
                    s.withCrystalBackground(CrystalBackground.MESH)
                        .withCrystalMesh(CrystalMesh.CUSTOM)
                        .withCrystalMeshCustom(colors.map { it.toArgb() }, chroma),
                )
            },
            onDismiss = { showCustomField = false },
        )
    }
}

/**
 * The hidden Borikén edition's unlock: call [onTap] from the app's version line. Ten taps within
 * three seconds of each other unlock it and show the habit tracker's unlock dialog.
 */
class BorikenUnlockCounter {
    private var taps = 0
    private var last = 0L

    /** True on the tap that unlocks. */
    fun onTap(now: Long = System.currentTimeMillis()): Boolean {
        taps = if (now - last > 3_000) 1 else taps + 1
        last = now
        if (taps >= 10) { taps = 0; return true }
        return false
    }
}

/** The unlock dialog; "Try it" turns Borikén on. */
@Composable
fun BorikenUnlock(settings: BoriThemeSettings, onChange: (BoriThemeSettings) -> Unit, onDismiss: () -> Unit) {
    BorikenUnlockDialog(
        onDismiss = { onChange(settings.withBorikenUnlocked()); onDismiss() },
        onTryIt = { onChange(settings.withBorikenMode(true)); onDismiss() },
    )
}

@Composable
private fun RadioRow(selected: Boolean, onClick: () -> Unit, title: String, subtitle: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        RadioButton(colors = crystalRadioButtonColors(), selected = selected, onClick = onClick)
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

private const val MESH_SWATCHES_PER_ROW = 5

/**
 * The colour fields, each drawn as itself (the same [drawCrystalMeshField] the real field uses), five
 * to a row: ten across the Fold's cover screen would be 21dp circles. Short last rows are padded.
 */
@Composable
fun CrystalMeshPickerRow(
    selected: CrystalMesh,
    onMeshSelected: (CrystalMesh) -> Unit,
    onCustomRequested: () -> Unit = {},
    onBorikenSelected: (() -> Unit)? = null,
    borikenSelected: Boolean = false,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val base = if (dark) AmbientBaseDark else AmbientBaseLight
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val swatches: List<CrystalMesh?> =
            if (onBorikenSelected != null) listOf(null) + CrystalMesh.entries else CrystalMesh.entries
        swatches.chunked(MESH_SWATCHES_PER_ROW).forEach { rowMeshes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowMeshes.forEach { mesh ->
                    val isSelected = if (mesh == null) borikenSelected else !borikenSelected && mesh == selected
                    val blobs = if (mesh == null) borikenFieldBlobs(dark) else crystalOwnFieldBlobs(mesh, dark)
                    val swatchBase = if (mesh == null && !dark) BorikenDayBase else base
                    val swatchDilution = when {
                        dark -> MESH_BLOB_DILUTION_DARK
                        mesh == null -> BorikenDayDilution
                        else -> MESH_BLOB_DILUTION_LIGHT
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .drawBehind { drawCrystalMeshField(swatchBase, blobs, dark, dilution = swatchDilution) }
                                .border(
                                    2.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    CircleShape,
                                )
                                .clickable {
                                    when (mesh) {
                                        null -> onBorikenSelected?.invoke()
                                        CrystalMesh.CUSTOM -> onCustomRequested()
                                        else -> onMeshSelected(mesh)
                                    }
                                },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (mesh == null) "Borikén" else mesh.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                repeat(MESH_SWATCHES_PER_ROW - rowMeshes.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
