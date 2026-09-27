package com.mototriptracker.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.datastore.AppearancePreferences
import com.mototriptracker.app.core.theme.AccentColor
import com.mototriptracker.app.core.theme.Appearance
import com.mototriptracker.app.core.theme.ThemeBase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * SET-002: the theme in use, shared by the activity (which applies it to the whole app) and the Appearance screen
 * (which changes it). `null` while the preference is still being read, so nothing is drawn in a colour that is about
 * to change. A choice takes effect at once and is saved behind it: if saving fails the person still sees what they
 * picked for this session and the worst case is the previous theme next time - never a stuck screen.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val appearancePreferences: AppearancePreferences
) : ViewModel() {

    private val chosenThisSession = MutableStateFlow<Appearance?>(null)

    val appearance: StateFlow<Appearance?> = combine(appearancePreferences.appearance, chosenThisSession) { saved, chosen ->
        chosen ?: saved
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onBaseSelected(base: ThemeBase) = update { it.copy(base = base) }

    fun onAccentSelected(accent: AccentColor) = update { it.copy(accent = accent) }

    fun onResetToDefault() = update { Appearance.Default }

    private fun update(change: (Appearance) -> Appearance) {
        val next = change(appearance.value ?: Appearance.Default)
        chosenThisSession.value = next
        viewModelScope.launch {
            try {
                appearancePreferences.set(next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Not remembered: it applies for this session and the previous theme returns next time. Never worth an error.
            }
        }
    }
}
