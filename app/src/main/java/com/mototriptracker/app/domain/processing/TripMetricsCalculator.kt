package com.mototriptracker.app.domain.processing

import com.mototriptracker.app.core.database.entity.ManualPauseIntervalEntity
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.database.entity.TripStatisticsEntity
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.domain.haversineMeters
import javax.inject.Inject

/**
 * PRC-002: FR-MET-001/002/005/006/007 from [ProcessingEngine]'s already-
 * computed [ProcessingEngine.Result] — no second pass over raw evidence,
 * no re-deciding what's accepted.
 *
 * [movingDurationMs]/[stoppedDurationMs]/[averageMovingSpeedMps] stay `null`
 * (FR-MET-003/004: "MUST calculate... when technically reliable"): any
 * moving/stopped split needs a speed threshold, and GPS jitter means even a
 * stationary phone rarely reports exactly 0 m/s — no F0.6-validated cutoff
 * exists yet (that's `DET`-family territory), so this doesn't invent one.
 * [minElevationM]/[maxElevationM]/[ascentM]/[descentM] come from
 * `PRC-003`'s [ElevationCalculator] - a separate, independently-testable
 * pure algorithm rather than more inline logic here, since hysteresis-based
 * ascent/descent is a genuinely different kind of computation from a
 * running sum. `manualPauseDurationMs` (`TRK-003`) sums every closed
 * [ManualPauseIntervalEntity] for the Trip's captures - `totalDurationMs`
 * deliberately stays the full wall-clock span unchanged, so a caller wanting
 * "riding time" computes `totalDurationMs - manualPauseDurationMs` itself
 * rather than this class silently redefining what "total" means.
 *
 * No Android dependency (ADR-013) — distance uses a plain-Kotlin haversine,
 * not `android.location.Location.distanceBetween()`.
 */
class TripMetricsCalculator @Inject constructor() {

    // Not a Hilt-injected constructor parameter: Dagger resolves every
    // `@Inject constructor` parameter through the dependency graph and
    // ignores Kotlin default values, so a second constructor parameter here
    // would need its own `@Provides` binding for no real benefit - the
    // detection engines' own profile classes (`CandidateStopProfile` et al.)
    // are likewise never Hilt-injected, only ever constructed directly.
    private val elevationProfile = ElevationProfile()

