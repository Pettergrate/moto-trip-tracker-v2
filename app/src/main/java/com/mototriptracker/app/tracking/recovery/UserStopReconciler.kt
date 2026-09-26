package com.mototriptracker.app.tracking.recovery

import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import javax.inject.Inject

/**
 * REC-004/F0.10 §9 + §25: when the previous process was ended *by the user*
 * (Task Manager "Stop" on Android 13+, or Settings "Force stop" - both report
 * `REASON_USER_REQUESTED`), a capture still `ACTIVE` is not silently revived.
 * Unlike a crash or an OOM kill, the user just said "stop": the Android
 * platform doesn't restart the service, and the policy is not to
 * auto-resume "ciegamente una captura previa" - the capture is sealed from
 * its last evidence as an interrupted, visible partial Trip, and recording
 * again is the user's own explicit Start. Deliberately starts nothing itself
 * (no foreground service, no Auto Tracking revival: "no debe usar el evento
 * de recuperación para revivir de inmediato el FGS que el usuario acaba de
 * detener").
 *
 * Idempotent through [HandledExitStore]: each recorded exit is acted on at
 * most once, so a *later* capture is never sealed because of an *old* exit.
 */
class UserStopReconciler @Inject constructor(
    private val exitReader: ProcessExitReasonReader,
    private val handledExitStore: HandledExitStore,
    private val coordinator: TrackingSessionCoordinator
) {
    sealed interface Outcome {
        /** No new exit info, an exit we already handled, or one that wasn't a user stop. */
        data object NothingToDo : Outcome
        data class Sealed(val captureId: String, val partialTripId: String?) : Outcome
    }

    suspend fun reconcile(): Outcome {
        val exit = exitReader.latestExit() ?: return Outcome.NothingToDo
        if (exit.timestampMillis <= handledExitStore.lastHandledTimestamp()) return Outcome.NothingToDo

        val outcome = if (exit.wasUserRequested) {
            when (val sealed = coordinator.sealActiveCaptureAfterUserStop()) {
                TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile -> Outcome.NothingToDo
                is TrackingSessionCoordinator.ReconcileOutcome.SealedAfterReboot -> Outcome.Sealed(sealed.captureId, sealed.partialTripId)
            }
        } else {
            Outcome.NothingToDo
        }
        // Only after acting: if sealing throws, the exit stays unhandled and the next start retries it.
        handledExitStore.markHandled(exit.timestampMillis)
        return outcome
    }
}
