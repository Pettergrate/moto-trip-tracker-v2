package com.mototriptracker.app.testing

import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider

/** AUTO-001: a settable [CapabilityInputsProvider] for tests, mirroring [FakeCapabilityProvider]'s fixtures. */
class FakeCapabilityInputsProvider(private var inputs: CapabilityInputs) : CapabilityInputsProvider {
    /** Makes the next reads fail, the way a platform call that throws would. */
    var throwOnRead: Boolean = false

    override suspend fun current(): CapabilityInputs {
        check(!throwOnRead) { "capability read failed" }
        return inputs
    }

    fun set(inputs: CapabilityInputs) {
        this.inputs = inputs
    }
}
