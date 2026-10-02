package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.domain.haversineMeters

/** [CandidateStopEngine]'s output per [DetectionEvent] — most events change nothing. */
sealed interface CandidateStopDecision {
    data object NoChange : CandidateStopDecision

    /** DET-009: says which label's EXIT opened it (`IN_VEHICLE_EXIT` / `ON_BICYCLE_EXIT`) - a ride can end on either. */
    data class CandidateOpened(val reasonCode: String) : CandidateStopDecision

    /** DET-008: the stop did not hold up - says why, and with what evidence. */
    data class Abandoned(val reasonCode: String, val evidence: CandidateEvidence) : CandidateStopDecision

    data class Confirmed(
        val candidateOpenedAtElapsedRealtimeNanos: Long,
        val confirmedAtElapsedRealtimeNanos: Long,
        /** DP-007 explainability — why this confirmed, not just that it did. */
        val reasonCode: String,
        val evidence: CandidateEvidence
    ) : CandidateStopDecision
}

/**
 * DET-003: F0.3 §5's CANDIDATE_STOP state / §7's stop-behavior requirements,
 * scoped to this task's own acceptance criterion — "traffic lights/short
 * stops do not end normal Trip fixtures" (§7 requirement 2). Mirrors
 * `CandidateStartEngine`'s design with the trigger reversed: `IN_VEHICLE`
 * EXIT (not ENTER) opens a candidate.
 *
 * Deliberately leans on Google's own Transition API filtering (F0.4 §4.2:
 * "el filtrado de transición evita tratar... quedar STILL en un semáforo...
 * como una salida real de IN_VEHICLE") rather than re-implementing that
 * filtering here — a genuine EXIT event is already meaningfully filtered
 * evidence, not a raw "speed is currently zero" sample (§7 requirement 1:
 * "zero speed alone MUST NOT finalize a Trip").
 *
 * **DET-008 (`ADR-025`): Activity Recognition's `WALKING`/`ON_FOOT` no longer
 * ends a Trip on its own.** DET-003 treated it as "the single strongest, still
 * zero-threshold signal" and confirmed on the spot. In real motorcycle riding
 * the classifier says WALKING at stops, in slow traffic, and on slow turns -
 * every one of 13 automatic Finishes in one field day came the instant it did,
 * cutting a ride into 13 fragments. DP-001 forbids a single activity label
 * from ending a Trip; §7 requirement 5 says walking away "SHOULD contribute
 * to confirming", not decide. It now only shows up in the evidence
 * ([CandidateEvidence.walkingSeen]).
 *
 * What ends a Trip is what always ended it in the silent case: the grace
 * period ([CandidateStopProfile.minConfirmationDurationMs]) passing with no
 * sign the ride went on. What *cancels* the candidate is the vehicle moving
 * again - `IN_VEHICLE` ENTER (§7 requirement 4) or, new here, GPS speed at
 * [CandidateStopProfile.resumeSpeedMps] or faster on
 * [CandidateStopProfile.resumeFixesRequired] consecutive fixes, so a light
 * turning green is recognised even when Activity Recognition never says
 * `IN_VEHICLE` again. The caller trims the walking tail off the Trip using
 * [CandidateStopDecision.Confirmed.candidateOpenedAtElapsedRealtimeNanos]
 * (§7 requirement 5: not "extending the route as a walking Trip").
 *
 * A pure, stateful reducer — no DAO/Clock/IdGenerator, no Android (ADR-013).
 * Assumes its caller only routes events here while a real Trip is
 * actually being tracked (the mirror image of `CandidateStartEngine`'s
 * IDLE-only assumption) — this engine has no way to know that on its own.
 */
class CandidateStopEngine(private val profile: CandidateStopProfile = CandidateStopProfile()) {

    private sealed interface State {
        data object Tracking : State
        data class CandidateStop(
            val openedAtElapsedRealtimeNanos: Long,
            val anchorLatitude: Double? = null,
            val anchorLongitude: Double? = null,
            val fixCount: Int = 0,
            val firstFixAtElapsedRealtimeNanos: Long? = null,
            val maxSpeedMps: Float? = null,
            val lastDisplacementMeters: Double? = null,
            val lastFix: LocationSample? = null,
            val consecutiveFastFixes: Int = 0,
            val walkingSeen: Boolean = false
        ) : State
    }

    private var state: State = State.Tracking

    /**
     * DET-009: the vehicle-like label last seen entering, if any. A change of label arrives as an EXIT of the old one
     * and an ENTER of the new one at the same instant, in either order; an EXIT that is not of the current label is
     * the old half of that change, not the end of the ride. `null` until one is seen (the engine starts at the moment
     * a ride is confirmed, with no memory of the label that started it), when any EXIT counts.
     */
    private var currentLabel: ActivityType? = null

    val isCandidateOpen: Boolean get() = state is State.CandidateStop

    fun accept(event: DetectionEvent): CandidateStopDecision = when (event) {
        is DetectionEvent.Activity -> onActivity(event.sample)
        is DetectionEvent.Location -> onLocation(event.sample)
        is DetectionEvent.TimeTick -> checkGracePeriodElapsed(event.nowElapsedRealtimeNanos)
    }

