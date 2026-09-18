package com.mototriptracker.app.domain.detection

/**
 * ADR-018: field-gated placeholder, not validated against real rides - same
 * posture as `CandidateStopProfile`/`ForgottenPauseProfile`.
 *
 * Deliberately longer than `CandidateStopProfile`'s 180s auto-finish grace
 * period: that engine's confirmation actually ends the Trip, so it can
 * afford to be quick to fire and cheap to be wrong about (the rider can
 * always start a new one). This engine only posts a dismissible reminder,
 * but a false one is more of a nuisance than a silent auto-finish would be,
 * so it leans more conservative - long enough to comfortably outlast a fuel
 * stop, a viewpoint, or a meal (F0.3 §18 SCN-008/SCN-009/SCN-010), not just
 * a traffic light. [maxStationaryDisplacementMeters] is generous relative to
 * this project's observed real GPS jitter (~5-7m accuracy on-device) so
 * ordinary noise while parked can't repeatedly re-anchor the episode and
 * mask a genuinely forgotten Finish.
 */
data class ForgottenFinishProfile(
    val minStationaryDurationMs: Long = 600_000L,
    val maxStationaryDisplacementMeters: Double = 50.0
)
