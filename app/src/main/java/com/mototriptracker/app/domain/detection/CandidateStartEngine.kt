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

    /** DET-008: says why, and with what evidence - an abandoned candidate used to vanish without a trace. */
    data class Abandoned(val reasonCode: String, val evidence: CandidateEvidence) : CandidateStartDecision

    data class Confirmed(
        val candidateOpenedAtElapsedRealtimeNanos: Long,
        val confirmedAtElapsedRealtimeNanos: Long,
        /** DP-007 explainability — which kind of evidence confirmed it. */
        val reasonCode: String,
        val evidence: CandidateEvidence
    ) : CandidateStartDecision
}

/**
 * DET-002: F0.3 §5's CANDIDATE_START state, scoped to exactly its own
 * acceptance criterion — "no single sample starts a Trip by itself"
 * (DP-001). `IN_VEHICLE` ENTER (ADR-007's passive trigger; DET-009/ADR-026: or
 * `ON_BICYCLE`, which Android also gives a motorcycle) only *opens* a
 * candidate; confirming it needs sustained time (DP-002 temporal
 * confirmation) AND real movement - either displacement from the
 * candidate's anchor point, or (DET-008) a sustained GPS speed. Activity
 * evidence alone or a single GPS jump alone can never confirm (SCN-015,
 * SCN-025).
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
            /** DET-009: the vehicle-like label that is current; only its EXIT abandons (a flip to the other label does not). */
            val label: ActivityType,
            val anchorLatitude: Double? = null,
            val anchorLongitude: Double? = null,
            val fixCount: Int = 0,
            val firstFixAtElapsedRealtimeNanos: Long? = null,
            val maxSpeedMps: Float? = null,
            val lastDisplacementMeters: Double? = null,
            val lastFix: LocationSample? = null,
            val consecutiveFastFixes: Int = 0
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
        val label = sample.activityType
        return when {
            !label.isVehicleLike() -> CandidateStartDecision.NoChange
            sample.transitionType == TransitionType.ENTER && current is State.Idle -> {
                state = State.Candidate(openedAtElapsedRealtimeNanos = sample.elapsedRealtimeNanos, label = label)
                CandidateStartDecision.CandidateOpened
            }
            // The other vehicle-like label took over (ADR-026): the same ride, not a new candidate and not an end.
            sample.transitionType == TransitionType.ENTER && current is State.Candidate -> {
                state = current.copy(label = label)
                CandidateStartDecision.NoChange
            }
            // An EXIT of a label that is no longer the current one is the old half of a label change.
            sample.transitionType == TransitionType.EXIT && current is State.Candidate && label == current.label ->
                abandon(current, exitReason(label), sample.elapsedRealtimeNanos)
            else -> CandidateStartDecision.NoChange
        }
    }

    private fun exitReason(label: ActivityType) = if (label == ActivityType.ON_BICYCLE) REASON_ON_BICYCLE_EXIT else REASON_IN_VEHICLE_EXIT

    private fun onLocation(sample: LocationSample): CandidateStartDecision {
        val candidate = state as? State.Candidate ?: return CandidateStartDecision.NoChange

        if (isExpired(candidate, sample.elapsedRealtimeNanos)) {
            return abandon(candidate, REASON_WINDOW_EXPIRED, sample.elapsedRealtimeNanos)
        }

        val speed = FixSpeed.effectiveSpeedMps(candidate.lastFix, sample)
        val fast = speed != null && speed >= profile.vehicleSpeedMps
        var updated = candidate.copy(
            fixCount = candidate.fixCount + 1,
            firstFixAtElapsedRealtimeNanos = candidate.firstFixAtElapsedRealtimeNanos ?: sample.elapsedRealtimeNanos,
            maxSpeedMps = listOfNotNull(candidate.maxSpeedMps, speed).maxOrNull(),
            lastFix = sample,
            consecutiveFastFixes = if (fast) candidate.consecutiveFastFixes + 1 else 0
        )

        if (candidate.anchorLatitude == null || candidate.anchorLongitude == null) {
            state = updated.copy(anchorLatitude = sample.latitude, anchorLongitude = sample.longitude)
            return CandidateStartDecision.NoChange
        }

        val elapsedSinceOpenMs = (sample.elapsedRealtimeNanos - candidate.openedAtElapsedRealtimeNanos) / 1_000_000
        val displacementMeters = haversineMeters(candidate.anchorLatitude, candidate.anchorLongitude, sample.latitude, sample.longitude)
        updated = updated.copy(lastDisplacementMeters = displacementMeters)

        if (elapsedSinceOpenMs >= profile.minConfirmationDurationMs) {
            val byDisplacement = displacementMeters >= profile.minDisplacementMeters
            val bySpeed = updated.consecutiveFastFixes >= profile.vehicleSpeedFixesRequired
            if (byDisplacement || bySpeed) {
                state = State.Idle
                return CandidateStartDecision.Confirmed(
                    candidateOpenedAtElapsedRealtimeNanos = candidate.openedAtElapsedRealtimeNanos,
                    confirmedAtElapsedRealtimeNanos = sample.elapsedRealtimeNanos,
                    reasonCode = if (byDisplacement) REASON_CONFIRMED_DISPLACEMENT else REASON_CONFIRMED_SPEED,
                    evidence = evidenceOf(updated, sample.elapsedRealtimeNanos)
                )
            }
        }
        state = updated
        return CandidateStartDecision.NoChange
    }

    private fun onTimeTick(nowElapsedRealtimeNanos: Long): CandidateStartDecision {
        val candidate = state as? State.Candidate ?: return CandidateStartDecision.NoChange
        if (isExpired(candidate, nowElapsedRealtimeNanos)) {
            return abandon(candidate, REASON_WINDOW_EXPIRED, nowElapsedRealtimeNanos)
        }
        return CandidateStartDecision.NoChange
    }

    private fun abandon(candidate: State.Candidate, reasonCode: String, nowElapsedRealtimeNanos: Long): CandidateStartDecision.Abandoned {
        state = State.Idle
        return CandidateStartDecision.Abandoned(reasonCode, evidenceOf(candidate, nowElapsedRealtimeNanos))
    }

    private fun evidenceOf(candidate: State.Candidate, nowElapsedRealtimeNanos: Long) = CandidateEvidence(
        elapsedMs = (nowElapsedRealtimeNanos - candidate.openedAtElapsedRealtimeNanos) / 1_000_000,
        fixCount = candidate.fixCount,
        firstFixDelayMs = candidate.firstFixAtElapsedRealtimeNanos?.let { (it - candidate.openedAtElapsedRealtimeNanos) / 1_000_000 },
        maxSpeedMps = candidate.maxSpeedMps,
        displacementMeters = candidate.lastDisplacementMeters
    )

    private fun isExpired(candidate: State.Candidate, nowElapsedRealtimeNanos: Long): Boolean =
        (nowElapsedRealtimeNanos - candidate.openedAtElapsedRealtimeNanos) / 1_000_000 >= profile.maxCandidateWindowMs

    companion object {
        const val REASON_CONFIRMED_DISPLACEMENT = "CONFIRMED_DISPLACEMENT"
        const val REASON_CONFIRMED_SPEED = "CONFIRMED_SPEED"
        const val REASON_IN_VEHICLE_EXIT = "IN_VEHICLE_EXIT"
        const val REASON_ON_BICYCLE_EXIT = "ON_BICYCLE_EXIT"
        const val REASON_WINDOW_EXPIRED = "WINDOW_EXPIRED"
    }
}
