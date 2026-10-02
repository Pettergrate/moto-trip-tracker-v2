package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.domain.haversineMeters

/** [CandidateStopEngine]'s output per [DetectionEvent] — most events change nothing. */
sealed interface CandidateStopDecision {
    data object NoChange : CandidateStopDecision
    data object CandidateOpened : CandidateStopDecision

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

    val isCandidateOpen: Boolean get() = state is State.CandidateStop

    fun accept(event: DetectionEvent): CandidateStopDecision = when (event) {
        is DetectionEvent.Activity -> onActivity(event.sample)
        is DetectionEvent.Location -> onLocation(event.sample)
        is DetectionEvent.TimeTick -> checkGracePeriodElapsed(event.nowElapsedRealtimeNanos)
    }

    private fun onActivity(sample: ActivityTransitionSample): CandidateStopDecision {
        val current = state
        return when {
            sample.activityType == ActivityType.IN_VEHICLE &&
                sample.transitionType == TransitionType.EXIT &&
                current is State.Tracking -> {
                state = State.CandidateStop(openedAtElapsedRealtimeNanos = sample.elapsedRealtimeNanos)
                CandidateStopDecision.CandidateOpened
            }
            sample.activityType == ActivityType.IN_VEHICLE &&
                sample.transitionType == TransitionType.ENTER &&
                current is State.CandidateStop -> {
                state = State.Tracking
                CandidateStopDecision.Abandoned(REASON_IN_VEHICLE_ENTER, evidenceOf(current, sample.elapsedRealtimeNanos))
            }
            isWalkingAwayEvidence(sample) && current is State.CandidateStop -> {
                state = current.copy(walkingSeen = true)
                CandidateStopDecision.NoChange
            }
            else -> CandidateStopDecision.NoChange
        }
    }

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
        const val REASON_MOVEMENT_RESUMED = "MOVEMENT_RESUMED"
    }
}
