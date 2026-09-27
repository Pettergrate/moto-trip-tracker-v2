package com.mototriptracker.app.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.datastore.AutoTrackingPreferences
import com.mototriptracker.app.domain.capability.AutoTrackingReadiness
import com.mototriptracker.app.domain.capability.AutoTrackingSetup
import com.mototriptracker.app.domain.capability.AutoTrackingState
import com.mototriptracker.app.domain.capability.RequirementStatus
import com.mototriptracker.app.domain.capability.SetupStep
import com.mototriptracker.app.tracking.activityrecognition.AutoTrackingDetection
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Auto Tracking screen shows: the switch, the state it is in, and each thing it needs. */
data class AutoTrackingUiState(
    val enabled: Boolean,
    val state: AutoTrackingState,
    val requirements: List<RequirementStatus>
)

/**
 * SET-02 / `ux-navigation.md` §15. The switch is the person's intent (`AutoTrackingPreferences`, which the capability
 * model already reads); the state is what the resolver says given that intent and the permissions actually held, so the
 * screen can never promise more than the app will do. Permissions are re-read every few seconds while the screen is
 * visible, because the person changes them in the phone's Settings - and the switch is taken from the preference
 * itself, so it moves the moment it is tapped instead of waiting for the next read.
 */
@HiltViewModel
class AutoTrackingViewModel @Inject constructor(
    private val autoTrackingPreferences: AutoTrackingPreferences,
    private val capabilityInputsProvider: CapabilityInputsProvider,
    private val detection: AutoTrackingDetection
) : ViewModel() {

    /** How often the permissions are re-read while the screen is visible; only tests change it. */
    @androidx.annotation.VisibleForTesting
    internal var recheckMs: Long = RECHECK_MS

    private val inputs = flow {
        while (true) {
            try {
                emit(capabilityInputsProvider.current())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failed platform read shows the previous state rather than an invented one.
            }
            delay(recheckMs)
        }
    }

    private val enabled: Flow<Boolean> = autoTrackingPreferences.autoTrackingEnabled.catch { emit(false) }

    val uiState: StateFlow<AutoTrackingUiState?> = combine(inputs, enabled) { current, isEnabled ->
        val effective = current.copy(autoTrackingEnabledByUser = isEnabled)
        AutoTrackingUiState(
            enabled = isEnabled,
            state = AutoTrackingReadiness.stateFor(effective),
            requirements = AutoTrackingReadiness.requirementsFor(effective)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * PERM-002: which permission the guided setup asks for next. Reads the phone *now* rather than trusting the last
     * poll - the person has just answered a system dialog and the poll may be up to 3 s behind - and never blocks
     * or breaks the screen: a read that fails means "nothing more to ask".
     */
    fun nextSetupStep(attempted: Set<SetupStep>, sdkInt: Int = Build.VERSION.SDK_INT, onStep: (SetupStep?) -> Unit) {
        val alreadyAsked = attempted.toSet()
        viewModelScope.launch {
            val step = try {
                val requirements = AutoTrackingReadiness.requirementsFor(capabilityInputsProvider.current())
                // A permission has just been answered: listening may have to start (or, if it was refused, stay off).
                detection.sync()
                AutoTrackingSetup.nextStep(requirements, sdkInt, alreadyAsked)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null
            }
            onStep(step)
        }
    }

    /** Applies the desired listening state again - called when the screen comes back to the front (a permission may have changed in Settings). */
    fun syncDetection() {
        viewModelScope.launch {
            try {
                detection.sync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Logged by the registration itself; the next change or app start applies it again.
            }
        }
    }

    /**
     * [afterSaved] runs once the choice is saved and the listening state applied: the guided setup starts from there, so it
     * never races the write it depends on (it used to read the phone while the switch was still being saved).
     */
    fun onToggle(enabled: Boolean, afterSaved: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                autoTrackingPreferences.setAutoTrackingEnabled(enabled)
                // "Off" has to mean not listening, and "on" (with the permission) has to start listening - at once, not at the next app start.
                detection.sync()
                afterSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Not saved: the switch stays where it was, which is the truth - the preference did not change.
            }
        }
    }

    companion object {
        const val RECHECK_MS = 3_000L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
