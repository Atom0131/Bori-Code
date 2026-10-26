package com.bori.app.ui.tutorial

import android.content.Context
import android.content.Intent
import android.util.Log
import com.bori.app.data.build.BuildStateHolder
import com.bori.app.data.project.ProjectStateHolder
import com.bori.app.tools.ToolExecutor
import com.bori.core.ai.local.LocalGemmaClient
import com.bori.core.ai.model.ChatChunk
import com.bori.core.ai.model.ChatMessage
import com.bori.core.ai.model.ChatRequest
import com.bori.core.project.ProjectRepository
import com.bori.core.project.model.Project
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State and work of the calculator tutorial, shared by the tutorial card and Chat. The card moves
 * through the rounds; Chat sends the round's prompt here and shows Gemma's reply as it streams.
 */
@Singleton
class TutorialSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val projectStateHolder: ProjectStateHolder,
    private val buildStateHolder: BuildStateHolder,
    private val toolExecutor: ToolExecutor,
    private val localGemmaClient: LocalGemmaClient,
) {
    enum class Phase { IDLE, AWAITING_SEND, WRITING, WRITTEN, FAILED, BUILDING, BUILT, BUILD_FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val round: Int = 1,
        val choice: TutorialRounds.UnfoldChoice? = null,
        /** The file Gemma is writing, and its place in the round, e.g. 1 of 2. */
        val writingFile: String? = null,
        val fileIndex: Int = 0,
        val fileCount: Int = 0,
        /** Why the round or the build stopped. */
        val problem: String? = null,
        val apkSize: String? = null,
        /** Attempts at this round; a retry samples instead of decoding greedily. */
        val attempt: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Every change is saved: Android kills Bori Code for memory while the user tries the calculator. */
    private fun update(new: State) {
        _state.value = new
        prefs.edit()
            .putBoolean(KEY_ACTIVE, new.phase != Phase.IDLE)
            .putInt(KEY_ROUND, new.round)
            .putString(KEY_CHOICE, new.choice?.name)
            .putString(KEY_PHASE, new.phase.name)
            .putString(KEY_APK_SIZE, new.apkSize)
            .apply()
    }

    /** Where the card was (a [TutorialViewModel.Step] name), saved alongside the round. */
    var savedStep: String?
        get() = prefs.getString(KEY_STEP, null)
        set(value) = prefs.edit().putString(KEY_STEP, value).apply()

    /**
     * Picks the tutorial up after Bori Code was killed. A round cut off mid-write starts again from
     * its prompt, and a cut-off build goes back to "Build it". False when there's nothing to resume.
     */
    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return@withContext false
        val id = prefs.getLong(KEY_PROJECT_ID, -1L)
        val saved = (if (id != -1L) projectRepository.getProject(id) else null) ?: return@withContext false
        project = saved
        val phase = runCatching { Phase.valueOf(prefs.getString(KEY_PHASE, null) ?: "") }.getOrDefault(Phase.AWAITING_SEND)
        val choice = prefs.getString(KEY_CHOICE, null)
            ?.let { runCatching { TutorialRounds.UnfoldChoice.valueOf(it) }.getOrNull() }
        val round = prefs.getInt(KEY_ROUND, 1)
        when (phase) {
            Phase.IDLE -> return@withContext false
            // A round cut off or failed starts over greedily
            Phase.AWAITING_SEND, Phase.WRITING, Phase.FAILED, Phase.BUILD_FAILED -> startRound(round, choice)
            Phase.BUILDING -> update(State(phase = Phase.WRITTEN, round = round, choice = choice))
            else -> update(State(phase = phase, round = round, choice = choice, apkSize = prefs.getString(KEY_APK_SIZE, null)))
        }
        true
    }

    /**
     * A request for the chat box. Only the Chat screen consumes it (the Editor holds its own
     * ChatViewModel, which used to take it first), and [id] makes every refill a new value.
     */
    data class Prefill(val text: String, val id: Long = System.nanoTime())

    private val _chatPrefill = MutableStateFlow<Prefill?>(null)
    val chatPrefill: StateFlow<Prefill?> = _chatPrefill.asStateFlow()
    fun consumePrefill(id: Long) { if (_chatPrefill.value?.id == id) _chatPrefill.value = null }

    /** Puts this round's request back in the chat box, e.g. after Bori Code restarted and the box was emptied. */
    fun fillChatBox() { _chatPrefill.value = Prefill(currentRound.request) }

    var project: Project? = null
        private set

    private val prefs = context.getSharedPreferences("tutorial", Context.MODE_PRIVATE)
    private var projectIdsAtStart: Set<Long> = emptySet()

    val currentRound: TutorialRounds.Round
        get() = TutorialRounds.round(_state.value.round, _state.value.choice)

    /** Starts over: the last run's Calculator goes to Deleted projects, so a rerun is clean. */
    suspend fun reset() = withContext(Dispatchers.IO) {
        val previous = prefs.getLong(KEY_PROJECT_ID, -1L)
        if (previous != -1L) {
            projectRepository.deleteProject(previous)
            prefs.edit().remove(KEY_PROJECT_ID).apply()
        }
        project = null
        projectIdsAtStart = projectRepository.projects.first().map { it.id }.toSet()
        update(State())
        _chatPrefill.value = null
        savedStep = null
    }

    /** The first project created after [reset] is the tutorial's. */
    fun findNewProject(projects: List<Project>): Project? = projects.firstOrNull { it.id !in projectIdsAtStart }

    fun adoptProject(created: Project) {
        project = created
        prefs.edit().putLong(KEY_PROJECT_ID, created.id).apply()
    }

    fun startRound(round: Int, choice: TutorialRounds.UnfoldChoice? = _state.value.choice) {
        update(State(phase = Phase.AWAITING_SEND, round = round, choice = choice))
        _chatPrefill.value = Prefill(TutorialRounds.round(round, choice).request)
    }

    /** A retry samples (temperature 0.7) so Gemma can write something different from the greedy reply. */
    fun retryRound() {
        val s = _state.value
        update(s.copy(phase = Phase.AWAITING_SEND, problem = null, attempt = s.attempt + 1))
        _chatPrefill.value = Prefill(currentRound.request)
    }

    val isAwaitingSend: Boolean get() = _state.value.phase == Phase.AWAITING_SEND

    /**
     * Writes the round's files with Gemma, one call per file. [render] gets the whole chat reply so
     * far. Each file is checked before it's saved; the first bad one stops the round, and the
     * known-good files go to `safety-net/` in the project for the user to copy in by hand.
     */
    suspend fun runRound(userRequest: String, render: suspend (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            writeRoundFiles(userRequest, render)
        } finally {
            // Frees the ~4 GB model, so Android doesn't kill Bori Code while the user tries the app
            localGemmaClient.invalidateCache()
        }
    }

    private suspend fun writeRoundFiles(userRequest: String, render: suspend (String) -> Unit): Boolean {
        val proj = project ?: return false
        projectStateHolder.setActiveProject(proj)
        val round = currentRound
        val greedy = _state.value.attempt == 0
        val transcript = StringBuilder()
        var lastRender = 0L

        // Written only once every file passes, so a retry starts from the same files
        val passed = mutableListOf<Pair<File, String>>()
        for ((index, file) in round.files.withIndex()) {
            val name = file.name(proj)
            update(_state.value.copy(
                phase = Phase.WRITING, writingFile = name, fileIndex = index + 1, fileCount = round.files.size,
            ))
            val target = File(proj.rootPath, file.path(proj))
            val current = if (target.exists()) target.readText() else ""
            val request = ChatRequest(
                messages = listOf(
                    ChatMessage(ChatMessage.Role.SYSTEM, "Output only a complete ${file.lang} code block. No explanations."),
                    ChatMessage(ChatMessage.Role.USER, "The user asked: \"$userRequest\"\n\n${file.spec(current)}"),
                ),
                // Greedy decoding: a rehearsed run writes the same files on camera
                temperature = if (greedy) 0f else 0.7f,
            )
            transcript.append("**$name**\n")
            val fileStart = transcript.length
            val reply = StringBuilder()
            try {
                localGemmaClient.chat(request).collect { chunk ->
                    if (chunk is ChatChunk.TextDelta) {
                        reply.append(chunk.text)
                        transcript.setLength(fileStart)
                        transcript.append(reply)
                        val now = System.currentTimeMillis()
                        if (now - lastRender > RENDER_INTERVAL_MS) {
                            lastRender = now
                            render(transcript.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemma failed on $name", e)
            }

            val code = TutorialRounds.extractCodeBlock(reply.toString(), file.lang)?.let {
                if (file.lang == "kotlin") {
                    TutorialRounds.withLiteralIds(TutorialRounds.withSuperOnCreate(TutorialRounds.withPackage(it, proj.packageName)))
                } else TutorialRounds.withPlainStyle(it)
            }
            val problem = if (code == null) "no ```${file.lang} block in the reply" else file.problem(code)
            Log.i(TAG, "round ${round.number} $name: ${reply.length} chars, greedy=$greedy, " +
                "fingerprint=${reply.toString().hashCode().toUInt()}, problem=${problem ?: "none"}")
            transcript.append("\n")
            if (code != null && problem == null) {
                passed += target to code
                transcript.append("✓ ${file.path(proj)} passed the checks\n\n")
                render(transcript.toString())
            } else {
                val netDir = writeSafetyNet(proj, round, name, reply.toString())
                transcript.append("✗ $name has a problem: $problem\n")
                transcript.append("Round stopped. Known-good files are in ${netDir.relativeTo(File(proj.rootPath))}/.")
                render(transcript.toString())
                update(_state.value.copy(phase = Phase.FAILED, problem = "$name: $problem", writingFile = null))
                return false
            }
        }
        passed.forEach { (target, code) ->
            target.parentFile?.mkdirs()
            target.writeText(code + "\n")
        }
        transcript.append("Saved ${passed.size} files.")
        render(transcript.toString())
        update(_state.value.copy(phase = Phase.WRITTEN, writingFile = null))
        return true
    }

    /** Copies the round's known-good files and Gemma's rejected reply into `<project>/safety-net/roundN/`. */
    private fun writeSafetyNet(proj: Project, round: TutorialRounds.Round, rejectedName: String, reply: String): File {
        val dir = File(proj.rootPath, "safety-net/${round.safetyNetDir}").apply { mkdirs() }
        runCatching {
            val assetDir = "safety_net/${round.safetyNetDir}"
            context.assets.list(assetDir)?.forEach { asset ->
                val text = context.assets.open("$assetDir/$asset").bufferedReader().readText()
                    .replace("{{PACKAGE}}", proj.packageName)
                File(dir, asset).writeText(text)
            }
            File(dir, "rejected-$rejectedName.txt").writeText(reply)
        }.onFailure { Log.w(TAG, "Couldn't write safety-net files", it) }
        return dir
    }

    /** Builds the project, then opens the system installer. */
    suspend fun buildAndInstall() {
        val proj = project ?: return
        projectStateHolder.setActiveProject(proj)
        update(_state.value.copy(phase = Phase.BUILDING, problem = null))
        val result = toolExecutor.execute("trigger_build", "{}")
        val apk = buildStateHolder.lastBuild.value.apkPath?.let(::File)
        if (result.isError || apk == null || !apk.exists()) {
            val error = buildStateHolder.lastBuild.value.error
                ?: result.output.lineSequence().firstOrNull { it.startsWith("Error:") }
                ?: "the build failed"
            Log.i(TAG, "round ${_state.value.round} build failed: $error")
            update(_state.value.copy(phase = Phase.BUILD_FAILED, problem = error))
            return
        }
        Log.i(TAG, "round ${_state.value.round} build ok: ${apk.length()} bytes")
        update(_state.value.copy(phase = Phase.BUILT, apkSize = "%.1f MB".format(apk.length() / 1_048_576.0)))
        toolExecutor.execute("install_apk", "{}")
    }

    /** Opens the built app by its activity name, which needs no package-visibility permission. */
    fun openApp() {
        val pkg = project?.packageName ?: return
        runCatching {
            context.startActivity(
                Intent().setClassName(pkg, "$pkg.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "Couldn't open $pkg", it) }
    }

    fun chooseUnfold(choice: TutorialRounds.UnfoldChoice) = startRound(3, choice)

    fun stop() {
        update(State())
        _chatPrefill.value = null
        savedStep = null
    }

    private companion object {
        const val TAG = "Tutorial"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_ACTIVE = "active"
        const val KEY_ROUND = "round"
        const val KEY_CHOICE = "choice"
        const val KEY_PHASE = "phase"
        const val KEY_APK_SIZE = "apk_size"
        const val KEY_STEP = "step"
        const val RENDER_INTERVAL_MS = 150L
    }
}
