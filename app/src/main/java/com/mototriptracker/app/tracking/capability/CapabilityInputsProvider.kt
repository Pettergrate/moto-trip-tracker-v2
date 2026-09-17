package com.mototriptracker.app.tracking.capability

import com.mototriptracker.app.core.model.CapabilityInputs

/**
 * The real-Android counterpart `domain.capability.CapabilityResolver`'s own
 * KDoc named as not-yet-built ("whatever reads real permissions/services
 * into a CapabilityInputs lives elsewhere"). Kept as an interface so
 * `ActivityTransitionReceiver`/tests can substitute a fake, the same seam
 * `LocationGateway`/`FusedLocationGateway` already established.
 */
interface CapabilityInputsProvider {
    suspend fun current(): CapabilityInputs
}
