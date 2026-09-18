package com.mototriptracker.app.domain.detection

/**
 * ADR-018: field-gated placeholder, not validated against real rides - same
 * posture as `CandidateStartProfile`/`CandidateStopProfile`.
 *
 * Deliberately a larger bar than `CandidateStartProfile`'s own 15s/40m start
 * thresholds: [ForgottenPauseEngine] has no activity-classification gate
 * behind it (see that class's own KDoc for why), so a bigger displacement/
 * duration requirement stands in for the evidence an `IN_VEHICLE` signal
 * would otherwise provide, reducing false alarms from ordinary
 * walking-around-at-a-stop movement (F0.3 §18 SCN-008/SCN-009).
 */
data class ForgottenPauseProfile(
    val minConfirmationDurationMs: Long = 60_000L,
    val minDisplacementMeters: Double = 100.0
)
