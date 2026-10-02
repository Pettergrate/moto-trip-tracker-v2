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
 *
 * DET-008 (`ADR-025`, from a real day of field evidence - four whole rides, ~36 of ~64 minutes Android called
 * "in vehicle", never started a recording): [maxCandidateWindowMs] grows from 2 to 5 minutes, because Activity
 * Recognition emits no second `IN_VEHICLE` ENTER while the state persists - a window that expired during a wait
 * (engine on, warming up, a friend not ready) lost the whole ride, with no further chance. And a sustained GPS speed
 * ([vehicleSpeedMps] on [vehicleSpeedFixesRequired] consecutive fixes) now confirms too, next to displacement: the
 * speed is what a GPS fix reports most reliably, and displacement from a single anchor fix is only as good as that
 * anchor. 2.5 m/s (9 km/h) sits above brisk walking (~2 m/s) and below the ~10 km/h slow departure above. Both are
 * placeholders like the rest, and every abandonment now leaves its evidence ([CandidateEvidence]) to tune them with.
 */
data class CandidateStartProfile(
    val minConfirmationDurationMs: Long = 15_000L,
    val minDisplacementMeters: Double = 40.0,
    /** F0.3 §5: a candidate must not wait indefinitely for evidence that never arrives. */
    val maxCandidateWindowMs: Long = 300_000L,
    val vehicleSpeedMps: Float = 2.5f,
    val vehicleSpeedFixesRequired: Int = 3
)
