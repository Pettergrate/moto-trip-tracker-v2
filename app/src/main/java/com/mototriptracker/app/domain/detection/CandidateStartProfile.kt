package com.mototriptracker.app.domain.detection

/**
 * ADR-018: production thresholds stay field-gated by `EXP-008`/G4 — these
 * are explicit, documented placeholders (same posture as PRC-001's
 * `GAP_THRESHOLD_MS`), not tuned values, and [CandidateStartEngine] takes
 * this as a constructor parameter specifically so tests can inject
 * different profiles (F0.12 §4/TST-UNIT-002: "the algorithm must be
 * testable with injected profiles").
 *
 * [minDisplacementMeters] over [minConfirmationDurationMs]: at F0.3 §6's
 * "slow parking-lot departure" (~10 km/h ≈ 2.8 m/s), 15s covers ~42m — just
 * past this threshold, so a genuinely slow departure still confirms within
 * the window without needing "unrealistically high immediate speed" (§6
 * requirement 3). A normal urban departure (~20 km/h+) clears it several
 * times over. Neither number is validated against real rides yet.
 */
data class CandidateStartProfile(
    val minConfirmationDurationMs: Long = 15_000L,
    val minDisplacementMeters: Double = 40.0,
    /** F0.3 §5: a candidate must not wait indefinitely for evidence that never arrives. */
    val maxCandidateWindowMs: Long = 120_000L
)
