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
        val elapsedMs = (nowElapsedRealtimeNanos - lastCaptureEndedAtElapsedRealtimeNanos) / 1_000_000
        return elapsedMs < profile.suppressionDurationMs
    }
}
