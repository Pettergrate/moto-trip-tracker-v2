package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.domain.haversineMeters

/** [ForgottenPauseEngine]'s output per location sample - most samples change nothing. */
sealed interface ForgottenPauseDecision {
    data object NoChange : ForgottenPauseDecision
    data class WarningIssued(val anchoredAtElapsedRealtimeNanos: Long, val detectedAtElapsedRealtimeNanos: Long) : ForgottenPauseDecision
}

/**
 * DET-005/F0.3 §8's "forgotten-pause scenario": "If the phone detects
 * sustained motorized movement while the Trip is manually paused... it MUST
 * NOT silently resume recording." This engine only ever *detects* - turning
 * a [ForgottenPauseDecision.WarningIssued] into an actual reminder is its
 * caller's job (`TrackingSessionCoordinator`/`TrackingForegroundService`);
 * this class never touches capture/pause state itself (DP-005: manual
 * ownership stays authoritative no matter what this engine decides).
 *
 * Deliberately location-only, unlike `CandidateStartEngine`'s activity+
 * location combo: `TrackingSessionCoordinator.recordLocationUpdates` (the
 * path a manually-started, manually-paused capture actually uses - F0.3
 * §8's own primary example is "eating, resting or visiting a location",
 * nothing auto-detection-specific) never sees activity transitions, only
 * location samples. Requiring an `IN_VEHICLE` gate the way candidate-start
 * does would leave that whole case undetectable. `ForgottenPauseProfile`'s
 * larger displacement/duration bar is what stands in for the missing
 * activity evidence instead.
 *
 * A fresh instance per pause is the caller's responsibility (mirroring
 * `CandidateStopEngine`'s "one instance per active session" pattern) - this
 * class has no way to know a pause ended on its own. [hasWarned] lets a
 * caller confirm it need not keep routing samples here: F0.3 §8 explicitly
 * defers "whether a second reminder/escalation is useful" to future
 * research, so warning more than once per pause is out of scope for v1,
 * not an oversight.
 */
class ForgottenPauseEngine(private val profile: ForgottenPauseProfile = ForgottenPauseProfile()) {

    private var anchorLatitude: Double? = null
    private var anchorLongitude: Double? = null
    private var anchorElapsedRealtimeNanos: Long? = null
    private var warned = false

    val hasWarned: Boolean get() = warned

    fun accept(sample: LocationSample): ForgottenPauseDecision {
        if (warned) return ForgottenPauseDecision.NoChange

        val anchorLat = anchorLatitude
        val anchorLon = anchorLongitude
        val anchorNanos = anchorElapsedRealtimeNanos
        if (anchorLat == null || anchorLon == null || anchorNanos == null) {
            anchorLatitude = sample.latitude
            anchorLongitude = sample.longitude
            anchorElapsedRealtimeNanos = sample.elapsedRealtimeNanos
            return ForgottenPauseDecision.NoChange
        }

        val elapsedMs = (sample.elapsedRealtimeNanos - anchorNanos) / 1_000_000
        val displacementMeters = haversineMeters(anchorLat, anchorLon, sample.latitude, sample.longitude)
        if (elapsedMs >= profile.minConfirmationDurationMs && displacementMeters >= profile.minDisplacementMeters) {
            warned = true
            return ForgottenPauseDecision.WarningIssued(anchorNanos, sample.elapsedRealtimeNanos)
        }
        return ForgottenPauseDecision.NoChange
    }
}
