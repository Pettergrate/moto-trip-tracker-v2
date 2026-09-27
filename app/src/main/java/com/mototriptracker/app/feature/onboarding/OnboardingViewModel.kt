package com.mototriptracker.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.datastore.OnboardingPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the app shows first: nothing yet (the preference is still being read), the welcome, or the app itself. */
enum class OnboardingGate { LOADING, WELCOME, DONE }

/**
 * PERM-001 / ONB-01: the welcome screen appears once. Continuing is remembered, but never *depends* on the
 * remembering: if the preference cannot be written the person still gets into the app this session, and the worst
 * case is seeing the welcome again next time. The welcome asks for no permission (`privacy-permissions.md` §19.1).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingPreferences: OnboardingPreferences
) : ViewModel() {

    private val continuedThisSession = MutableStateFlow(false)

    val gate: StateFlow<OnboardingGate> = combine(onboardingPreferences.welcomeSeen, continuedThisSession) { seen, continued ->
        if (seen || continued) OnboardingGate.DONE else OnboardingGate.WELCOME
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingGate.LOADING)

    fun onWelcomeContinue() {
        continuedThisSession.value = true
        viewModelScope.launch {
            try {
                onboardingPreferences.markWelcomeSeen()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Not remembered: the welcome may show once more next time. Never worth blocking the person.
            }
        }
    }
}
