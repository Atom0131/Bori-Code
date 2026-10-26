package com.bori.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bori.core.project.model.ProjectTemplate

private val MIN_SDK_OPTIONS = listOf(26, 28, 29, 30, 31, 33, 34, 35)
private val PACKAGE_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, packageName: String, minSdk: Int, template: ProjectTemplate) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("com.example.myapp") }
    var minSdk by remember { mutableIntStateOf(26) }
    var template by remember { mutableStateOf(ProjectTemplate.BASIC_ACTIVITY_XML) }
    var sdkDropdownExpanded by remember { mutableStateOf(false) }

    val nameError = name.isBlank()
    val packageError = !PACKAGE_REGEX.matches(packageName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("App Name") },
                    isError = nameError && name.isNotEmpty(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Package Name") },
                    isError = packageError && packageName.isNotEmpty(),
                    supportingText = if (packageError && packageName.isNotEmpty()) {
                        { Text("Must be like com.example.myapp") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Min SDK picker
                ExposedDropdownMenuBox(
                    expanded = sdkDropdownExpanded,
                    onExpandedChange = { sdkDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = "API $minSdk",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Min SDK") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sdkDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = sdkDropdownExpanded,
                        onDismissRequest = { sdkDropdownExpanded = false },
                    ) {
                        MIN_SDK_OPTIONS.forEach { sdk ->
                            DropdownMenuItem(
                                text = { Text("API $sdk") },
                                onClick = {
                                    minSdk = sdk
                                    sdkDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // Template selector
                Text("Template")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    TemplateChip(
                        selected = template == ProjectTemplate.BASIC_ACTIVITY_XML,
                        onClick = { template = ProjectTemplate.BASIC_ACTIVITY_XML },
                        label = "Basic Activity (Java)"
                    )
                    TemplateChip(
                        selected = template == ProjectTemplate.BASIC_ACTIVITY_XML_KOTLIN,
                        onClick = { template = ProjectTemplate.BASIC_ACTIVITY_XML_KOTLIN },
                        label = "Basic Activity (Kotlin)"
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), packageName.trim(), minSdk, template) },
                enabled = !nameError && !packageError,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun TemplateChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}
