package com.bori.app.ui.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bori.app.ui.tutorial.TutorialSession.Phase
import com.bori.app.ui.tutorial.TutorialViewModel.Step
import com.bori.core.ai.local.DownloadState

private val successGreen = Color(0xFF4CAF50)

/**
 * The tutorial card above the bottom bar. [onGoToChat] switches to the Chat tab, where each
 * round's request waits in the chat box.
 */
@Composable
fun TutorialOverlay(viewModel: TutorialViewModel, onGoToChat: () -> Unit, modifier: Modifier = Modifier) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val round by viewModel.session.state.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val modelAdvice by viewModel.modelAdvice.collectAsStateWithLifecycle()

    // Each round starts in Chat, with its request ready to send
    LaunchedEffect(step, round.phase, round.round) {
        if (step == Step.ROUNDS && round.phase == Phase.AWAITING_SEND) onGoToChat()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (step) {
                Step.WELCOME -> {
                    Header(Icons.Default.School, "Build an app with on-device AI")
                    Body(
                        "In three chat rounds, Gemma running on this phone builds a calculator. " +
                            "You ask, Gemma writes the code, you build and try it, then ask for changes.",
                    )
                    Buttons(onSkip = viewModel::dismiss) { Button(onClick = viewModel::advance) { Text("Let's go") } }
                }
                Step.CHECK_MODEL -> {
                    Header(Icons.Default.CloudDownload, "Download the local model")
                    CheckModelBody(downloadState, modelAdvice)
                    Buttons(onSkip = viewModel::dismiss) {
                        when (downloadState) {
                            is DownloadState.Idle, is DownloadState.Error ->
                                Button(onClick = viewModel::startDownload) { Text("Download") }
                            is DownloadState.Downloading -> Button(enabled = false, onClick = {}) { Text("Downloading…") }
                            is DownloadState.Ready -> Button(onClick = viewModel::advance) { Text("Continue") }
                        }
                    }
                }
                Step.CREATE_PROJECT -> {
                    Header(Icons.Default.CreateNewFolder, "Create a project")
                    Body("Tap the Editor tab (the <> icon at the bottom), then tap + and create a Kotlin project named Calculator.")
                    Buttons(onSkip = viewModel::dismiss) { Button(enabled = false, onClick = {}) { Text("Waiting…") } }
                }
                Step.ROUNDS -> RoundBody(viewModel, round, onGoToChat)
                Step.CHOOSE_UNFOLD -> {
                    Header(Icons.Default.Help, "Round 3 of 3 · Bori Code asks")
                    Body("When your phone is unfolded, what should change?")
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TutorialRounds.UnfoldChoice.entries.forEach { choice ->
                            OutlinedButton(onClick = { viewModel.chooseUnfold(choice) }, modifier = Modifier.fillMaxWidth()) {
                                Text(choice.label)
                            }
                        }
                    }
                }
                Step.DONE -> {
                    Header(Icons.Default.CheckCircle, "You built an app with on-device AI", successGreen)
                    Body(
                        "That's the loop: ask, build, try it, then ask for changes. " +
                            "Everything ran on this phone, with no internet.",
                    )
                    Buttons(onSkip = null) { Button(onClick = viewModel::advance) { Text("Finish") } }
                }
            }
        }
    }
}

@Composable
private fun RoundBody(viewModel: TutorialViewModel, state: TutorialSession.State, onGoToChat: () -> Unit) {
    val session = viewModel.session
    val round = TutorialRounds.round(state.round, state.choice)
    val title = "Round ${state.round} of ${TutorialRounds.ROUND_COUNT}"
    when (state.phase) {
        Phase.IDLE -> Unit
        Phase.AWAITING_SEND -> {
            Header(Icons.Default.Send, "$title · Ask Gemma")
            Body("Your request is in the chat box. Tap Send, or type it your own way.")
            InstructionsToggle(round)
            Buttons(onSkip = viewModel::dismiss) {
                Button(onClick = { session.fillChatBox(); onGoToChat() }) { Text("Put it in the chat box") }
            }
        }
        Phase.WRITING -> {
            Header(Icons.Default.AutoAwesome, "$title · Gemma is writing")
            Body("${state.writingFile} (${state.fileIndex} of ${state.fileCount}). Watch it in the chat.")
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        Phase.WRITTEN -> {
            Header(Icons.Default.Build, "$title · Build it")
            Body("Gemma wrote ${state.fileCount} files and every one passed the checks. Build them into an app on this phone.")
            Buttons(onSkip = viewModel::dismiss) { Button(onClick = viewModel::buildAndInstall) { Text("Build & install") } }
        }
        Phase.BUILDING -> {
            Header(Icons.Default.Build, "$title · Building")
            Body("Compiling and signing the app on the phone…")
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        Phase.BUILT -> {
            Header(Icons.Default.CheckCircle, "$title · Try it", successGreen)
            Body("Built (${state.apkSize}). Tap Install in the installer, then: ${round.tryIt}")
            Buttons(onSkip = null) {
                OutlinedButton(onClick = session::openApp) { Text("Open app") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = viewModel::nextRound) {
                    Text(if (state.round < TutorialRounds.ROUND_COUNT) "Next round" else "Finish")
                }
            }
        }
        Phase.FAILED, Phase.BUILD_FAILED -> {
            Header(Icons.Default.ErrorOutline, "$title · Gemma made a mistake", MaterialTheme.colorScheme.error)
            Body(
                (state.problem ?: "Something went wrong.") +
                    "\n\nTry again and Gemma writes it fresh. Known-good files are in the project's " +
                    "safety-net/${round.safetyNetDir} folder if you'd rather copy them in.",
            )
            Buttons(onSkip = viewModel::dismiss) { Button(onClick = session::retryRound) { Text("Try again") } }
        }
    }
}

/** Shows the rules Bori Code adds to the user's request for each file, so nothing is hidden. */
@Composable
private fun InstructionsToggle(round: TutorialRounds.Round) {
    var open by remember(round.number) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.padding(top = 4.dp)) {
        Text(if (open) "Hide what Bori Code tells Gemma" else "Show what Bori Code tells Gemma")
    }
    if (open) {
        val text = round.files.joinToString("\n\n") { it.spec("") }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header(icon: ImageVector, title: String, tint: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Buttons(onSkip: (() -> Unit)?, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onSkip != null) {
            TextButton(onClick = onSkip) { Text("Skip") }
            Spacer(Modifier.width(8.dp))
        }
        content()
    }
}

@Composable
private fun CheckModelBody(downloadState: DownloadState, modelAdvice: String?) {
    when (downloadState) {
        is DownloadState.Idle -> Body("The tutorial uses Gemma on-device. Wi-Fi recommended." + modelAdvice?.let { "\n\n$it" }.orEmpty())
        is DownloadState.Downloading -> {
            Body("Downloading model… ${downloadState.progressPercent}%")
            LinearProgressIndicator(
                progress = { downloadState.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        is DownloadState.Error -> Text(
            text = downloadState.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
        is DownloadState.Ready -> Text(
            text = "Model ready!",
            style = MaterialTheme.typography.bodyMedium,
            color = successGreen,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
