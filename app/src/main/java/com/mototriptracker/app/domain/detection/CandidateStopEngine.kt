package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.TransitionType

/** [CandidateStopEngine]'s output per [DetectionEvent] — most events change nothing. */
sealed interface CandidateStopDecision {
    data object NoChange : CandidateStopDecision
    data object CandidateOpened : CandidateStopDecision
    data object Abandoned : CandidateStopDecision
    data class Confirmed(
        val candidateOpenedAtElapsedRealtimeNanos: Long,
        val confirmedAtElapsedRealtimeNanos: Long,
        /** DP-007 explainability — why this confirmed, not just that it did. */
        val reasonCode: String
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
 * "zero speed alone MUST NOT finalize a Trip" — this engine never even sees
 * raw speed, only the already-filtered activity signal).
 *
 * No displacement check for confirmation (unlike `CandidateStartEngine`):
 * §7 requirement 5 treats *either* staying put *or* walking away as
 * evidence the ride ended, so there's no "insufficient movement" case to
 * guard against here the way candidate-start needed one. A `WALKING`/
 * `ON_FOOT` ENTER while a candidate is open confirms immediately — §7
 * requirement 5's own wording ("SHOULD contribute to confirming") for the
 * single strongest, still zero-threshold signal available; sustained
 * absence of a re-`ENTER` for [CandidateStopProfile.minConfirmationDurationMs]
 * confirms otherwise (DP-002 temporal confirmation). An `IN_VEHICLE` ENTER
 * before either abandons the candidate (§7 requirement 4).
 *
 * A pure, stateful reducer — no DAO/Clock/IdGenerator, no Android (ADR-013).
 * No live caller yet, same posture as `CandidateStartEngine`: turning a
 * [CandidateStopDecision.Confirmed] into an actual Finish is `AUTO-001`'s
 * job. Assumes its caller only routes events here while a real Trip is
 * actually being tracked (the mirror image of `CandidateStartEngine`'s
 * IDLE-only assumption) — this engine has no way to know that on its own.
 */
class CandidateStopEngine(private val profile: CandidateStopProfile = CandidateStopProfile()) {

    private sealed interface State {
        data object Tracking : State
        data class CandidateStop(val openedAtElapsedRealtimeNanos: Long) : State
    }

    private var state: State = State.Tracking

    val isCandidateOpen: Boolean get() = state is State.CandidateStop

    fun accept(event: DetectionEvent): CandidateStopDecision = when (event) {
        is DetectionEvent.Activity -> onActivity(event.sample)
        is DetectionEvent.Location -> checkGracePeriodElapsed(event.sample.elapsedRealtimeNanos)
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
                CandidateStopDecision.Abandoned
            }
            isWalkingAwayEvidence(sample) && current is State.CandidateStop -> {
                val openedAt = current.openedAtElapsedRealtimeNanos
                state = State.Tracking
                CandidateStopDecision.Confirmed(openedAt, sample.elapsedRealtimeNanos, REASON_WALKING_AWAY)
            }
            else -> CandidateStopDecision.NoChange
        }
    }

    private fun isWalkingAwayEvidence(sample: ActivityTransitionSample): Boolean =
        sample.transitionType == TransitionType.ENTER &&
            (sample.activityType == ActivityType.WALKING || sample.activityType == ActivityType.ON_FOOT)

    private fun checkGracePeriodElapsed(nowElapsedRealtimeNanos: Long): CandidateStopDecision {
        val candidate = state as? State.CandidateStop ?: return CandidateStopDecision.NoChange
        val elapsedMs = (nowElapsedRealtimeNanos - candidate.openedAtElapsedRealtimeNanos) / 1_000_000
        if (elapsedMs < profile.minConfirmationDurationMs) return CandidateStopDecision.NoChange

        val openedAt = candidate.openedAtElapsedRealtimeNanos
        state = State.Tracking
        return CandidateStopDecision.Confirmed(openedAt, nowElapsedRealtimeNanos, REASON_GRACE_PERIOD_ELAPSED)
    }

    companion object {
        const val REASON_WALKING_AWAY = "WALKING_AWAY_EVIDENCE"
        const val REASON_GRACE_PERIOD_ELAPSED = "GRACE_PERIOD_ELAPSED"
    }
}
