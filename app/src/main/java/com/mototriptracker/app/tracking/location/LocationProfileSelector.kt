package com.mototriptracker.app.tracking.location

import com.mototriptracker.app.core.model.LocationProfile

/**
 * The currently active [LocationProfile] for [FusedLocationGateway]'s real
 * tracking request. Deliberately in-memory only (see
 * [InMemoryLocationProfileSelector]), not persisted, so any unexpected
 * process death/crash before a field-test session gets to reset this
 * reverts to the safe default on the next launch, rather than silently
 * leaving production tracking on an experimental profile.
 * `FieldTestHarnessViewModel` re-applies the intended profile itself on its
 * own resumability path when it resumes a session after a kill.
 */
interface LocationProfileSelector {
    fun current(): LocationProfile

    /** `null` resets to `ExperimentLocationProfiles.DEFAULT`. */
    fun select(profile: LocationProfile?)
}