    fun calculate(
        tripId: String,
        processingVersion: ProcessingVersion,
        computedAt: Long,
        parts: List<TripPartEntity>,
        processingResult: ProcessingEngine.Result,
        rawPointsByCapture: Map<String, List<RawTrackPointEntity>>,
        pausesByCapture: Map<String, List<ManualPauseIntervalEntity>> = emptyMap()
    ): TripStatisticsEntity {
        val rawBySourceKey = rawPointsByCapture.values.flatten().associateBy { it.captureId to it.sequenceNumber }

        var distanceM = 0.0
        val speedSamplesMps = mutableListOf<Float>()
        val elevationSamples = mutableListOf<ElevationSample>()
        var previous: ProcessedTrackPointEntity? = null

        for (current in processingResult.processedPoints) {
            val isGapBoundary = current.pointRole == ProcessingEngine.POINT_ROLE_GAP_BOUNDARY
            val raw = rawBySourceKey[current.sourceCaptureId to current.sourceSequenceNumber]

            // ADR-016/F0.5 §11.3: a gap must not silently inflate distance —
            // the edge landing on a gap-boundary point is excluded, not the
            // edges before/after it.
            if (previous != null && !isGapBoundary) {
                distanceM += haversineMeters(previous.latitude, previous.longitude, current.latitude, current.longitude)
            }

            // F0.5 §12.3: a reported speed right at a gap boundary is the
            // teleport-like case that must not inflate max speed - the one
            // rejection this task can make with zero invented numbers, the
            // same posture PRC-001 took for point assessment.
            if (!isGapBoundary) {
                raw?.speedMps?.let { speedSamplesMps += it }
            }

            elevationSamples += ElevationSample(
                elevationMeters = raw?.let { selectElevationMeters(it) },
                isGapBoundary = isGapBoundary
            )

            previous = current
        }

        val elevationMetrics = ElevationCalculator.compute(elevationSamples, elevationProfile)

        val totalDurationMs = parts.sumOf { part ->
            val endNanos = checkNotNull(part.endElapsedRealtimeNanos) {
                "TripPart ${part.id} has no endElapsedRealtimeNanos - metrics require a Finished Trip"
            }
            (endNanos - part.startElapsedRealtimeNanos) / 1_000_000
        }

        val maxSpeedMps = speedSamplesMps.maxOrNull()?.toDouble()
        val averageSpeedMps = if (totalDurationMs > 0) distanceM / (totalDurationMs / 1000.0) else null

        // TRK-003: every pause reaching here is already closed -
        // finishCapture closes any open one before a Trip can exist at all.
        // EDT-002: clipped to each part's own elapsed range - after a split,
        // two Trips share one capture, and a pause belongs only to the
        // half(s) of the ride it actually overlaps (a pause straddling the
        // cut counts partly in each), never to both in full. For a Trip
        // whose parts are whole captures (every Trip before EDT-001/002)
        // this clips nothing.
        val manualPauseDurationMs = parts.sumOf { part ->
            val partEnd = checkNotNull(part.endElapsedRealtimeNanos) {
                "TripPart ${part.id} has no endElapsedRealtimeNanos - metrics require a Finished Trip"
            }
            pausesByCapture[part.captureId].orEmpty().sumOf { pause ->
                val pauseEnd = checkNotNull(pause.endElapsedRealtimeNanos) {
                    "ManualPauseInterval ${pause.id} has no endElapsedRealtimeNanos - finishCapture should have closed it"
                }
                val overlapNanos = minOf(pauseEnd, partEnd) - maxOf(pause.startElapsedRealtimeNanos, part.startElapsedRealtimeNanos)
                if (overlapNanos > 0) overlapNanos / 1_000_000 else 0L
            }
        }

        val rejectedPointCount = processingResult.assessments.size - processingResult.processedPoints.size

        return TripStatisticsEntity(
            tripId = tripId,
            processingVersion = processingVersion,
            computedAt = computedAt,
            distanceM = distanceM,
            totalDurationMs = totalDurationMs,
            movingDurationMs = null,
            stoppedDurationMs = null,
            manualPauseDurationMs = manualPauseDurationMs,
            maxSpeedMps = maxSpeedMps,
            averageSpeedMps = averageSpeedMps,
            averageMovingSpeedMps = null,
            minElevationM = elevationMetrics.minElevationM,
            maxElevationM = elevationMetrics.maxElevationM,
            startElevationM = elevationMetrics.startElevationM,
            endElevationM = elevationMetrics.endElevationM,
            ascentM = elevationMetrics.ascentM,
            descentM = elevationMetrics.descentM,
            validPointCount = processingResult.processedPoints.size,
            suspectPointCount = 0,
            rejectedPointCount = rejectedPointCount,
            gapCount = processingResult.gaps.size
        )
    }

    /**
     * GPS-010: prefer MSL altitude over the WGS84 ellipsoid when available.
     * A sample is discarded only when its own vertical accuracy is *known*
     * and worse than [ElevationProfile.maxVerticalAccuracyM] - a device that
     * never reports vertical accuracy at all isn't punished harder than one
     * honest enough to report poor accuracy.
     */
    private fun selectElevationMeters(raw: RawTrackPointEntity): Double? {
        val verticalAccuracy = raw.verticalAccuracyM
        if (verticalAccuracy != null && verticalAccuracy.toDouble() > elevationProfile.maxVerticalAccuracyM) return null
        return raw.altitudeMslM ?: raw.altitudeEllipsoidM
    }
}