    private fun onActivity(sample: ActivityTransitionSample): CandidateStopDecision {
        val current = state
        val label = sample.activityType
        if (label.isVehicleLike()) {
            if (sample.transitionType == TransitionType.ENTER) {
                currentLabel = label
                if (current is State.CandidateStop) {
                    state = State.Tracking
                    return CandidateStopDecision.Abandoned(enterReason(label), evidenceOf(current, sample.elapsedRealtimeNanos))
                }
            } else if (current is State.Tracking && (currentLabel == null || currentLabel == label)) {
                currentLabel = null
                state = State.CandidateStop(openedAtElapsedRealtimeNanos = sample.elapsedRealtimeNanos)
                return CandidateStopDecision.CandidateOpened(exitReason(label))
            }
            return CandidateStopDecision.NoChange
        }
        return when {
            isWalkingAwayEvidence(sample) && current is State.CandidateStop -> {
                state = current.copy(walkingSeen = true)
                CandidateStopDecision.NoChange
            }
            else -> CandidateStopDecision.NoChange
        }
    }

    private fun enterReason(label: ActivityType) = if (label == ActivityType.ON_BICYCLE) REASON_ON_BICYCLE_ENTER else REASON_IN_VEHICLE_ENTER

    private fun exitReason(label: ActivityType) = if (label == ActivityType.ON_BICYCLE) REASON_ON_BICYCLE_EXIT else REASON_IN_VEHICLE_EXIT

    private fun isWalkingAwayEvidence(sample: ActivityTransitionSample): Boolean =
        sample.transitionType == TransitionType.ENTER &&
            (sample.activityType == ActivityType.WALKING || sample.activityType == ActivityType.ON_FOOT)

    private fun onLocation(sample: LocationSample): CandidateStopDecision {
        val candidate = state as? State.CandidateStop ?: return CandidateStopDecision.NoChange

        val speed = FixSpeed.effectiveSpeedMps(candidate.lastFix, sample)
        val fast = speed != null && speed >= profile.resumeSpeedMps
        val hasAnchor = candidate.anchorLatitude != null && candidate.anchorLongitude != null
        val updated = candidate.copy(
            anchorLatitude = candidate.anchorLatitude ?: sample.latitude,
            anchorLongitude = candidate.anchorLongitude ?: sample.longitude,
            fixCount = candidate.fixCount + 1,
            firstFixAtElapsedRealtimeNanos = candidate.firstFixAtElapsedRealtimeNanos ?: sample.elapsedRealtimeNanos,
            maxSpeedMps = listOfNotNull(candidate.maxSpeedMps, speed).maxOrNull(),
            lastDisplacementMeters = if (hasAnchor) {
                haversineMeters(candidate.anchorLatitude!!, candidate.anchorLongitude!!, sample.latitude, sample.longitude)
            } else {
                candidate.lastDisplacementMeters
            },
            lastFix = sample,
            consecutiveFastFixes = if (fast) candidate.consecutiveFastFixes + 1 else 0
        )

        if (updated.consecutiveFastFixes >= profile.resumeFixesRequired) {
            state = State.Tracking
            return CandidateStopDecision.Abandoned(REASON_MOVEMENT_RESUMED, evidenceOf(updated, sample.elapsedRealtimeNanos))
        }
        state = updated
        return checkGracePeriodElapsed(sample.elapsedRealtimeNanos)
    }

    private fun checkGracePeriodElapsed(nowElapsedRealtimeNanos: Long): CandidateStopDecision {
        val candidate = state as? State.CandidateStop ?: return CandidateStopDecision.NoChange
        val elapsedMs = (nowElapsedRealtimeNanos - candidate.openedAtElapsedRealtimeNanos) / 1_000_000
        if (elapsedMs < profile.minConfirmationDurationMs) return CandidateStopDecision.NoChange

        state = State.Tracking
        return CandidateStopDecision.Confirmed(
            candidateOpenedAtElapsedRealtimeNanos = candidate.openedAtElapsedRealtimeNanos,
            confirmedAtElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            reasonCode = REASON_GRACE_PERIOD_ELAPSED,
            evidence = evidenceOf(candidate, nowElapsedRealtimeNanos)
        )
    }

    private fun evidenceOf(candidate: State.CandidateStop, nowElapsedRealtimeNanos: Long) = CandidateEvidence(
        elapsedMs = (nowElapsedRealtimeNanos - candidate.openedAtElapsedRealtimeNanos) / 1_000_000,
        fixCount = candidate.fixCount,
        firstFixDelayMs = candidate.firstFixAtElapsedRealtimeNanos?.let { (it - candidate.openedAtElapsedRealtimeNanos) / 1_000_000 },
        maxSpeedMps = candidate.maxSpeedMps,
        displacementMeters = candidate.lastDisplacementMeters,
        walkingSeen = candidate.walkingSeen
    )

    companion object {
        const val REASON_GRACE_PERIOD_ELAPSED = "GRACE_PERIOD_ELAPSED"
        const val REASON_IN_VEHICLE_ENTER = "IN_VEHICLE_ENTER"
        const val REASON_ON_BICYCLE_ENTER = "ON_BICYCLE_ENTER"
        const val REASON_IN_VEHICLE_EXIT = "IN_VEHICLE_EXIT"
        const val REASON_ON_BICYCLE_EXIT = "ON_BICYCLE_EXIT"
        const val REASON_MOVEMENT_RESUMED = "MOVEMENT_RESUMED"
    }
}
