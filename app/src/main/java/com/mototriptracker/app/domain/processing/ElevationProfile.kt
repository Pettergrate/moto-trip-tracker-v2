package com.mototriptracker.app.domain.processing

/**
 * PRC-003/ADR-018: field-gated placeholder, not validated against real
 * rides - same posture as the detection engines' own profiles
 * (`CandidateStopProfile`/`ForgottenPauseProfile`). `gps-location-research.md`
 * §14.3/GPS-011 explicitly rules out summing raw altitude deltas ("genera
 * sobreconteo por ruido vertical") and lists hysteresis/minimum-elevation-
 * change and vertical-accuracy filtering as the candidate methods to
 * validate in F0.6 - this profile drives exactly those two knobs, not an
 * invented third one.
 *
 * [maxVerticalAccuracyM] discards a sample only when its own reported
 * vertical accuracy is *known* and worse than this - a device that never
 * reports vertical accuracy at all isn't punished harder than one honest
 * enough to report poor accuracy.
 */
data class ElevationProfile(
    val maxVerticalAccuracyM: Double = 20.0,
    val minElevationChangeM: Double = 3.0,
    val minReliableSampleCount: Int = 3
)
