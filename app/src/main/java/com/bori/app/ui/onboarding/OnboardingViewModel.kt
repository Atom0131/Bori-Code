package com.bori.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bori.app.data.prefs.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    // null = DataStore not yet emitted (loading); false = first launch; true = already seen
    val hasSeenOnboarding = prefs.preferences
        .map { it.hasSeenOnboarding }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun markComplete() = viewModelScope.launch {
        prefs.markOnboardingComplete()
    }
}
