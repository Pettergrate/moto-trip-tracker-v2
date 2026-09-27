package com.mototriptracker.app.feature.home

import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.domain.capability.CapabilityIssue

/** F0.9 §5: HOME-01 answers two questions - is Auto Tracking ready, and is there an active Trip. */
data class HomeUiState(
    val capabilityMode: CapabilityMode? = null,
    /** PERM-003: why the app cannot do its job as well as it should right now, most important first; empty when nothing is wrong. */
    val capabilityIssues: List<CapabilityIssue> = emptyList(),
    val activeTrip: ActiveTripSummary? = null,
    val recentTrips: List<RecentTripUi> = emptyList()
)

/** F0.9 §5.2: Home's own compact view of the active Trip - Active Trip screen shows the fuller version. */
data class ActiveTripSummary(
    val captureId: String,
    val isPaused: Boolean,
    val distanceMeters: Double,
    val elapsedMs: Long
)

/** F0.9 §8.2: `displayName` is already resolved to the fallback ("Trip · Sep 15 · 08:42") when the Trip has no user-given name. */
data class RecentTripUi(
    val tripId: String,
    val displayName: String,
    val distanceMeters: Double?,
    val durationMs: Long?
)

/** F0.9 §5.1's four Auto Tracking readiness messages, one-to-one with [CapabilityMode] - SET-02 will likely want this same mapping later. */
fun CapabilityMode.toReadinessText(): String = when (this) {
    CapabilityMode.FULL_AUTO -> "Auto Tracking is ready"
    CapabilityMode.ASSISTED_AUTO -> "Auto Tracking is limited — background location is needed for hands-free start"
    CapabilityMode.MANUAL -> "Auto Tracking is off — start your trips manually"
    // PERM-003: this mode has two different causes (Location off, or only approximate location), so the *cause* is named by
    // the notice above and this line must not blame one of them - it used to say "Location services are off" even when the
    // real problem was the permission.
    CapabilityMode.LOCATION_DEGRADED -> "Trips cannot record a route until the problem above is fixed"
}
