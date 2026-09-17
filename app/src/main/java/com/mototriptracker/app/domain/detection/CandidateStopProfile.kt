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
 */
data class CandidateStopProfile(
    val minConfirmationDurationMs: Long = 180_000L
)
