package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.domain.haversineMeters

/** [ForgottenFinishEngine]'s output per location sample - most samples change nothing. */
sealed interface ForgottenFinishDecision {
    data object NoChange : ForgottenFinishDecision
    data class WarningIssued(val anchoredAtElapsedRealtimeNanos: Long, val detectedAtElapsedRealtimeNanos: Long) : ForgottenFinishDecision
}

/**
 * DET-007: the mirror image of [ForgottenPauseEngine], for a real gap that
 * engine doesn't cover - a manually-started capture that keeps recording,
 * never paused, because the rider simply forgot to press Finish. Discovered
 * through real on-device dogfooding (a genuine ride whose capture ran ~30
 * extra stationary minutes after the rider parked), not from a pre-existing
 * F0.3 scenario - trip-detection-spec.md's SCN-017 documents it after the
 * fact. `AUTO-001`-started captures don't need this: `CandidateStopEngine`
 * already auto-finishes those on a real stop. A manual capture has no such
 * safety net at all, which is the actual gap this closes.
 *
 * Deliberately the inverse of [ForgottenPauseEngine]'s algorithm rather than
 * a different one: instead of warning once cumulative displacement from an
 * anchor exceeds a bar (evidence of sustained *movement*), this re-anchors
 * on every sample that moves far enough from the current anchor (evidence
 * the rider is still genuinely riding) and warns once the anchor's age
 * exceeds a duration bar without that happening (evidence of sustained
 * *non*-movement). Location-only for the same reason `ForgottenPauseEngine`
 * is: `TrackingSessionCoordinator.recordLocationUpdates` never sees activity
 * transitions, only location samples.
 *
 * Warns once per stationary episode, not once ever (unlike
 * [ForgottenPauseEngine], scoped to one bounded pause): a long multi-stop
 * ride can plausibly have more than one genuinely-forgotten-length stop, and
 * moving away resets the episode - so a rider who gets the reminder, then
 * rides on, then stops again just as long later, is warned again rather
 * than silently going unwarned because some earlier, unrelated stop already
 * "used up" this engine's one warning.
 *
 * A pure, stateful reducer - no DAO/Clock/IdGenerator, no Android (ADR-013).
 * This engine only ever *detects*; turning a [ForgottenFinishDecision.WarningIssued]
 * into an actual reminder is its caller's job, and it never touches capture
 * state itself (DP-005: manual ownership stays authoritative - this is a
 * nudge, never an auto-finish).
 */
class ForgottenFinishEngine(private val profile: ForgottenFinishProfile = ForgottenFinishProfile()) {

    private var anchorLatitude: Double? = null
    private var anchorLongitude: Double? = null
    private var anchorElapsedRealtimeNanos: Long? = null
    private var warnedForCurrentEpisode = false

    fun accept(sample: LocationSample): ForgottenFinishDecision {
        val anchorLat = anchorLatitude
        val anchorLon = anchorLongitude
        val anchorNanos = anchorElapsedRealtimeNanos
        if (anchorLat == null || anchorLon == null || anchorNanos == null) {
            anchorAt(sample)
            return ForgottenFinishDecision.NoChange
        }

        val displacementMeters = haversineMeters(anchorLat, anchorLon, sample.latitude, sample.longitude)
        if (displacementMeters >= profile.maxStationaryDisplacementMeters) {
            anchorAt(sample)
            return ForgottenFinishDecision.NoChange
        }

        if (warnedForCurrentEpisode) return ForgottenFinishDecision.NoChange

        val elapsedMs = (sample.elapsedRealtimeNanos - anchorNanos) / 1_000_000
        if (elapsedMs >= profile.minStationaryDurationMs) {
            warnedForCurrentEpisode = true
            return ForgottenFinishDecision.WarningIssued(anchorNanos, sample.elapsedRealtimeNanos)
        }
        return ForgottenFinishDecision.NoChange
    }

    private fun anchorAt(sample: LocationSample) {
        anchorLatitude = sample.latitude
        anchorLongitude = sample.longitude
        anchorElapsedRealtimeNanos = sample.elapsedRealtimeNanos
        warnedForCurrentEpisode = false
    }
}
