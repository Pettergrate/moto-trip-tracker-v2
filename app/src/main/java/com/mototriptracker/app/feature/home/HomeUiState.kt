package com.mototriptracker.app.feature.home

import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.domain.capability.AutoTrackingState
import com.mototriptracker.app.domain.capability.CapabilityIssue

/** F0.9 §5: HOME-01 answers two questions - is Auto Tracking ready, and is there an active Trip. */
data class HomeUiState(
    val capabilityMode: CapabilityMode? = null,
    /** PERM-003: why the app cannot do its job as well as it should right now, most important first; empty when nothing is wrong. */
    val capabilityIssues: List<CapabilityIssue> = emptyList(),
    /** SET-02: what Auto Tracking should say (off, ready, limited, needs setup, location problem); `null` until the first read. */
    val autoTrackingState: AutoTrackingState? = null,
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

/**
 * F0.9 §5.1's Auto Tracking readiness line, now one per [AutoTrackingState] (SET-02): the four of the document plus "off",
 * so a person who switched it on but is missing a permission is not told it is off. The screen behind the card says more.
 */
fun AutoTrackingState.toReadinessText(): String = when (this) {
    AutoTrackingState.OFF -> "Auto Tracking is off — start your trips manually"
    AutoTrackingState.READY -> "Auto Tracking is ready"
    AutoTrackingState.LIMITED -> "Auto Tracking is limited — tap to see what is missing for hands-free start"
    AutoTrackingState.NEEDS_SETUP -> "Auto Tracking needs a permission before it can detect trips — tap to set it up"
    // PERM-003: this state has two different causes (Location off, or only approximate location), so the *cause* is named by
    // the notice above and this line must not blame one of them.
    AutoTrackingState.LOCATION_PROBLEM -> "Trips cannot record a route until the problem above is fixed"
}
