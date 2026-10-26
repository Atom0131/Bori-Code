package com.bori.app.data.build

import com.bori.core.build.model.Diagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton cache of the most recent set of build diagnostics.
 *
 * Populated by [BuildViewModel] after each build completes (success or failure).
 * Observed by [EditorViewModel] to push underline markers into open editor tabs,
 * and by [EditorScreen] to render the Problems panel.
 */
@Singleton
class DiagnosticsStateHolder @Inject constructor() {

    private val _diagnostics = MutableStateFlow<List<Diagnostic>>(emptyList())
    val diagnostics: StateFlow<List<Diagnostic>> = _diagnostics.asStateFlow()

    fun update(list: List<Diagnostic>) { _diagnostics.value = list }
    fun clear() { _diagnostics.value = emptyList() }
}
