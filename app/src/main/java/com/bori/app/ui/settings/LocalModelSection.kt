package com.bori.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bori.core.ai.local.DeviceSpecs
import com.bori.core.ai.local.DownloadState
import com.bori.core.ai.local.FitRating
import com.bori.core.ai.local.LocalModel
import com.bori.core.ai.local.ModelFit

@Composable
fun LocalModelSection(
    viewModel: LocalModelViewModel = hiltViewModel(),
) {
    val state by viewModel.downloadState.collectAsState()
    val selected by viewModel.selectedModel.collectAsState()
    val specs by viewModel.specs.collectAsState()
    val fits by viewModel.fits.collectAsState()
    val isDownloading = state is DownloadState.Downloading
    // A model the advisor says won't fit waits here until the user confirms
    var confirmModel by remember { mutableStateOf<ModelFit?>(null) }
    val choose: (LocalModel) -> Unit = { model ->
        val fit = fits.firstOrNull { it.model == model }
        if (fit?.rating == FitRating.NOT_ENOUGH_RAM) confirmModel = fit else viewModel.selectModel(model)
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SuggestionChip(onClick = {}, label = { Text("${selected.label} · ${selected.sizeLabel}") })
        }

        Spacer(Modifier.height(4.dp))

        Text(
            "Runs entirely on-device with no internet connection required. " +
                "Speed depends on your device hardware. " +
                "Requires ${selected.sizeLabel} of free storage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        ModelAdvisorCard(
            specs = specs,
            fits = fits,
            selected = selected,
            selectEnabled = !isDownloading,
            onUse = choose,
            onSetManual = viewModel::setManualSpecs,
            onUseScan = viewModel::clearManualSpecs,
        )

        // Model choice — locked while a download runs so the progress stays with its model
        Row(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            LocalModel.entries.forEach { model ->
                FilterChip(
                    selected = model == selected,
                    onClick = { choose(model) },
                    enabled = !isDownloading,
                    label = {
                        Text(model.label.removePrefix("Gemma 4 ") + if (viewModel.isDownloaded(model)) " ✓" else "")
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        Text(
            selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        confirmModel?.let { fit ->
            AlertDialog(
                onDismissRequest = { confirmModel = null },
                title = { Text("${fit.model.label} may not run") },
                text = { Text("${fit.reason} It may fail to load or make the phone very slow.") },
                confirmButton = {
                    TextButton(onClick = { viewModel.selectModel(fit.model); confirmModel = null }) { Text("Use anyway") }
                },
                dismissButton = { TextButton(onClick = { confirmModel = null }) { Text("Cancel") } },
            )
        }

        val uriHandler = LocalUriHandler.current
        var tokenVisible by remember { mutableStateOf(false) }
        var tokenText by remember { mutableStateOf(viewModel.getHfToken()) }

        OutlinedTextField(
            value = tokenText,
            onValueChange = { tokenText = it; viewModel.setHfToken(it) },
            label = { Text("HuggingFace Token") },
            placeholder = { Text("hf_xxxxxxxxxxxxxxxx") },
            supportingText = {
                Text("Optional. Only needed if you hit rate limits or are accessing a private variant.")
            },
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { uriHandler.openUri("https://huggingface.co/settings/tokens") },
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Text("Get a HuggingFace token (optional) →", style = MaterialTheme.typography.bodySmall)
        }

        when (val s = state) {
            is DownloadState.Idle -> {
                Button(
                    onClick = viewModel::startDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Download Model")
                }
            }

            is DownloadState.Downloading -> {
                val percent = s.progressPercent
                val downloaded = formatBytes(s.bytesDownloaded)
                val total = if (s.bytesTotal > 0) formatBytes(s.bytesTotal) else "~${selected.sizeLabel}"

                Text(
                    "Downloading… $percent% ($downloaded / $total)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                LinearProgressIndicator(
                    progress = { if (s.bytesTotal > 0) percent / 100f else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::cancelDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }

            is DownloadState.Ready -> {
                val sizeStr = formatBytes(viewModel.storageBytes())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Model ready  •  $sizeStr on disk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = viewModel::deleteModel,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete")
                    }
                }
            }

            is DownloadState.Error -> {
                Text(
                    "Error: ${s.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Button(
                    onClick = viewModel::startDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retry Download")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}

/** "Recommended for this phone": the specs, each model's fit, and manual spec entry. */
@Composable
private fun ModelAdvisorCard(
    specs: DeviceSpecs,
    fits: List<ModelFit>,
    selected: LocalModel,
    selectEnabled: Boolean,
    onUse: (LocalModel) -> Unit,
    onSetManual: (name: String, ramGb: Double, freeStorageGb: Double) -> Unit,
    onUseScan: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Recommended for this phone", style = MaterialTheme.typography.titleSmall)
            Text(
                text = specsLine(specs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            fits.forEach { fit ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fit.model.label + "  ·  " + fit.model.sizeLabel + if (fit.isBestPick) "  ·  Best pick" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (fit.isBestPick) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            text = ratingLabel(fit.rating) + " — " + fit.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (fit.rating) {
                                FitRating.RECOMMENDED -> MaterialTheme.colorScheme.primary
                                FitRating.SLOW -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    if (fit.model == selected) {
                        Text("In use", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
                    } else {
                        TextButton(onClick = { onUse(fit.model) }, enabled = selectEnabled) { Text("Use this") }
                    }
                }
            }

            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { editing = !editing }) {
                    Text(if (editing) "Hide" else "Enter specs manually")
                }
                if (specs.manual) TextButton(onClick = { onUseScan(); editing = false }) { Text("Use scanned specs") }
            }

            if (editing) ManualSpecsForm(specs = specs, onSave = { name, ram, storage -> onSetManual(name, ram, storage); editing = false })
        }
    }
}

@Composable
private fun ManualSpecsForm(specs: DeviceSpecs, onSave: (String, Double, Double) -> Unit) {
    var name by remember { mutableStateOf(if (specs.manual) specs.name else "") }
    var ram by remember { mutableStateOf(if (specs.manual) "%.0f".format(specs.ramGb) else "") }
    var storage by remember { mutableStateOf(if (specs.manual) "%.0f".format(specs.freeStorageGb) else "") }
    val ramValue = ram.toDoubleOrNull()?.takeIf { it in 1.0..64.0 }
    val storageValue = storage.toDoubleOrNull()?.takeIf { it >= 0.0 }

    Column {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Phone (e.g. Pixel 9)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = ram, onValueChange = { ram = it },
                label = { Text("RAM (GB)") }, singleLine = true,
                isError = ram.isNotEmpty() && ramValue == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = storage, onValueChange = { storage = it },
                label = { Text("Free storage (GB)") }, singleLine = true,
                isError = storage.isNotEmpty() && storageValue == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "RAM is in your phone's Settings → About phone. Free storage is under Settings → Storage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(
            onClick = { onSave(name, ramValue ?: return@Button, storageValue ?: return@Button) },
            enabled = ramValue != null && storageValue != null,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Recommend for these specs") }
    }
}

private fun specsLine(specs: DeviceSpecs): String {
    if (specs.ramGb <= 0.0) return "Checking this phone…"
    return buildList {
        add(specs.name.ifBlank { "This phone" })
        add("%.0f GB RAM".format(specs.ramGb))
        specs.chip?.let { add(it) }
        add("%.0f GB free".format(specs.freeStorageGb))
        if (specs.manual) add("entered manually")
    }.joinToString("  ·  ")
}

private fun ratingLabel(rating: FitRating): String = when (rating) {
    FitRating.RECOMMENDED -> "Recommended"
    FitRating.SLOW -> "Will run, slower"
    FitRating.NOT_ENOUGH_RAM -> "Not enough RAM"
    FitRating.NOT_ENOUGH_STORAGE -> "Not enough storage"
}
