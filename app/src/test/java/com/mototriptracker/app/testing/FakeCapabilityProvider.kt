package com.mototriptracker.app.testing

import com.mototriptracker.app.core.model.CapabilityInputs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * F0.12 §5.4. Exposes mutable, testable [CapabilityInputs] instead of
 * querying Android permissions/services directly — `CAP-001`'s resolver
 * (not yet implemented) will consume a real equivalent of this that
 * actually reads permission/service state; this fake just lets a test set
 * that state directly and change it mid-test (e.g. "permission revoked
 * during Trip", F0.10/F0.12 TST-PERM-006).
 */
class FakeCapabilityProvider(initial: CapabilityInputs) {
    private val state = MutableStateFlow(initial)
    val capabilityInputs: StateFlow<CapabilityInputs> = state

    fun update(transform: (CapabilityInputs) -> CapabilityInputs) {
        state.value = transform(state.value)
    }

    companion object {
        /** Every permission granted, every service enabled — FULL_AUTO-eligible inputs. */
        fun fullAuto() = CapabilityInputs(
            preciseLocationGranted = true,
            approximateLocationGranted = true,
            activityRecognitionGranted = true,
            backgroundLocationGranted = true,
            notificationsEnabled = true,
            locationServicesEnabled = true,
            autoTrackingEnabledByUser = true
        )

        /** Nothing granted beyond what manual recording needs — a clean install. */
        fun cleanInstall() = CapabilityInputs(
            preciseLocationGranted = false,
            approximateLocationGranted = false,
            activityRecognitionGranted = false,
            backgroundLocationGranted = false,
            notificationsEnabled = false,
            locationServicesEnabled = true,
            autoTrackingEnabledByUser = false
        )
    }
}
