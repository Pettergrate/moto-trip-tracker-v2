package com.mototriptracker.app.tracking.location

import com.mototriptracker.app.core.model.LocationProfile
import com.mototriptracker.app.experiment.ExperimentLocationProfiles
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `@Singleton`: [FusedLocationGateway] (reader) and `FieldTestHarnessViewModel`
 * (writer) must share the same instance for a selection to have any effect.
 */
@Singleton
class InMemoryLocationProfileSelector @Inject constructor() : LocationProfileSelector {
    private val active = AtomicReference(ExperimentLocationProfiles.DEFAULT)

    override fun current(): LocationProfile = active.get()

    override fun select(profile: LocationProfile?) {
        active.set(profile ?: ExperimentLocationProfiles.DEFAULT)
    }
}
