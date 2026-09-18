package com.mototriptracker.app.domain.processing

/** One point's resolved elevation (already accuracy-filtered/MSL-preferred by the caller) plus whether it's a gap boundary, per [ElevationCalculator]'s own input contract. */
data class ElevationSample(val elevationMeters: Double?, val isGapBoundary: Boolean)

/** FR-MET-009/010's four fields - each independently nullable per F0.7 §9.3's own rule ("una métrica desconocida queda nula, no cero"). */
data class ElevationMetrics(
    val minElevationM: Double?,
    val maxElevationM: Double?,
    val ascentM: Double?,
    val descentM: Double?
)

/**
 * PRC-003/FR-MET-009/010. A pure, stateless algorithm - no DAO/Clock/Android
 * (ADR-013), directly testable with hand-built [ElevationSample]s the same
 * way `CandidateStopEngine`/`ForgottenPauseEngine` are tested with minimal
 * synthetic `LocationSample`s.
 *
 * [ElevationMetrics.minElevationM]/[ElevationMetrics.maxElevationM] fill as
 * soon as any valid sample exists - F0.7 §9.3's own rule only gates
 * ascent/descent ("elevation gain/loss solo se rellena si el algoritmo
 * vigente se considera suficientemente fiable"), not the simple range.
 * [ElevationMetrics.ascentM]/[ElevationMetrics.descentM] additionally
 * require [ElevationProfile.minReliableSampleCount] valid samples before
 * being filled at all - noisy/unsupported altitude (too few usable
 * samples) degrades to `null`, never a fabricated number from a
 * statistically meaningless sequence.
 *
 * Ascent/descent use hysteresis over a running baseline, never a raw
 * Δaltitude sum (`gps-location-research.md` §14.3/GPS-011 explicitly rules
 * that out as overcounting GPS vertical noise): a change only counts once
 * it clears [ElevationProfile.minElevationChangeM] from the last confirmed
 * baseline, which then becomes the new baseline - a swing that never clears
 * the threshold is treated as noise and ignored, not partially credited.
 *
 * A gap-boundary sample never contributes a hysteresis delta across the
 * edge landing on it - the same posture `TripMetricsCalculator` already
 * takes for distance/speed (ADR-016/F0.5 §11.3: a gap must not silently
 * inflate a derived metric) - but it still resets the baseline from that
 * point onward and still counts toward the min/max range and the reliable-
 * sample-count gate.
 */
object ElevationCalculator {

    fun compute(samples: List<ElevationSample>, profile: ElevationProfile = ElevationProfile()): ElevationMetrics {
        val validElevations = samples.mapNotNull { it.elevationMeters }

        var baseline: Double? = null
        var ascent = 0.0
        var descent = 0.0
        for (sample in samples) {
            val elevation = sample.elevationMeters ?: continue

            if (sample.isGapBoundary) {
                baseline = elevation
                continue
            }

            val confirmedBaseline = baseline
            if (confirmedBaseline == null) {
                baseline = elevation
                continue
            }

            val delta = elevation - confirmedBaseline
            when {
                delta >= profile.minElevationChangeM -> {
                    ascent += delta
                    baseline = elevation
                }
                delta <= -profile.minElevationChangeM -> {
                    descent += -delta
                    baseline = elevation
                }
                // else: inside the hysteresis band - GPS vertical noise, ignore, keep the same baseline.
            }
        }

        val hasEnoughSamplesForGainLoss = validElevations.size >= profile.minReliableSampleCount
        return ElevationMetrics(
            minElevationM = validElevations.minOrNull(),
            maxElevationM = validElevations.maxOrNull(),
            ascentM = if (hasEnoughSamplesForGainLoss) ascent else null,
            descentM = if (hasEnoughSamplesForGainLoss) descent else null
        )
    }
}
