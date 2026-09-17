package com.mototriptracker.app.domain.processing

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
 * [minElevationM]/[maxElevationM]/[ascentM]/[descentM] are `PRC-003`'s job,
 * not duplicated here. `manualPauseDurationMs` is a true `0`, not an unknown
 * — no `ManualPauseIntervalEntity` producer exists yet (`TRK-003`), so none
 * can structurally exist.
 *
 * No Android dependency (ADR-013) — distance uses a plain-Kotlin haversine,
 * not `android.location.Location.distanceBetween()`.
 */
class TripMetricsCalculator @Inject constructor() {

    fun calculate(
        tripId: String,
        processingVersion: ProcessingVersion,
        computedAt: Long,
        parts: List<TripPartEntity>,
        processingResult: ProcessingEngine.Result,
        rawPointsByCapture: Map<String, List<RawTrackPointEntity>>
    ): TripStatisticsEntity {
        val rawBySourceKey = rawPointsByCapture.values.flatten().associateBy { it.captureId to it.sequenceNumber }

        var distanceM = 0.0
        val speedSamplesMps = mutableListOf<Float>()
        var previous: ProcessedTrackPointEntity? = null

        for (current in processingResult.processedPoints) {
            val isGapBoundary = current.pointRole == ProcessingEngine.POINT_ROLE_GAP_BOUNDARY

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
                val raw = rawBySourceKey[current.sourceCaptureId to current.sourceSequenceNumber]
                raw?.speedMps?.let { speedSamplesMps += it }
            }

            previous = current
        }

        val totalDurationMs = parts.sumOf { part ->
            val endNanos = checkNotNull(part.endElapsedRealtimeNanos) {
                "TripPart ${part.id} has no endElapsedRealtimeNanos - metrics require a Finished Trip"
            }
            (endNanos - part.startElapsedRealtimeNanos) / 1_000_000
        }

        val maxSpeedMps = speedSamplesMps.maxOrNull()?.toDouble()
        val averageSpeedMps = if (totalDurationMs > 0) distanceM / (totalDurationMs / 1000.0) else null

        val rejectedPointCount = processingResult.assessments.size - processingResult.processedPoints.size

        return TripStatisticsEntity(
            tripId = tripId,
            processingVersion = processingVersion,
            computedAt = computedAt,
            distanceM = distanceM,
            totalDurationMs = totalDurationMs,
            movingDurationMs = null,
            stoppedDurationMs = null,
            manualPauseDurationMs = 0L,
            maxSpeedMps = maxSpeedMps,
            averageSpeedMps = averageSpeedMps,
            averageMovingSpeedMps = null,
            minElevationM = null,
            maxElevationM = null,
            ascentM = null,
            descentM = null,
            validPointCount = processingResult.processedPoints.size,
            suspectPointCount = 0,
            rejectedPointCount = rejectedPointCount,
            gapCount = processingResult.gaps.size
        )
    }

}
