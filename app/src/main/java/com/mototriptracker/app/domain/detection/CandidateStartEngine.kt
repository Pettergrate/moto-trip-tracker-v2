package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.domain.haversineMeters

/** [CandidateStartEngine]'s output per [DetectionEvent] — most events change nothing. */
sealed interface CandidateStartDecision {
    data object NoChange : CandidateStartDecision
    data object CandidateOpened : CandidateStartDecision
    data object Abandoned : CandidateStartDecision
    data class Confirmed(val candidateOpenedAtElapsedRealtimeNanos: Long, val confirmedAtElapsedRealtimeNanos: Long) : CandidateStartDecision
}

/**
 * DET-002: F0.3 §5's CANDIDATE_START state, scoped to exactly its own
 * acceptance criterion — "no single sample starts a Trip by itself"
 * (DP-001). `IN_VEHICLE` ENTER (ADR-007's passive trigger) only *opens* a
 * candidate; confirming it needs BOTH sustained time (DP-002 temporal
 * confirmation) AND real displacement from the candidate's anchor point —
 * activity evidence alone or a single GPS jump alone can never confirm
 * (SCN-015, SCN-025).
 *
 * Displacement is measured from the anchor (the first location fix
 * received *after* the candidate opened) to the current fix — straight-line
 * displacement, not summed path distance, so ordinary GPS jitter while
 * essentially stationary can't accumulate into a false confirmation.
 *
 * A pure, stateful reducer — no DAO/Clock/IdGenerator, no Android (ADR-013).
 * There is no live caller yet: wiring a [Confirmed] decision into an actual
 * `TrackingSessionCoordinator` auto-start, gated by capability mode, is
 * `AUTO-001`'s job, the same "built before its consumer exists" posture
 * `CAP-001`'s `CapabilityResolver` had until now.
 *
 * Assumes its caller only feeds it events while the overall system is
 * actually IDLE (F0.3 §4: CANDIDATE_START is only reachable from IDLE) —
 * this engine has no way to know a real capture is already active elsewhere
 * (that would mean depending on `TripCaptureDao`, breaking ADR-013's
 * boundary for no real benefit), so it is `AUTO-001`'s job to stop routing
 * events here once a Trip is actually being tracked, not this engine's job
 * to notice on its own.
 */
class CandidateStartEngine(private val profile: CandidateStartProfile = CandidateStartProfile()) {

    private sealed interface State {
        data object Idle : State
        data class Candidate(
            val openedAtElapsedRealtimeNanos: Long,
            val anchorLatitude: Double? = null,
            val anchorLongitude: Double? = null
        ) : State
    }

    private var state: State = State.Idle

    val isCandidateOpen: Boolean get() = state is State.Candidate

    fun accept(event: DetectionEvent): CandidateStartDecision = when (event) {
        is DetectionEvent.Activity -> onActivity(event.sample)
        is DetectionEvent.Location -> onLocation(event.sample)
        is DetectionEvent.TimeTick -> onTimeTick(event.nowElapsedRealtimeNanos)
    }

    private fun onActivity(sample: ActivityTransitionSample): CandidateStartDecision {
        val current = state
        return when {
            sample.activityType == ActivityType.IN_VEHICLE &&
                sample.transitionType == TransitionType.ENTER &&
                current is State.Idle -> {
                state = State.Candidate(openedAtElapsedRealtimeNanos = sample.elapsedRealtimeNanos)
                CandidateStartDecision.CandidateOpened
            }
            sample.activityType == ActivityType.IN_VEHICLE &&
                sample.transitionType == TransitionType.EXIT &&
                current is State.Candidate -> {
                state = State.Idle
                CandidateStartDecision.Abandoned
            }
            else -> CandidateStartDecision.NoChange
        }
    }

    private fun onLocation(sample: LocationSample): CandidateStartDecision {
        val candidate = state as? State.Candidate ?: return CandidateStartDecision.NoChange

        if (isExpired(candidate, sample.elapsedRealtimeNanos)) {
            state = State.Idle
            return CandidateStartDecision.Abandoned
        }

        if (candidate.anchorLatitude == null || candidate.anchorLongitude == null) {
            state = candidate.copy(anchorLatitude = sample.latitude, anchorLongitude = sample.longitude)
            return CandidateStartDecision.NoChange
        }

        val elapsedSinceOpenMs = (sample.elapsedRealtimeNanos - candidate.openedAtElapsedRealtimeNanos) / 1_000_000
        val displacementMeters = haversineMeters(candidate.anchorLatitude, candidate.anchorLongitude, sample.latitude, sample.longitude)

        if (elapsedSinceOpenMs >= profile.minConfirmationDurationMs && displacementMeters >= profile.minDisplacementMeters) {
            val openedAt = candidate.openedAtElapsedRealtimeNanos
            state = State.Idle
            return CandidateStartDecision.Confirmed(openedAt, sample.elapsedRealtimeNanos)
        }
        return CandidateStartDecision.NoChange
    }

    private fun onTimeTick(nowElapsedRealtimeNanos: Long): CandidateStartDecision {
        val candidate = state as? State.Candidate ?: return CandidateStartDecision.NoChange
        if (isExpired(candidate, nowElapsedRealtimeNanos)) {
            state = State.Idle
            return CandidateStartDecision.Abandoned
        }
        return CandidateStartDecision.NoChange
    }

    private fun isExpired(candidate: State.Candidate, nowElapsedRealtimeNanos: Long): Boolean =
        (nowElapsedRealtimeNanos - candidate.openedAtElapsedRealtimeNanos) / 1_000_000 >= profile.maxCandidateWindowMs
}
