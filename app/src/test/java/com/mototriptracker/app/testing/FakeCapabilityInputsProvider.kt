package com.mototriptracker.app.testing

import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider

/** AUTO-001: a settable [CapabilityInputsProvider] for tests, mirroring [FakeCapabilityProvider]'s fixtures. */
class FakeCapabilityInputsProvider(private var inputs: CapabilityInputs) : CapabilityInputsProvider {
    override suspend fun current(): CapabilityInputs = inputs

    fun set(inputs: CapabilityInputs) {
        this.inputs = inputs
    }
}
