package com.bori.app.ui.git

import com.bori.app.ui.common.readableWidth
import com.bori.app.ui.common.ShellContentInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bori.app.ui.common.CollapsibleSection
import com.bori.core.project.git.GitCommit
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.History
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    onBack: () -> Unit,
    viewModel: GitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    Scaffold(

        contentWindowInsets = ShellContentInsets,
        topBar = {
            TopAppBar(
                title = { Text("Git") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.projectPath == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No active project",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .readableWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            if (!state.isRepo) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Not a git repository", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Initialize a repository to start tracking your project's history.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { viewModel.initRepo() },
                                modifier = Modifier.align(Alignment.End),
                                enabled = !state.isLoading,
                            ) {
                                Text("Init Repository")
                            }
                        }
                    }
                }
            }

            if (state.isRepo) {
                item {
                    val changes = listOf(
                        state.staged.size to "staged",
                        state.modified.size to "modified",
                        state.untracked.size to "untracked",
                    ).filter { it.first > 0 }.joinToString(" · ") { "${it.first} ${it.second}" }
                    CollapsibleSection(
                        title = "Changes",
                        summary = changes.ifEmpty { "Working tree clean" },
                        icon = Icons.Filled.Difference,
                        initiallyExpanded = true,
                        contentSpacing = 10.dp,
                    ) {
                        if (state.staged.isEmpty() && state.modified.isEmpty() && state.untracked.isEmpty()) {
                            Text(
                                "Nothing to commit, working tree clean",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.staged.isNotEmpty()) {
                            FileGroup("Staged", MaterialTheme.colorScheme.primary, state.staged)
                        }
                        if (state.modified.isNotEmpty()) {
                            FileGroup("Modified", MaterialTheme.colorScheme.error, state.modified)
                        }
                        if (state.untracked.isNotEmpty()) {
                            FileGroup("Untracked", MaterialTheme.colorScheme.onSurfaceVariant, state.untracked)
                        }
                        Button(
                            onClick = { viewModel.stageAll() },
                            modifier = Modifier.align(Alignment.End),
                            enabled = !state.isLoading,
                        ) {
                            Text("Stage All")
                        }
                    }
                }

                item {
                    CollapsibleSection(
                        title = "Commit",
                        summary = when {
                            state.staged.isEmpty() -> "Nothing staged yet"
                            state.authorName.isBlank() -> "${state.staged.size} staged · add your name"
                            else -> "${state.staged.size} staged · as ${state.authorName}"
                        },
                        icon = Icons.Filled.Done,
                        contentSpacing = 10.dp,
                    ) {
                        OutlinedTextField(
                            value = state.commitMessage,
                            onValueChange = { viewModel.setCommitMessage(it) },
                            label = { Text("Message") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = state.authorName,
                                onValueChange = { viewModel.setAuthorName(it) },
                                label = { Text("Name") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = state.authorEmail,
                                onValueChange = { viewModel.setAuthorEmail(it) },
                                label = { Text("Email") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            )
                        }
                        Button(
                            onClick = { viewModel.commit() },
                            modifier = Modifier.align(Alignment.End),
                            enabled = !state.isLoading && state.commitMessage.isNotBlank(),
                        ) {
                            Text("Commit")
                        }
                    }
                }

                item {
                    CollapsibleSection(
                        title = "Remote",
                        summary = state.remoteUrl.removePrefix("https://").removeSuffix(".git").ifBlank { "Not set up" },
                        icon = Icons.Filled.CloudSync,
                        contentSpacing = 10.dp,
                    ) {
                        OutlinedTextField(
                            value = state.remoteUrl,
                            onValueChange = { viewModel.setRemoteUrl(it) },
                            label = { Text("Remote URL") },
                            placeholder = { Text("https://github.com/user/repo.git") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = { viewModel.setUsername(it) },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = state.token,
                            onValueChange = { viewModel.setToken(it) },
                            label = { Text("Token / Password") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.pull() },
                                enabled = !state.isLoading,
                            ) {
                                Text("Pull")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { viewModel.saveAndPush() },
                                enabled = !state.isLoading && state.remoteUrl.isNotBlank(),
                            ) {
                                Text("Push")
                            }
                        }
                    }
                }

                if (state.log.isNotEmpty()) {
                    item {
                        CollapsibleSection(
                            title = "History",
                            summary = "${state.log.size} commits · latest: ${state.log.first().message.lineSequence().first()}",
                            icon = Icons.Filled.History,
                        ) {
                            state.log.forEach { commit -> CommitRow(commit, dateFormat) }
                        }
                    }
                }
            }

            if (state.error != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = state.error ?: "",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(onClick = { viewModel.clearError() }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileGroup(label: String, color: Color, files: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        files.take(8).forEach { path ->
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (files.size > 8) {
            Text(
                "…and ${files.size - 8} more",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun CommitRow(commit: GitCommit, dateFormat: SimpleDateFormat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = commit.shortSha,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(commit.message, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(
                text = "${commit.author} · ${dateFormat.format(Date(commit.timestamp))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
