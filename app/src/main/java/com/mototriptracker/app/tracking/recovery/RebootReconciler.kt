package com.mototriptracker.app.tracking.recovery

import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import javax.inject.Inject

/**
 * REC-003/F0.10 §10: seals a capture orphaned by a real reboot - and only by
 * a real one. The trigger (the `BOOT_COMPLETED` receiver, or any process
 * start as a fallback for OEMs that block boot receivers) only says "check";
 * whether a reboot actually happened is decided by comparing the platform's
 * boot count with the one recorded at the previous run, because
 * `BOOT_COMPLETED` alone is not trustworthy (see [BootCountReader]).
 *
 * First run (nothing recorded yet) and an unavailable count both mean "can't
 * tell" and seal nothing; the elapsedRealtime discontinuity test still covers
 * process starts (`TrackingSessionCoordinator.reconcileActiveCaptureAfterReboot`).
 * The new boot count is recorded only *after* acting, so a failed seal is
 * retried at the next opportunity rather than lost.
 */
class RebootReconciler @Inject constructor(
    private val bootCountReader: BootCountReader,
    private val store: HandledExitStore,
    private val coordinator: TrackingSessionCoordinator
) {
    suspend fun reconcile(): TrackingSessionCoordinator.ReconcileOutcome {
        val current = bootCountReader.bootCount() ?: return TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile
        val last = store.lastSeenBootCount()
        val outcome = if (last != null && current != last) {
            coordinator.sealActiveCaptureAfterBoot()
        } else {
            TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile
        }
        store.setLastSeenBootCount(current)
        return outcome
    }
}
