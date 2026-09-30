package com.mototriptracker.app.domain.detection

/**
 * DET-006/F0.3 §9's "post-manual-finish suppression": "a rider/passenger
 * may press Finish while the device is still moving. Without protection,
 * the detector could immediately create a new candidate Trip." Pure
 * decision logic, zero Android/DAO dependency (ADR-013), same posture as
 * `domain.capability.CapabilityResolver` — whatever reads the last-ended
 * capture's timestamp lives in the caller (`ActivityTransitionReceiver`);
 * this only decides, never queries anything itself.
 */
object PostFinishSuppression {

    /**
     * @param lastCaptureEndedAtElapsedRealtimeNanos the most recently ended
     * capture's `endElapsedRealtimeNanos`, or `null` if none has ever ended
     * (a fresh install, or every capture so far is still ACTIVE).
     */
    fun isSuppressed(
        lastCaptureEndedAtElapsedRealtimeNanos: Long?,
        nowElapsedRealtimeNanos: Long,
        profile: PostFinishSuppressionProfile = PostFinishSuppressionProfile()
    ): Boolean {
        if (lastCaptureEndedAtElapsedRealtimeNanos == null) return false
        // REL-INV-010: elapsedRealtime is monotonic only within one boot session - a reboot resets it to (near) zero, so a
        // stored value from before it reads as later than "now". Found on the phone: with no reboot since, this stayed
        // silently correct; after any reboot following the last Finish, the subtraction went negative - always less than
        // the window - and suppressed every automatic start from then on, with no way out except a manual Finish (which
        // never goes through this check at all). A reboot is exactly what makes "still moving right after Finish" moot.
        if (nowElapsedRealtimeNanos < lastCaptureEndedAtElapsedRealtimeNanos) return false
        val elapsedMs = (nowElapsedRealtimeNanos - lastCaptureEndedAtElapsedRealtimeNanos) / 1_000_000
        return elapsedMs < profile.suppressionDurationMs
    }
}
