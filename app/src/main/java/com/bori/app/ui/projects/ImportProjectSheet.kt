package com.bori.app.ui.projects

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportProjectSheet(
    importState: ImportState,
    pickedZipUri: Uri?,
    pickedZipName: String?,
    onPickZip: () -> Unit,
    onImportGitHub: (url: String, token: String) -> Unit,
    onImportZip: (uri: Uri) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val isLoading = importState is ImportState.Loading

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Import Project", style = MaterialTheme.typography.titleMedium)

            var selectedTab by rememberSaveable { mutableIntStateOf(0) }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("GitHub", "ZIP File").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                        label = { Text(label) },
                    )
                }
            }

            when (selectedTab) {
                0 -> GitHubTab(isLoading = isLoading, onImport = onImportGitHub)
                1 -> ZipTab(
                    isLoading = isLoading,
                    pickedZipUri = pickedZipUri,
                    pickedZipName = pickedZipName,
                    onPickZip = onPickZip,
                    onImport = { uri -> onImportZip(uri) },
                )
            }

            if (importState is ImportState.Error) {
                Text(
                    text = importState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GitHubTab(
    isLoading: Boolean,
    onImport: (url: String, token: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var privateRepo by rememberSaveable { mutableStateOf(false) }
    var token by rememberSaveable { mutableStateOf("") }

    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Repository URL") },
        placeholder = { Text("https://github.com/owner/repo") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !isLoading,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Private repository", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = privateRepo, onCheckedChange = { privateRepo = it }, enabled = !isLoading)
    }

    if (privateRepo) {
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Personal Access Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isLoading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }

    Button(
        onClick = { onImport(url.trim(), token.trim()) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading && url.isNotBlank(),
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text("Clone Repository")
        }
    }
}

@Composable
private fun ZipTab(
    isLoading: Boolean,
    pickedZipUri: Uri?,
    pickedZipName: String?,
    onPickZip: () -> Unit,
    onImport: (Uri) -> Unit,
) {
    OutlinedButton(
        onClick = onPickZip,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(pickedZipName ?: "Choose ZIP file…")
    }

    Button(
        onClick = { pickedZipUri?.let { onImport(it) } },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading && pickedZipUri != null,
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text("Import ZIP")
        }
    }
}
