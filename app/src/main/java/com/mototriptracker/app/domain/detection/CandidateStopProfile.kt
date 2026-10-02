package com.mototriptracker.app.domain.detection

/**
 * ADR-018: field-gated by `EXP-008`/G4, not frozen — a placeholder like
 * `CandidateStartProfile`'s, injected for the same testability reason
 * (F0.12 §4/TST-UNIT-003).
 *
 * F0.3 §7's opening line: "Automatic stop is intentionally more
 * conservative than temporary-stop recognition" — so this grace period is
 * deliberately much longer than `CandidateStartProfile`'s 15s start window.
 * 3 minutes comfortably outlasts a traffic light, a stop sign, or brief
 * congestion (F0.3 §7's own named tolerance list) while still being a
 * bounded wait, not an indefinite one. Not validated against real rides.
 *
 * DET-008 (`ADR-025`): a real day of riding showed that 13 of 13 automatic
 * Finishes came the instant Activity Recognition said WALKING - at stops,
 * not at the end of rides - so this grace period never got to protect
 * anything. It now applies whatever Activity Recognition says, and
 * *movement* is what cancels it: [resumeFixesRequired] consecutive fixes at
 * [resumeSpeedMps] or faster mean the vehicle is moving again (F0.3 §7
 * requirement 4: "Resumed movement during candidate-stop MUST return to
 * TRACKING without creating a split"). 3 m/s (~11 km/h) is above any walking
 * pace and below even a slow start from a light; two fixes in a row keep a
 * single GPS spike from cancelling a real stop (DP-001). Neither is validated
 * against real rides - every confirmation and cancellation leaves its
 * evidence ([CandidateEvidence]) to tune them with.
 */
data class CandidateStopProfile(
    val minConfirmationDurationMs: Long = 180_000L,
    val resumeSpeedMps: Float = 3.0f,
    val resumeFixesRequired: Int = 2
)
