package com.bori.app.ui.tutorial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.prefs.UserPreferencesRepository
import com.bori.core.ai.local.DownloadState
import com.bori.core.ai.local.ModelAdvisor
import com.bori.core.ai.local.ModelDownloadManager
import com.bori.core.project.ProjectRepository
import com.bori.core.security.AiProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the tutorial card: setup (model, project), then three chat rounds run by [TutorialSession]
 * (build the calculator, add color and the running sum, adapt to the unfolded screen).
 */
@HiltViewModel
class TutorialViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val userPrefs: UserPreferencesRepository,
    private val downloadManager: ModelDownloadManager,
    val session: TutorialSession,
) : ViewModel() {

    /** Setup steps before the rounds; [ROUNDS] covers everything [TutorialSession] runs. */
    enum class Step { WELCOME, CHECK_MODEL, CREATE_PROJECT, ROUNDS, CHOOSE_UNFOLD, DONE }

    private val _step = MutableStateFlow(Step.WELCOME)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    val hasDismissedTutorial: StateFlow<Boolean?> = userPrefs.preferences
        .map { it.hasDismissedTutorial }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloadState: StateFlow<DownloadState> = downloadManager.downloadState

    private val _modelAdvice = MutableStateFlow<String?>(null)
    /** One line on which model suits this phone, from the same advisor as Settings → Local AI. */
    val modelAdvice: StateFlow<String?> = _modelAdvice.asStateFlow()

    init {
        // Android may have killed Bori Code while the user tried the calculator: carry on where it was
        viewModelScope.launch {
            if (session.restore()) {
                _step.value = session.savedStep?.let { runCatching { Step.valueOf(it) }.getOrNull() } ?: Step.ROUNDS
                _isActive.value = true
            }
            _step.collect { if (_isActive.value) session.savedStep = it.name }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val specs = downloadManager.manualSpecs ?: downloadManager.scanSpecs()
            val best = ModelAdvisor.recommend(specs).firstOrNull { it.isBestPick }?.model
            // A fresh install defaults to E2B; download the model that suits this phone instead
            if (best != null && !downloadManager.isModelReady() && downloadManager.selected.value != best) {
                downloadManager.select(best)
            }
            val selected = downloadManager.selected.value
            _modelAdvice.value = when {
                best == null -> "This phone may be too limited for on-device AI; it could be very slow."
                best == selected -> "${selected.label} is the best fit for this phone."
                else -> "This phone could run ${best.label}; you're set to download ${selected.label}. " +
                    "Change it in Settings → Local AI."
            }
        }
        // The project created during CREATE_PROJECT starts round 1
        viewModelScope.launch(Dispatchers.IO) {
            projectRepository.projects.collect { projects ->
                if (_step.value != Step.CREATE_PROJECT) return@collect
                val created = session.findNewProject(projects) ?: return@collect
                session.adoptProject(created)
                _step.value = Step.ROUNDS
                session.startRound(1, choice = null)
            }
        }
        viewModelScope.launch {
            downloadManager.downloadState.collect { state ->
                if (_step.value == Step.CHECK_MODEL && state is DownloadState.Ready) {
                    useGemmaForChat()
                    _step.value = Step.CREATE_PROJECT
                }
            }
        }
    }

    fun startTutorial() {
        _isActive.value = true
        session.savedStep = Step.WELCOME.name
        _step.value = Step.WELCOME
        viewModelScope.launch(Dispatchers.IO) {
            userPrefs.dismissTutorial()
            session.reset()
        }
    }

    fun advance() {
        when (_step.value) {
            Step.WELCOME -> if (downloadManager.isModelReady()) {
                useGemmaForChat()
                _step.value = Step.CREATE_PROJECT
            } else {
                _step.value = Step.CHECK_MODEL
            }
            Step.CHECK_MODEL -> if (downloadManager.isModelReady()) {
                useGemmaForChat()
                _step.value = Step.CREATE_PROJECT
            }
            Step.DONE -> dismiss()
            else -> Unit
        }
    }

    /** After trying the app: on to the next round, round 3's question, or the end. */
    fun nextRound() {
        when (session.state.value.round) {
            1 -> session.startRound(2)
            2 -> _step.value = Step.CHOOSE_UNFOLD
            else -> {
                session.stop()
                _step.value = Step.DONE
            }
        }
    }

    fun chooseUnfold(choice: TutorialRounds.UnfoldChoice) {
        _step.value = Step.ROUNDS
        session.chooseUnfold(choice)
    }

    fun buildAndInstall() {
        viewModelScope.launch { session.buildAndInstall() }
    }

    fun startDownload() = downloadManager.startDownload()

    /** Chat's header and model chip show the on-device model the tutorial actually uses. */
    private fun useGemmaForChat() {
        viewModelScope.launch {
            userPrefs.setExperimentalFeaturesEnabled(true)
            userPrefs.updateChatProvider(AiProvider.LOCAL_GEMMA)
            userPrefs.updateChatModel(downloadManager.selected.value.id)
        }
    }

    fun dismiss() {
        _isActive.value = false
        session.stop()
        viewModelScope.launch { userPrefs.dismissTutorial() }
    }
}
