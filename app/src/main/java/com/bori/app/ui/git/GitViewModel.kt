package com.bori.app.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.project.ProjectStateHolder
import com.bori.core.project.git.GitCommit
import com.bori.core.project.git.GitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class GitScreenState(
    val projectPath: String? = null,
    val isRepo: Boolean = false,
    val staged: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val untracked: List<String> = emptyList(),
    val log: List<GitCommit> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val commitMessage: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val remoteUrl: String = "",
    val username: String = "",
    val token: String = "",
)

@HiltViewModel
class GitViewModel @Inject constructor(
    private val gitRepository: GitRepository,
    private val projectStateHolder: ProjectStateHolder,
) : ViewModel() {

    private val _state = MutableStateFlow(GitScreenState())
    val state: StateFlow<GitScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            projectStateHolder.activeProject.collect { project ->
                _state.update { it.copy(projectPath = project?.rootPath) }
                if (project != null) refresh()
            }
        }
        // A coding CLI may have changed files (or committed) during a chat turn.
        viewModelScope.launch { projectStateHolder.filesChanged.collect { refresh() } }
    }

    fun refresh() {
        val path = _state.value.projectPath ?: return
        val dir = File(path)
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val isRepo = gitRepository.isRepo(dir)
                if (isRepo) {
                    val status = gitRepository.status(dir)
                    val log = gitRepository.log(dir)
                    val remoteUrl = gitRepository.getRemoteUrl(dir) ?: _state.value.remoteUrl
                    _state.update {
                        it.copy(
                            isRepo = true,
                            staged = status.staged,
                            modified = status.modified,
                            untracked = status.untracked,
                            log = log,
                            remoteUrl = remoteUrl,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isRepo = false,
                            staged = emptyList(),
                            modified = emptyList(),
                            untracked = emptyList(),
                            log = emptyList(),
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun initRepo() {
        val path = _state.value.projectPath ?: return
        viewModelScope.launch {
            runOp {
                gitRepository.init(File(path))
                refresh()
            }
        }
    }

    fun stageAll() {
        val path = _state.value.projectPath ?: return
        viewModelScope.launch {
            runOp {
                gitRepository.stageAll(File(path))
                refresh()
            }
        }
    }

    fun commit() {
        val s = _state.value
        val path = s.projectPath ?: return
        if (s.commitMessage.isBlank()) {
            _state.update { it.copy(error = "Commit message is required") }
            return
        }
        viewModelScope.launch {
            runOp {
                gitRepository.commit(
                    dir = File(path),
                    message = s.commitMessage,
                    name = s.authorName.ifBlank { "Bori Code user" },
                    email = s.authorEmail.ifBlank { "user@localhost" },
                )
                _state.update { it.copy(commitMessage = "") }
                refresh()
            }
        }
    }

    fun saveAndPush() {
        val s = _state.value
        val path = s.projectPath ?: return
        viewModelScope.launch {
            runOp {
                if (s.remoteUrl.isNotBlank()) gitRepository.setRemoteUrl(File(path), s.remoteUrl)
                gitRepository.push(File(path), s.username, s.token)
            }
        }
    }

    fun pull() {
        val s = _state.value
        val path = s.projectPath ?: return
        viewModelScope.launch {
            runOp {
                gitRepository.pull(File(path), s.username, s.token)
                refresh()
            }
        }
    }

    fun setCommitMessage(msg: String) = _state.update { it.copy(commitMessage = msg) }
    fun setAuthorName(name: String) = _state.update { it.copy(authorName = name) }
    fun setAuthorEmail(email: String) = _state.update { it.copy(authorEmail = email) }
    fun setRemoteUrl(url: String) = _state.update { it.copy(remoteUrl = url) }
    fun setUsername(u: String) = _state.update { it.copy(username = u) }
    fun setToken(t: String) = _state.update { it.copy(token = t) }
    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun runOp(block: suspend () -> Unit) {
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            block()
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }
}
