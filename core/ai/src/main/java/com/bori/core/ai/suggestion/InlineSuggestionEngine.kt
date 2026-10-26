package com.bori.core.ai.suggestion

import com.bori.core.ai.context.ContextManager
import com.bori.core.ai.router.ModelRouter
import com.bori.core.editor.EditorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts a stream of [EditorState] snapshots into a stream of AI completion
 * suggestions.
 *
 * Pipeline:
 * 1. [debounce] — waits 400 ms of inactivity before firing a request.
 * 2. [distinctUntilChangedBy] — skips if the prefix text hasn't changed
 *    (e.g. cursor moved but content didn't).
 * 3. [filter] — skips empty prefixes and whitespace-only lines to avoid
 *    triggering completions at the top of a blank file.
 * 4. [flatMapLatest] — cancels any in-flight request when a new state arrives.
 * 5. Errors are swallowed with [catch] so a network blip never crashes the
 *    typing experience.
 *
 * The engine itself is stateless. Call [suggestions] with any [Flow<EditorState>]
 * — typically [EditorEngine.stateFlow] wired in [EditorScreen].
 */
@Singleton
class InlineSuggestionEngine @Inject constructor(
    private val router: ModelRouter,
    private val contextManager: ContextManager,
) {
    /**
     * Returns a [Flow] that emits one suggestion string each time the model
     * responds. An empty string signals "no suggestion" (e.g. after an error).
     *
     * Collect this flow in a [kotlinx.coroutines.CoroutineScope] tied to the
     * composable or ViewModel lifecycle; cancelling the scope automatically
     * cancels any in-flight HTTP request via [flatMapLatest].
     */
    fun suggestions(editorStates: Flow<EditorState>): Flow<String> =
        editorStates
            .debounce(400L)
            .distinctUntilChangedBy { it.prefix }
            .filter { state ->
                state.prefix.isNotBlank() &&
                    state.prefix.trimEnd().last() != '\n'
            }
            .flatMapLatest { state ->
                val request = contextManager.buildCompletionRequest(
                    prefix = state.prefix,
                    suffix = state.suffix,
                    language = state.language,
                )
                router.complete(request)
                    .map { chunk -> chunk.text }
                    .catch { emit("") }
            }
}
